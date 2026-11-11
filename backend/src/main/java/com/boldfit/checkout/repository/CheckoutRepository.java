package com.boldfit.checkout.repository;

import com.boldfit.checkout.domain.*;
import com.boldfit.checkout.dto.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL operations participate in the calling service transaction. */
@Repository
public class CheckoutRepository {
  private final JdbcTemplate db;

  public CheckoutRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<Product> findProducts() {
    return db.query(
        "SELECT * FROM product ORDER BY id",
        (rs, n) ->
            new Product(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getString("description"),
                rs.getLong("price"),
                rs.getInt("available")));
  }

  public int claimIdempotency(String customer, String operation, String key, String fingerprint) {
    return db.update(
        "INSERT INTO idempotency(customer_id,operation,key,fingerprint) VALUES (?,?,?,?) ON"
            + " CONFLICT DO NOTHING",
        customer,
        operation,
        key,
        fingerprint);
  }

  public IdempotencyRecord lockIdempotency(String customer, String operation, String key) {
    return db.queryForObject(
        "SELECT * FROM idempotency WHERE customer_id=? AND operation=? AND key=? FOR UPDATE",
        (rs, n) -> new IdempotencyRecord(rs.getString("fingerprint"), rs.getString("response")),
        customer,
        operation,
        key);
  }

  public int saveIdempotentResponse(
      String response, String customer, String operation, String key) {
    return db.update(
        "UPDATE idempotency SET response=? WHERE customer_id=? AND operation=? AND key=?",
        response,
        customer,
        operation,
        key);
  }

  public int reserveStock(int quantity, String skuId, int required) {
    return db.update(
        "UPDATE product SET available=available-? WHERE id=? AND available>=?",
        quantity,
        skuId,
        required);
  }

  public Product findProduct(String skuId) {
    return db.queryForObject(
        "SELECT * FROM product WHERE id=?",
        (rs, n) ->
            new Product(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getString("description"),
                rs.getLong("price"),
                rs.getInt("available")),
        skuId);
  }

  public int insertOrder(UUID id, String customer, long total) {
    return db.update(
        "INSERT INTO customer_order(id,customer_id,status,total,expires_at) VALUES"
            + " (?,?,'AWAITING_PAYMENT',?,now()+interval '15 minutes')",
        id,
        customer,
        total);
  }

  public int insertOrderLine(UUID id, String skuId, String name, int quantity, long unitPrice) {
    return db.update(
        "INSERT INTO order_line VALUES (?,?,?,?,?)", id, skuId, name, quantity, unitPrice);
  }

  public List<OrderLine> findOrderLines(UUID id) {
    return db.query(
        "SELECT sku_id AS \"skuId\",name,quantity,unit_price AS \"unitPrice\" FROM order_line WHERE"
            + " order_id=? ORDER BY sku_id",
        (rs, n) ->
            new OrderLine(
                rs.getString("skuId"),
                rs.getString("name"),
                rs.getInt("quantity"),
                rs.getLong("unitPrice")),
        id);
  }

  public List<Attempt> findAttempts(UUID id) {
    return db.query(
        "SELECT a.id,a.status,a.created_at AS \"createdAt\",a.method,EXISTS(SELECT 1 FROM"
            + " mock_delivery d WHERE d.attempt_id=a.id) AS submitted,COALESCE((SELECT CASE WHEN"
            + " d.delivered THEN 'DELIVERED' WHEN d.exhausted THEN 'EXHAUSTED' ELSE 'QUEUED' END"
            + " FROM mock_delivery d WHERE d.attempt_id=a.id),'NOT_SUBMITTED') AS \"deliveryState\""
            + " FROM payment_attempt a WHERE a.order_id=? ORDER BY a.created_at,a.id",
        (rs, n) ->
            new Attempt(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getTimestamp("createdAt").toInstant(),
                rs.getString("method"),
                rs.getBoolean("submitted"),
                rs.getString("deliveryState")),
        id);
  }

