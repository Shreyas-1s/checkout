package com.boldfit.checkout.service.impl;

import com.boldfit.checkout.dto.Attention;
import com.boldfit.checkout.dto.PaymentStatus;
import com.boldfit.checkout.dto.RetryPaymentAction;
import com.boldfit.checkout.exception.ApiException;
import com.boldfit.checkout.repository.RecoveryRepository;
import com.boldfit.checkout.service.CheckoutService;
import com.boldfit.checkout.service.RecoveryService;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecoveryServiceImpl implements RecoveryService {
  private final RecoveryRepository repository;
  private final CheckoutService checkout;

  public RecoveryServiceImpl(RecoveryRepository repository, CheckoutService checkout) {
    this.repository = repository;
    this.checkout = checkout;
  }

  public List<Attention> attention() {
    return repository.findAttention();
  }

  @Transactional
  public PaymentStatus retryFailedPayment(String operator, RetryPaymentAction input) {
    if (!input.action().equals("RETRY_FAILED_PAYMENT")
        || !input.approvalStatus().equals("APPROVED"))
      throw new ApiException(
          400,
          "HUMAN_APPROVAL_REQUIRED",
          "A human must approve this retry action before it can execute.");
    Instant now = Instant.now();
    // PostgreSQL stores microseconds. Normalize before binding so JDBC cannot round
    // nanoseconds differently from the comparison used for an identical replay.
    Instant approvedAt = input.approvedAt().truncatedTo(ChronoUnit.MICROS);
    if (input.approvedAt().isAfter(now.plusSeconds(300))
        || input.approvedAt().isBefore(now.minus(Duration.ofHours(24))))
      throw new ApiException(
          400, "STALE_APPROVAL", "Human approval must be recent and cannot be future-dated.");
    // Same lock order as webhook processing and review resolution.
    var orderRows = repository.lockAttemptOrder(input.paymentAttemptId());
    if (orderRows.isEmpty() || !input.orderId().equals(orderRows.get(0)))
      throw new ApiException(
          400, "PAYMENT_ORDER_MISMATCH", "Payment attempt does not belong to this order.");
    var prior = repository.findAction(input.actionId());
    if (!prior.isEmpty()) {
      var saved = prior.get(0);
      boolean same =
          input.action().equals(saved.action())
              && input.orderId().equals(saved.orderId())
              && input.paymentAttemptId().equals(saved.attemptId())
              && input.reason().equals(saved.reason())
              && input.approvalStatus().equals(saved.approvalStatus())
              && input.approvedBy().equals(saved.approvedBy())
              && approvedAt.equals(saved.approvedAt());
      if (!same)
        throw new ApiException(
            409, "ACTION_ID_REUSED", "Action ID was already used for a different request.");
      return new PaymentStatus("QUEUED");
    }
    int updated = repository.requeueExhaustedDelivery(input.paymentAttemptId());
    if (updated == 0)
      throw new ApiException(
          409,
          "NOT_REDELIVERABLE",
          "Callback is missing, already delivered, or the order was cancelled.");
    repository.insertAction(
        input.actionId(),
        input.action(),
        input.orderId(),
        input.paymentAttemptId(),
        input.reason(),
        input.approvalStatus(),
        input.approvedBy(),
        java.sql.Timestamp.from(approvedAt),
        operator);
    return new PaymentStatus("QUEUED");
  }

  public void resolveReview(UUID id, String operator, String reason) {
    checkout.resolveReview(id, operator, reason);
  }
}
