package com.boldfit.checkout.controller;

import com.boldfit.checkout.dto.CheckoutRequest;
import com.boldfit.checkout.dto.CreatedAttempt;
import com.boldfit.checkout.dto.Message;
import com.boldfit.checkout.dto.Order;
import com.boldfit.checkout.dto.PaymentRequest;
import com.boldfit.checkout.dto.PaymentStatus;
import com.boldfit.checkout.dto.Product;
import com.boldfit.checkout.dto.SimulationRequest;
import com.boldfit.checkout.exception.ApiException;
import com.boldfit.checkout.service.CheckoutService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class ApiController {
  private final CheckoutService service;
  private final boolean demoEnabled;

  public ApiController(CheckoutService service, @Value("${app.demo-enabled}") boolean demoEnabled) {
    this.service = service;
    this.demoEnabled = demoEnabled;
  }

  @GetMapping("/api/products")
  public List<Product> products() {
    return service.products();
  }

  @PostMapping("/api/orders")
  public ResponseEntity<Order> checkout(
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody CheckoutRequest input) {
    Order response = service.checkout(customer(request), key, input.items());
    return ResponseEntity.created(URI.create("/api/orders/" + response.id())).body(response);
  }

  @GetMapping("/api/orders/{id}")
  public Order order(HttpServletRequest request, @PathVariable UUID id) {
    return service.order(customer(request), id);
  }

  @PostMapping("/api/orders/{id}/payment-attempts")
  public ResponseEntity<CreatedAttempt> attempt(
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key) {
    return ResponseEntity.status(201).body(service.attempt(customer(request), id, key));
  }

  @PostMapping("/api/demo/payments/{id}/resolve")
  public ResponseEntity<Message> simulate(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody SimulationRequest input) {
    if (!demoEnabled) throw new ApiException(404, "NOT_FOUND", "Not found.");
    service.simulate(customer(request), id, input.outcome());
    return ResponseEntity.accepted().body(new Message("Mock provider queued its signed callback."));
  }

  @PostMapping("/api/payments/{id}/pay")
  public ResponseEntity<PaymentStatus> pay(
      HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody PaymentRequest input) {
    if (!demoEnabled) throw new ApiException(404, "NOT_FOUND", "Not found.");
    service.pay(customer(request), id, input.method());
    return ResponseEntity.accepted().body(new PaymentStatus("PROCESSING"));
  }

  private String customer(HttpServletRequest request) {
    return (String) request.getAttribute("customer");
  }
}