  public List<OrderSnapshot> findOwnedOrder(UUID id, String customer, boolean lock) {
    return db.query(
        "SELECT id,status,total,currency,expires_at AS \"expiresAt\",created_at AS"
            + " \"createdAt\",stock_released AS \"stockReleased\" FROM customer_order WHERE id=?"
            + " AND customer_id=?"
            + (lock ? " FOR UPDATE" : ""),
        (rs, n) ->
            new OrderSnapshot(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getLong("total"),
                rs.getString("currency"),
                rs.getTimestamp("expiresAt").toInstant(),
                rs.getTimestamp("createdAt").toInstant(),
                rs.getBoolean("stockReleased")),
        id,
        customer);
  }

  public List<AttemptState> findAttemptStates(UUID orderId) {
    return db.query(
        "SELECT id,status FROM payment_attempt WHERE order_id=? ORDER BY created_at DESC",
        (rs, n) -> new AttemptState(rs.getObject("id", UUID.class), rs.getString("status")),
        orderId);
  }

  public int insertAttempt(UUID id, UUID orderId) {
    return db.update(
        "INSERT INTO payment_attempt(id,order_id,status) VALUES (?,?,'PENDING')", id, orderId);
  }

  public int markStockReleased(UUID orderId) {
    return db.update(
        "UPDATE customer_order SET stock_released=true WHERE id=? AND stock_released=false",
        orderId);
  }

  public List<OrderLine> findReservedLines(UUID orderId) {
    return db.query(
        "SELECT sku_id,name,quantity,unit_price FROM order_line WHERE order_id=? ORDER BY sku_id",
        (rs, n) ->
            new OrderLine(
                rs.getString("sku_id"),
                rs.getString("name"),
                rs.getInt("quantity"),
                rs.getLong("unit_price")),
        orderId);
  }

  public int restoreStock(int quantity, String skuId) {
    return db.update("UPDATE product SET available=available+? WHERE id=?", quantity, skuId);
  }

  public OrderSnapshot lockExpiringOrder(UUID id) {
    return db.queryForObject(
        "SELECT id,status,total,currency,expires_at AS \"expiresAt\",created_at AS"
            + " \"createdAt\",stock_released AS \"stockReleased\" FROM customer_order WHERE id=?"
            + " FOR UPDATE",
        (rs, n) ->
            new OrderSnapshot(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getLong("total"),
                rs.getString("currency"),
                rs.getTimestamp("expiresAt").toInstant(),
                rs.getTimestamp("createdAt").toInstant(),
                rs.getBoolean("stockReleased")),
        id);
  }

  public int markExpired(UUID id) {
    return db.update("UPDATE customer_order SET status='EXPIRED' WHERE id=?", id);
  }

  public List<UUID> findExpiringOrderIds() {
    return db.queryForList(
        "SELECT id FROM customer_order WHERE status='AWAITING_PAYMENT' AND expires_at<now()",
        UUID.class);
  }

  public List<UUID> findAttemptOrder(UUID attemptId) {
    return db.query(
        "SELECT order_id FROM payment_attempt WHERE id=?",
        (rs, n) -> rs.getObject("order_id", UUID.class),
        attemptId);
  }

  public OrderSnapshot lockPaymentOrder(UUID orderId) {
    return db.queryForObject(
        "SELECT id,status,total,currency,expires_at AS \"expiresAt\",created_at AS"
            + " \"createdAt\",stock_released AS \"stockReleased\" FROM customer_order WHERE id=?"
            + " FOR UPDATE",
        (rs, n) ->
            new OrderSnapshot(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getLong("total"),
                rs.getString("currency"),
                rs.getTimestamp("expiresAt").toInstant(),
                rs.getTimestamp("createdAt").toInstant(),
                rs.getBoolean("stockReleased")),
        orderId);
  }

