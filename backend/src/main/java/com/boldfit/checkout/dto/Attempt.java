package com.boldfit.checkout.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public record Attempt(
    UUID id,
    String status,
    Instant createdAt,
    String method,
    boolean submitted,
    String deliveryState) {}
