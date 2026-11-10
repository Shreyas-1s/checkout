package com.boldfit.checkout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class CheckoutService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public CheckoutService(JdbcTemplate db, ObjectMapper json) { this.db=db; this.json=json; }
    public List<Map<String,Object>> products() { return db.queryForList("SELECT * FROM product ORDER BY id"); }

    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    /** INSERT ON CONFLICT waits for the concurrent owner; both response and effects share this transaction. */
    private String idempotent(String customer, String operation, String key, Object input, Supplier<Object> action) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,100}"))
            throw new ApiException(400,"INVALID_KEY","Use an 8–100 character idempotency key.");
        String fingerprint=hash(encode(input));
        int inserted=db.update("INSERT INTO idempotency(customer_id,operation,key,fingerprint) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",customer,operation,key,fingerprint);
        Map<String,Object> record=db.queryForMap("SELECT * FROM idempotency WHERE customer_id=? AND operation=? AND key=? FOR UPDATE",customer,operation,key);
        if (!fingerprint.equals(record.get("fingerprint"))) throw new ApiException(409,"KEY_REUSED","This key was used for a different request.");
        if (inserted==0) return (String)record.get("response");
        String response=encode(action.get());
        db.update("UPDATE idempotency SET response=? WHERE customer_id=? AND operation=? AND key=?",response,customer,operation,key);
        return response;
    }

    @Transactional
    public String checkout(String customer, String key, List<ApiController.Item> items) {
        TreeMap<String,Integer> quantities=new TreeMap<>();
        items.forEach(item -> quantities.merge(item.skuId(),item.quantity(),Integer::sum));
        if (quantities.values().stream().anyMatch(q -> q>10)) throw new ApiException(400,"INVALID_QUANTITY","Maximum ten units per product.");
        return idempotent(customer,"checkout",key,quantities,() -> {
            UUID id=UUID.randomUUID();
            List<Map<String,Object>> lines=new ArrayList<>();
            long total=0;
            // Consistent SKU order avoids inverted lock acquisition for multi-item carts.
            for (var entry: quantities.entrySet()) {
                int updated=db.update("UPDATE product SET available=available-? WHERE id=? AND available>=?",entry.getValue(),entry.getKey(),entry.getValue());
                if(updated==0) throw new ApiException(409,"OUT_OF_STOCK","An item is no longer available. Refresh your bag.");
                var product=db.queryForMap("SELECT * FROM product WHERE id=?",entry.getKey());
                long price=((Number)product.get("price")).longValue();
                total=Math.addExact(total,Math.multiplyExact(price,entry.getValue()));
                lines.add(Map.of("skuId",entry.getKey(),"name",product.get("name"),"quantity",entry.getValue(),"unitPrice",price));
            }
            db.update("INSERT INTO customer_order(id,customer_id,status,total,expires_at) VALUES (?,?,'AWAITING_PAYMENT',?,now()+interval '15 minutes')",id,customer,total);
            for(var line:lines) db.update("INSERT INTO order_line VALUES (?,?,?,?,?)",id,line.get("skuId"),line.get("name"),line.get("quantity"),line.get("unitPrice"));
            return order(customer,id);
        });
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> order(String customer, UUID id) {
        Map<String,Object> order=owned(customer,id,false);
        order.put("lines",db.queryForList("SELECT sku_id AS \"skuId\",name,quantity,unit_price AS \"unitPrice\" FROM order_line WHERE order_id=? ORDER BY sku_id",id));
        order.put("attempts",db.queryForList("SELECT a.id,a.status,a.created_at AS \"createdAt\",a.method,EXISTS(SELECT 1 FROM mock_delivery d WHERE d.attempt_id=a.id) AS submitted FROM payment_attempt a WHERE a.order_id=? ORDER BY a.created_at,a.id",id));
        return order;
    }
    private Map<String,Object> owned(String customer, UUID id, boolean lock) {
        var rows=db.queryForList("SELECT id,status,total,currency,expires_at AS \"expiresAt\",created_at AS \"createdAt\",stock_released AS \"stockReleased\" FROM customer_order WHERE id=? AND customer_id=?"+(lock?" FOR UPDATE":""),id,customer);
        if(rows.isEmpty()) throw new ApiException(404,"NOT_FOUND","Order not found.");
        return rows.get(0);
    }
    @Transactional
    public String attempt(String customer, UUID orderId, String key) {
        // Ownership is checked even for a replay.
        owned(customer,orderId,false);
        return idempotent(customer,"attempt:"+orderId,key,Map.of("orderId",orderId),() -> {
            var order=owned(customer,orderId,true);
            if(!order.get("status").equals("AWAITING_PAYMENT") || expired(order))
                throw new ApiException(409,"ORDER_NOT_PAYABLE","This order is no longer payable.");
            var attempts=db.queryForList("SELECT id,status FROM payment_attempt WHERE order_id=? ORDER BY created_at DESC",orderId);
            if(attempts.stream().anyMatch(a -> !a.get("status").equals("FAILED")))
                throw new ApiException(409,"ATTEMPT_IN_PROGRESS","Resolve the current payment before starting another.");
            UUID id=UUID.randomUUID();
            db.update("INSERT INTO payment_attempt(id,order_id,status) VALUES (?,?,'PENDING')",id,orderId);
            return Map.of("id",id,"orderId",orderId,"status","PENDING");
        });
    }
    private boolean expired(Map<String,Object> order) {
        return ((java.sql.Timestamp)order.get("expiresAt")).toInstant().isBefore(Instant.now());
    }
    private void release(UUID orderId) {
        if(db.update("UPDATE customer_order SET stock_released=true WHERE id=? AND stock_released=false",orderId)==1) {
            for(var line:db.queryForList("SELECT sku_id,quantity FROM order_line WHERE order_id=? ORDER BY sku_id",orderId))
                db.update("UPDATE product SET available=available+? WHERE id=?",line.get("quantity"),line.get("sku_id"));
        }
    }
    @Transactional
    public void expire(UUID id) {
        var order=db.queryForMap("SELECT status,expires_at AS \"expiresAt\" FROM customer_order WHERE id=? FOR UPDATE",id);
        if(order.get("status").equals("AWAITING_PAYMENT") && expired(order)) {
            release(id);
            db.update("UPDATE customer_order SET status='EXPIRED' WHERE id=?",id);
        }
    }
    public List<UUID> expiring() {
        return db.queryForList("SELECT id FROM customer_order WHERE status='AWAITING_PAYMENT' AND expires_at<now()",UUID.class);
    }

    @Transactional
    public void receive(PaymentEvent event, String payload) {
        var attempts=db.queryForList("SELECT order_id FROM payment_attempt WHERE id=?",event.attemptId());
        if(attempts.isEmpty() || !attempts.get(0).get("order_id").equals(event.orderId()))
            throw new ApiException(400,"UNKNOWN_PAYMENT","Payment does not match this order.");
        var order=db.queryForMap("SELECT status,total,currency,expires_at AS \"expiresAt\" FROM customer_order WHERE id=? FOR UPDATE",event.orderId());
        String bodyHash=hash(payload);
        int inserted=db.update("INSERT INTO webhook_event(id,payload_hash) VALUES (?,?) ON CONFLICT DO NOTHING",event.eventId(),bodyHash);
        if(inserted==0) {
            if(!bodyHash.equals(db.queryForObject("SELECT payload_hash FROM webhook_event WHERE id=?",String.class,event.eventId())))
                throw new ApiException(409,"EVENT_REUSED","Event ID has a different payload.");
            return;
        }
        String state=db.queryForObject("SELECT status FROM payment_attempt WHERE id=?",String.class,event.attemptId());
        boolean success=event.outcome().equals("SUCCEEDED");
        boolean exact=event.amount()==((Number)order.get("total")).longValue() && event.currency().equals(order.get("currency"));
        if(state.equals("SUCCEEDED") && success && exact || state.equals("FAILED") && !success && exact) return;
        // A conflicting terminal event or amount mismatch is visible for reconciliation.
        if(!state.equals("PENDING") || !exact) { review(event); return; }
        if(order.get("status").equals("AWAITING_PAYMENT") && expired(order)) {
            release(event.orderId());
            db.update("UPDATE customer_order SET status='EXPIRED' WHERE id=?",event.orderId());
            order.put("status","EXPIRED");
        }
        if(success && !order.get("status").equals("AWAITING_PAYMENT")) { review(event); return; }
        db.update("UPDATE payment_attempt SET status=? WHERE id=?",success?"SUCCEEDED":"FAILED",event.attemptId());
        if(success) db.update("UPDATE customer_order SET status='CONFIRMED' WHERE id=?",event.orderId());
    }
    private void review(PaymentEvent event) {
        db.update("UPDATE payment_attempt SET status='REVIEW' WHERE id=?",event.attemptId());
        db.update("UPDATE customer_order SET status='PAYMENT_REVIEW' WHERE id=?",event.orderId());
    }

    @Transactional
    public void simulate(String customer, UUID attemptId, String outcome) {
        var rows=db.queryForList("SELECT order_id FROM payment_attempt WHERE id=?",attemptId);
        if(rows.isEmpty()) throw new ApiException(404,"NOT_FOUND","Payment not found.");
        UUID orderId=(UUID)rows.get(0).get("order_id");
        var order=owned(customer,orderId,true);
        var previous=db.queryForList("SELECT payload FROM mock_delivery WHERE attempt_id=?",attemptId);
        if(!previous.isEmpty()) {
            try {
                var existing=json.readValue((String)previous.get(0).get("payload"),PaymentEvent.class);
                String original=existing.outcome().equals("FAILED")?"FAILED":existing.amount()==((Number)order.get("total")).longValue()?"SUCCEEDED":"PARTIAL";
                if(!original.equals(outcome)) throw new ApiException(409,"MOCK_ALREADY_SETTLED","This mock payment already has a fixed outcome.");
                db.update("UPDATE mock_delivery SET delivered=false,next_delivery=now() WHERE attempt_id=?",attemptId);
                return;
            } catch(JsonProcessingException ex) { throw new IllegalStateException(ex); }
        }
        if(!order.get("status").equals("AWAITING_PAYMENT") || expired(order))
            throw new ApiException(409,"ORDER_NOT_PAYABLE","Your reservation has expired or the order is no longer payable.");
        String status=db.queryForObject("SELECT status FROM payment_attempt WHERE id=?",String.class,attemptId);
        if(!status.equals("PENDING")) throw new ApiException(409,"NOT_PENDING","Payment is no longer pending.");
        long total=((Number)order.get("total")).longValue();
        UUID eventId=UUID.randomUUID();
        var event=new PaymentEvent(eventId,attemptId,orderId,outcome.equals("FAILED")?"FAILED":"SUCCEEDED",outcome.equals("PARTIAL")?total/2:total,"INR");
        db.update("INSERT INTO mock_delivery(attempt_id,event_id,payload) VALUES (?,?,?)",attemptId,eventId,encode(event));
    }
    @Transactional
    public void pay(String customer, UUID attemptId, String method) {
        // simulate acquires the order lock and durably queues exactly one fixed outcome.
        // Payment credentials are intentionally never accepted by this mock provider.
        simulate(customer,attemptId,"SUCCEEDED");
        String previous=db.queryForObject("SELECT method FROM payment_attempt WHERE id=?",String.class,attemptId);
        if(previous!=null && !previous.equals(method))
            throw new ApiException(409,"METHOD_ALREADY_SELECTED","This payment was already submitted with a different method.");
        db.update("UPDATE payment_attempt SET method=? WHERE id=?",method,attemptId);
    }
    public record PaymentEvent(UUID eventId, UUID attemptId, UUID orderId, String outcome, long amount, String currency) {}
}
