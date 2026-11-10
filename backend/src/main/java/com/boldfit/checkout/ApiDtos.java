package com.boldfit.checkout;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public JSON contracts are independent of JDBC row maps. Money is in paise. */
public final class ApiDtos {
    private ApiDtos() {}
    public record Product(String id, String name, String category, String description, long price, int available) {}
    public record OrderLine(String skuId, String name, int quantity, long unitPrice) {}
    public record Attempt(UUID id, String status, Instant createdAt, String method, boolean submitted) {}
    public record Order(UUID id, String status, long total, String currency, Instant expiresAt,
                        Instant createdAt, boolean stockReleased, List<OrderLine> lines, List<Attempt> attempts) {}
    public record CreatedAttempt(UUID id, UUID orderId, String status) {}
    public record PaymentStatus(String status) {}
    public record Message(String message) {}
    public record Error(String code, String message) {}
}
