package com.boldfit.checkout.dto;

import jakarta.validation.constraints.*;
import java.util.*;

public record Product(
    String id, String name, String category, String description, long price, int available) {}
