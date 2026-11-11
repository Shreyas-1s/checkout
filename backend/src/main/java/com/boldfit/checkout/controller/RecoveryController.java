package com.boldfit.checkout.controller;

import com.boldfit.checkout.dto.Attention;
import com.boldfit.checkout.dto.PaymentStatus;
import com.boldfit.checkout.dto.RetryPaymentAction;
import com.boldfit.checkout.dto.ReviewResolutionRequest;
import com.boldfit.checkout.exception.ApiException;
import com.boldfit.checkout.service.RecoveryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/** HTTP adapter for local mock recovery; it does not execute real refunds. */
@RestController
@RequestMapping("/api/ops")
public class RecoveryController {
  private final RecoveryService service;
  private final boolean enabled;

  public RecoveryController(
      RecoveryService service, @Value("${app.demo-enabled}") boolean enabled) {
    this.service = service;
    this.enabled = enabled;
  }

  @GetMapping("attention")
  public List<Attention> attention() {
    requireDemo();
    return service.attention();
  }

  @PostMapping("agent-actions/retry-failed-payment")
  public PaymentStatus retryFailedPayment(
      HttpServletRequest request, @Valid @RequestBody RetryPaymentAction input) {
    requireDemo();
    return service.retryFailedPayment((String) request.getAttribute("customer"), input);
  }

  @PostMapping("orders/{id}/resolve-review")
  public PaymentStatus resolve(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody ReviewResolutionRequest input) {
    requireDemo();
    service.resolveReview(id, (String) request.getAttribute("customer"), input.reason());
    return new PaymentStatus("CANCELLED");
  }

  private void requireDemo() {
    if (!enabled) throw new ApiException(404, "NOT_FOUND", "Not found.");
  }
}
