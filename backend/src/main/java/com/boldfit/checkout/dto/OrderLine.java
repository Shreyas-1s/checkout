package com.boldfit.checkout.dto;

import jakarta.validation.constraints.*;
import java.util.*;

public record OrderLine(String skuId, String name, int quantity, long unitPrice) {}
