package com.boldfit.checkout.dto;

import jakarta.validation.constraints.*;
import java.util.*;

public record Attention(
    UUID orderId,
    String status,
    UUID attemptId,
    boolean exhausted,
    int retries,
    String lastError) {}
