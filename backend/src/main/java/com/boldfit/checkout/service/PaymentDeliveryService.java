package com.boldfit.checkout.service;

import java.util.UUID;

public interface PaymentDeliveryService {
  void expire();

  void deliver();

  void recordFailure(UUID id, String reason);
}
