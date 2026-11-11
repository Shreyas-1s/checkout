package com.boldfit.checkout.service;

import com.boldfit.checkout.dto.Attention;
import com.boldfit.checkout.dto.PaymentStatus;
import com.boldfit.checkout.dto.RetryPaymentAction;
import java.util.List;
import java.util.UUID;

public interface RecoveryService {
  List<Attention> attention();

  PaymentStatus retryFailedPayment(String operator, RetryPaymentAction input);

  void resolveReview(UUID id, String operator, String reason);
}