  public int insertWebhookEvent(UUID eventId, String bodyHash) {
    return db.update(
        "INSERT INTO webhook_event(id,payload_hash) VALUES (?,?) ON CONFLICT DO NOTHING",
        eventId,
        bodyHash);
  }

  public String findWebhookHash(UUID eventId) {
    return db.queryForObject(
        "SELECT payload_hash FROM webhook_event WHERE id=?", String.class, eventId);
  }

  public String findAttemptStatus(UUID attemptId) {
    return db.queryForObject(
        "SELECT status FROM payment_attempt WHERE id=?", String.class, attemptId);
  }

  public int markExpiredAfterCallback(UUID orderId) {
    return db.update("UPDATE customer_order SET status='EXPIRED' WHERE id=?", orderId);
  }

  public int updateAttemptStatus(String status, UUID attemptId) {
    return db.update("UPDATE payment_attempt SET status=? WHERE id=?", status, attemptId);
  }

  public int confirmOrder(UUID orderId) {
    return db.update("UPDATE customer_order SET status='CONFIRMED' WHERE id=?", orderId);
  }

  public int reviewAttempt(UUID attemptId) {
    return db.update("UPDATE payment_attempt SET status='REVIEW' WHERE id=?", attemptId);
  }

  public int reviewOrder(UUID orderId) {
    return db.update("UPDATE customer_order SET status='PAYMENT_REVIEW' WHERE id=?", orderId);
  }

  public List<UUID> findMockAttemptOrder(UUID attemptId) {
    return db.query(
        "SELECT order_id FROM payment_attempt WHERE id=?",
        (rs, n) -> rs.getObject("order_id", UUID.class),
        attemptId);
  }

  public List<String> findDeliveryPayload(UUID attemptId) {
    return db.query(
        "SELECT payload FROM mock_delivery WHERE attempt_id=?",
        (rs, n) -> rs.getString("payload"),
        attemptId);
  }

  public int requeueDelivery(UUID attemptId) {
    return db.update(
        "UPDATE mock_delivery SET delivered=false,next_delivery=now() WHERE attempt_id=?",
        attemptId);
  }

  public String findMockAttemptStatus(UUID attemptId) {
    return db.queryForObject(
        "SELECT status FROM payment_attempt WHERE id=?", String.class, attemptId);
  }

  public int insertDelivery(UUID attemptId, UUID eventId, String payload) {
    return db.update(
        "INSERT INTO mock_delivery(attempt_id,event_id,payload) VALUES (?,?,?)",
        attemptId,
        eventId,
        payload);
  }

  public String findPaymentMethod(UUID attemptId) {
    return db.queryForObject(
        "SELECT method FROM payment_attempt WHERE id=?", String.class, attemptId);
  }

  public int setPaymentMethod(String method, UUID attemptId) {
    return db.update("UPDATE payment_attempt SET method=? WHERE id=?", method, attemptId);
  }

  public List<String> lockReviewOrder(UUID orderId) {
    return db.query(
        "SELECT status FROM customer_order WHERE id=? FOR UPDATE",
        (rs, n) -> rs.getString("status"),
        orderId);
  }

  public int insertReviewResolution(UUID orderId, String operator, String reason) {
    return db.update(
        "INSERT INTO review_resolution(order_id,operator_id,reason,disposition) VALUES"
            + " (?,?,?,'MOCK_REFUND_AND_CANCEL')",
        orderId,
        operator,
        reason);
  }

  public int cancelOrder(UUID orderId) {
    return db.update("UPDATE customer_order SET status='CANCELLED' WHERE id=?", orderId);
  }

  public int stopOrderDeliveries(UUID orderId) {
    return db.update(
        "UPDATE mock_delivery SET exhausted=true,last_error='REVIEW_RESOLVED' WHERE attempt_id IN"
            + " (SELECT id FROM payment_attempt WHERE order_id=?) AND delivered=false",
        orderId);
  }
}
