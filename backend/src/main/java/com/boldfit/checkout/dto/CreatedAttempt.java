package com.boldfit.checkout.dto;

import jakarta.validation.constraints.*;
import java.util.*;

public record CreatedAttempt(UUID id, UUID orderId, String status) {}
