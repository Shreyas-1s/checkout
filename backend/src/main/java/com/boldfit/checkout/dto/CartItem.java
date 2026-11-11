package com.boldfit.checkout.dto;

import jakarta.validation.constraints.*;
import java.util.*;

public record CartItem(@NotBlank String skuId, @Min(1) @Max(10) int quantity) {}
