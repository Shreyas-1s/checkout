package com.boldfit.checkout.dto;

import jakarta.validation.constraints.*;
import java.util.*;

public record PaymentRequest(@NotNull @Pattern(regexp = "UPI|CARD|NETBANKING") String method) {}
