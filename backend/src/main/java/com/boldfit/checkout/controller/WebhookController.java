package com.boldfit.checkout.controller;

import com.boldfit.checkout.dto.PaymentEvent;
import com.boldfit.checkout.dto.PaymentStatus;
import com.boldfit.checkout.exception.ApiException;
import com.boldfit.checkout.security.WebhookSignature;
import com.boldfit.checkout.service.CheckoutService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
public class WebhookController {
  private final CheckoutService service;
  private final ObjectMapper json;
  private final String secret;

  public WebhookController(
      CheckoutService service, ObjectMapper json, @Value("${app.webhook-secret}") String secret) {
    this.service = service;
    this.json = json;
    this.secret = secret;
  }

  @PostMapping("/webhooks/payment")
  public PaymentStatus receive(
      @RequestBody byte[] body,
      @RequestHeader(value = "X-Payment-Timestamp", required = false) String timestamp,
      @RequestHeader(value = "X-Payment-Signature", required = false) String signature) {
    try {
      long supplied = Long.parseLong(timestamp), now = Instant.now().getEpochSecond();
      if (supplied < now - 300
          || supplied > now + 300
          || signature == null
          || signature.length() != 64
          || !MessageDigest.isEqual(
              HexFormat.of().parseHex(signature),
              HexFormat.of().parseHex(WebhookSignature.sign(secret, timestamp, body))))
        throw new IllegalArgumentException();
    } catch (Exception ex) {
      throw new ApiException(401, "INVALID_SIGNATURE", "Invalid or expired payment signature.");
    }
    PaymentEvent event;
    try {
      event = json.readValue(body, PaymentEvent.class);
      if (event.eventId() == null
          || event.attemptId() == null
          || event.orderId() == null
          || event.amount() < 0
          || event.currency() == null
          || !Set.of("SUCCEEDED", "FAILED").contains(event.outcome()))
        throw new IllegalArgumentException();
    } catch (Exception ex) {
      throw new ApiException(400, "INVALID_EVENT", "Invalid payment event.");
    }
    service.receive(event, new String(body, StandardCharsets.UTF_8));
    return new PaymentStatus("received");
  }
}
