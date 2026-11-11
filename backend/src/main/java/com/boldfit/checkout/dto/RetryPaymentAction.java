package com.boldfit.checkout.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public record RetryPaymentAction(
    @NotNull UUID actionId,
    @NotBlank String action,
    @NotNull UUID orderId,
    @NotNull UUID paymentAttemptId,
    @NotBlank @Size(min = 10, max = 500) String reason,
    @NotBlank String approvalStatus,
    @NotBlank @Size(max = 200) String approvedBy,
    @NotNull Instant approvedAt) {}
