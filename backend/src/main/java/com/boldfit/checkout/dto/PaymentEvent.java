package com.boldfit.checkout.dto;

import jakarta.validation.constraints.*;
import java.util.*;

public record PaymentEvent(
    UUID eventId, UUID attemptId, UUID orderId, String outcome, long amount, String currency) {}
