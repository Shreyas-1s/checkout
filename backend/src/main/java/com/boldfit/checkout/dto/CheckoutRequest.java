package com.boldfit.checkout.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

public record CheckoutRequest(@NotEmpty @Size(max = 20) List<@NotNull @Valid CartItem> items) {}
