package com.boldfit.checkout.domain;

import java.time.Instant;
import java.util.UUID;

public record ApprovedAction(
    String action,
    UUID orderId,
    UUID attemptId,
    String reason,
    String approvalStatus,
    String approvedBy,
    Instant approvedAt) {}
