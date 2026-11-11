package com.boldfit.checkout.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public record Order(
    UUID id,
    String status,
    long total,
    String currency,
    Instant expiresAt,
    Instant createdAt,
    boolean stockReleased,
    List<OrderLine> lines,
    List<Attempt> attempts) {}
