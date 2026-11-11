package com.boldfit.checkout.dto;

import jakarta.validation.constraints.*;
import java.util.*;

public record ApiErrorResponse(
    String code, String message, String requestId, java.util.Map<String, String> fields) {}
