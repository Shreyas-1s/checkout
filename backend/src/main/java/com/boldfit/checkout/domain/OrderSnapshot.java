package com.boldfit.checkout.domain;

import java.time.Instant;
import java.util.UUID;

public record OrderSnapshot(
    UUID id,
    String status,
    long total,
    String currency,
    Instant expiresAt,
    Instant createdAt,
    boolean stockReleased) {
  public OrderSnapshot withStatus(String next) {
    return new OrderSnapshot(id, next, total, currency, expiresAt, createdAt, stockReleased);
  }
}
