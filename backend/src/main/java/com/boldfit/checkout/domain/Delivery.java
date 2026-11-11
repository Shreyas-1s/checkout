package com.boldfit.checkout.domain;

import java.util.UUID;

public record Delivery(UUID attemptId, String payload) {}
