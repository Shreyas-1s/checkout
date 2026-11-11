package com.boldfit.checkout.service.impl;

import com.boldfit.checkout.domain.*;
import com.boldfit.checkout.dto.CartItem;
import com.boldfit.checkout.dto.CreatedAttempt;
import com.boldfit.checkout.dto.Order;
import com.boldfit.checkout.dto.OrderLine;
import com.boldfit.checkout.dto.PaymentEvent;
import com.boldfit.checkout.dto.Product;
import com.boldfit.checkout.exception.ApiException;
import com.boldfit.checkout.repository.CheckoutRepository;
import com.boldfit.checkout.service.CheckoutService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckoutServiceImpl implements CheckoutService {
  private final CheckoutRepository repository;
  private final ObjectMapper json;

  public CheckoutServiceImpl(CheckoutRepository repository, ObjectMapper json) {
    this.repository = repository;
    this.json = json;
  }

  public List<Product> products() {
    return repository.findProducts();
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  /**
   * INSERT ON CONFLICT waits for the concurrent owner; both response and effects share this
   * transaction.
   */
  private <T> T idempotent(
      String customer,
      String operation,
      String key,
      Object input,
      Class<T> responseType,
      Supplier<T> action) {
    if (key == null || !key.matches("[A-Za-z0-9_-]{8,100}"))
      throw new ApiException(400, "INVALID_KEY", "Use an 8–100 character idempotency key.");
    String fingerprint = hash(encode(input));
    int inserted = repository.claimIdempotency(customer, operation, key, fingerprint);
    IdempotencyRecord record = repository.lockIdempotency(customer, operation, key);
    if (!fingerprint.equals(record.fingerprint()))
      throw new ApiException(409, "KEY_REUSED", "This key was used for a different request.");
    if (inserted == 0) {
      try {
        return json.readValue(record.response(), responseType);
      } catch (JsonProcessingException ex) {
        throw new IllegalStateException("Stored response is invalid", ex);
      }
    }
    T result = action.get();
    String response = encode(result);
    repository.saveIdempotentResponse(response, customer, operation, key);
    return result;
  }

  @Transactional
  public Order checkout(String customer, String key, List<CartItem> items) {
    TreeMap<String, Integer> quantities = new TreeMap<>();
    items.forEach(item -> quantities.merge(item.skuId(), item.quantity(), Integer::sum));
    if (quantities.values().stream().anyMatch(q -> q > 10))
      throw new ApiException(400, "INVALID_QUANTITY", "Maximum ten units per product.");
    return idempotent(
        customer,
        "checkout",
        key,
        quantities,
        Order.class,
        () -> {
          UUID id = UUID.randomUUID();
          List<OrderLine> lines = new ArrayList<>();
          long total = 0;
          // Consistent SKU order avoids inverted lock acquisition for multi-item carts.
          for (var entry : quantities.entrySet()) {
            int updated =
                repository.reserveStock(entry.getValue(), entry.getKey(), entry.getValue());
            if (updated == 0)
              throw new ApiException(
                  409, "OUT_OF_STOCK", "An item is no longer available. Refresh your bag.");
            var product = repository.findProduct(entry.getKey());
            long price = product.price();
            total = Math.addExact(total, Math.multiplyExact(price, entry.getValue()));
            lines.add(new OrderLine(entry.getKey(), product.name(), entry.getValue(), price));
          }
          repository.insertOrder(id, customer, total);
          for (var line : lines)
            repository.insertOrderLine(
                id, line.skuId(), line.name(), line.quantity(), line.unitPrice());
          return order(customer, id);
        });
  }

  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public Order order(String customer, UUID id) {
    OrderSnapshot order = owned(customer, id, false);
    return new Order(
        order.id(),
        order.status(),
        order.total(),
        order.currency(),
        order.expiresAt(),
        order.createdAt(),
        order.stockReleased(),
        repository.findOrderLines(id),
        repository.findAttempts(id));
  }

  private OrderSnapshot owned(String customer, UUID id, boolean lock) {
    var rows = repository.findOwnedOrder(id, customer, lock);
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Order not found.");
    return rows.get(0);
  }

  @Transactional
  public CreatedAttempt attempt(String customer, UUID orderId, String key) {
    // Ownership is checked even for a replay.
    owned(customer, orderId, false);
    return idempotent(
        customer,
        "attempt:" + orderId,
        key,
        Map.of("orderId", orderId),
        CreatedAttempt.class,
        () -> {
          var order = owned(customer, orderId, true);
          if (!order.status().equals("AWAITING_PAYMENT") || expired(order))
            throw new ApiException(409, "ORDER_NOT_PAYABLE", "This order is no longer payable.");
          var attempts = repository.findAttemptStates(orderId);
          if (attempts.stream().anyMatch(a -> !a.status().equals("FAILED")))
            throw new ApiException(
                409, "ATTEMPT_IN_PROGRESS", "Resolve the current payment before starting another.");
          UUID id = UUID.randomUUID();
          repository.insertAttempt(id, orderId);
          return new CreatedAttempt(id, orderId, "PENDING");
        });
  }

  private boolean expired(OrderSnapshot order) {
    return order.expiresAt().isBefore(Instant.now());
  }

  private void release(UUID orderId) {
    if (repository.markStockReleased(orderId) == 1) {
      for (var line : repository.findReservedLines(orderId))
        repository.restoreStock(line.quantity(), line.skuId());
    }
  }

  @Transactional
  public void expire(UUID id) {
    var order = repository.lockExpiringOrder(id);
    if (order.status().equals("AWAITING_PAYMENT") && expired(order)) {
      release(id);
      repository.markExpired(id);
    }
  }

  public List<UUID> expiring() {
    return repository.findExpiringOrderIds();
  }

  @Transactional
  public void receive(PaymentEvent event, String payload) {
    var attempts = repository.findAttemptOrder(event.attemptId());
    if (attempts.isEmpty() || !attempts.get(0).equals(event.orderId()))
      throw new ApiException(400, "UNKNOWN_PAYMENT", "Payment does not match this order.");
    var order = repository.lockPaymentOrder(event.orderId());
    String bodyHash = hash(payload);
    int inserted = repository.insertWebhookEvent(event.eventId(), bodyHash);
    if (inserted == 0) {
      if (!bodyHash.equals(repository.findWebhookHash(event.eventId())))
        throw new ApiException(409, "EVENT_REUSED", "Event ID has a different payload.");
      return;
    }
    // An operator has already recorded the mock refund/cancellation. Keep late
    // callbacks auditable, but never resurrect or fulfill this resolved order.
    if (order.status().equals("CANCELLED")) return;
    String state = repository.findAttemptStatus(event.attemptId());
    boolean success = event.outcome().equals("SUCCEEDED");
    boolean exact = event.amount() == order.total() && event.currency().equals(order.currency());
    if (state.equals("SUCCEEDED") && success && exact
        || state.equals("FAILED") && !success && exact) return;
    // A conflicting terminal event or amount mismatch is visible for reconciliation.
    if (!state.equals("PENDING") || !exact) {
      review(event);
      return;
    }
    if (order.status().equals("AWAITING_PAYMENT") && expired(order)) {
      release(event.orderId());
      repository.markExpiredAfterCallback(event.orderId());
      order = order.withStatus("EXPIRED");
    }
    if (success && !order.status().equals("AWAITING_PAYMENT")) {
      review(event);
      return;
    }
    repository.updateAttemptStatus(success ? "SUCCEEDED" : "FAILED", event.attemptId());
    if (success) repository.confirmOrder(event.orderId());
  }

  private void review(PaymentEvent event) {
    repository.reviewAttempt(event.attemptId());
    repository.reviewOrder(event.orderId());
  }

  @Transactional
  public void simulate(String customer, UUID attemptId, String outcome) {
    var rows = repository.findMockAttemptOrder(attemptId);
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Payment not found.");
    UUID orderId = rows.get(0);
    var order = owned(customer, orderId, true);
    if (order.status().equals("CANCELLED"))
      throw new ApiException(409, "ORDER_NOT_PAYABLE", "This order was cancelled.");
    var previous = repository.findDeliveryPayload(attemptId);
    if (!previous.isEmpty()) {
      try {
        var existing = json.readValue(previous.get(0), PaymentEvent.class);
        String original =
            existing.outcome().equals("FAILED")
                ? "FAILED"
                : existing.amount() == order.total() ? "SUCCEEDED" : "PARTIAL";
        if (!original.equals(outcome))
          throw new ApiException(
              409, "MOCK_ALREADY_SETTLED", "This mock payment already has a fixed outcome.");
        repository.requeueDelivery(attemptId);
        return;
      } catch (JsonProcessingException ex) {
        throw new IllegalStateException(ex);
      }
    }
    if (!order.status().equals("AWAITING_PAYMENT") || expired(order))
      throw new ApiException(
          409,
          "ORDER_NOT_PAYABLE",
          "Your reservation has expired or the order is no longer payable.");
    String status = repository.findMockAttemptStatus(attemptId);
    if (!status.equals("PENDING"))
      throw new ApiException(409, "NOT_PENDING", "Payment is no longer pending.");
    long total = order.total();
    UUID eventId = UUID.randomUUID();
    var event =
        new PaymentEvent(
            eventId,
            attemptId,
            orderId,
            outcome.equals("FAILED") ? "FAILED" : "SUCCEEDED",
            outcome.equals("PARTIAL") ? total / 2 : total,
            "INR");
    repository.insertDelivery(attemptId, eventId, encode(event));
  }

  @Transactional
  public void pay(String customer, UUID attemptId, String method) {
    // simulate acquires the order lock and durably queues exactly one fixed outcome.
    // Payment credentials are intentionally never accepted by this mock provider.
    simulate(customer, attemptId, "SUCCEEDED");
    String previous = repository.findPaymentMethod(attemptId);
    if (previous != null && !previous.equals(method))
      throw new ApiException(
          409,
          "METHOD_ALREADY_SELECTED",
          "This payment was already submitted with a different method.");
    repository.setPaymentMethod(method, attemptId);
  }

  @Transactional
  public void resolveReview(UUID orderId, String operator, String reason) {
    var rows = repository.lockReviewOrder(orderId);
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Order not found.");
    if (rows.get(0).equals("CANCELLED")) return;
    if (!rows.get(0).equals("PAYMENT_REVIEW"))
      throw new ApiException(
          409, "NOT_IN_REVIEW", "Only review orders can be resolved through this mock operation.");
    release(orderId);
    repository.insertReviewResolution(orderId, operator, reason);
    repository.cancelOrder(orderId);
    repository.stopOrderDeliveries(orderId);
  }
}
