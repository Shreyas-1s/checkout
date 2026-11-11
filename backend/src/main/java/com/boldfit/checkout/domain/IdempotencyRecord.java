package com.boldfit.checkout.domain;


public record IdempotencyRecord(String fingerprint, String response) {}
