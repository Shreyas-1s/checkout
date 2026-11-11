package com.boldfit.checkout.service.impl;

import com.boldfit.checkout.repository.DeliveryRepository;
import com.boldfit.checkout.security.WebhookSignature;
import com.boldfit.checkout.service.CheckoutService;
import com.boldfit.checkout.service.PaymentDeliveryService;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PaymentDeliveryServiceImpl implements PaymentDeliveryService {
  private static final Logger log = LoggerFactory.getLogger(PaymentDeliveryServiceImpl.class);
  private final CheckoutService service;
  private final DeliveryRepository repository;
  private final String secret, callback;
  private final boolean enabled;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  public PaymentDeliveryServiceImpl(
      CheckoutService service,
      DeliveryRepository repository,
      @Value("${app.webhook-secret}") String secret,
      @Value("${app.callback-url}") String callback,
      @Value("${app.demo-enabled}") boolean enabled) {
    this.service = service;
    this.repository = repository;
    this.secret = secret;
    this.callback = callback;
    this.enabled = enabled;
  }

  public void expire() {
    for (var id : service.expiring()) {
      try {
        service.expire(id);
      } catch (Exception ex) {
        log.error(
            "Reservation expiry failed orderId={} type={}", id, ex.getClass().getSimpleName());
      }
    }
  }

  /** Delivery happens outside a DB transaction. Repeated delivery is intentional and safe. */
  public void deliver() {
    if (!enabled) return;
    for (var row : repository.findDueDeliveries()) {
      try {
        byte[] body = row.payload().getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        var request =
            HttpRequest.newBuilder(URI.create(callback))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .header("X-Payment-Timestamp", timestamp)
                .header("X-Payment-Signature", WebhookSignature.sign(secret, timestamp, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 == 2) repository.markDelivered(row.attemptId());
        else recordFailure(row.attemptId(), "HTTP_" + response.statusCode());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception ex) {
        log.warn("Mock delivery failed for attempt {}", row.attemptId());
        recordFailure(row.attemptId(), "DELIVERY_UNAVAILABLE");
      }
    }
  }

  public void recordFailure(UUID id, String reason) {
    // Five failed deliveries, exponential delay (5, 10, 20, 40 seconds), then
    // stop automatic delivery. The persisted row and error log require operator action.
    var rows = repository.recordFailure(reason, id);
    if (!rows.isEmpty() && Boolean.TRUE.equals(rows.get(0)))
      log.error("CALLBACK_EXHAUSTED attemptId={} reason={} operatorAction=required", id, reason);
  }
}
