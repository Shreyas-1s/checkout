package com.boldfit.checkout.domain;

import java.util.UUID;

public record AttemptState(UUID id, String status) {}
