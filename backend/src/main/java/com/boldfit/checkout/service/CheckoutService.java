package com.boldfit.checkout.service;

import com.boldfit.checkout.dto.CartItem;
import com.boldfit.checkout.dto.CreatedAttempt;
import com.boldfit.checkout.dto.Order;
import com.boldfit.checkout.dto.PaymentEvent;
import com.boldfit.checkout.dto.Product;
import java.util.List;
import java.util.UUID;

/** Application use cases; callers do not depend on persistence or JSON serialization. */
public interface CheckoutService {
  List<Product> products();

  Order checkout(String customer, String key, List<CartItem> items);

  Order order(String customer, UUID id);

  CreatedAttempt attempt(String customer, UUID orderId, String key);

  void expire(UUID id);

  List<UUID> expiring();

  void receive(PaymentEvent event, String payload);

  void simulate(String customer, UUID attemptId, String outcome);

  void pay(String customer, UUID attemptId, String method);

  void resolveReview(UUID orderId, String operator, String reason);
}
