package com.boldfit.checkout.dto;

import jakarta.validation.constraints.*;
import java.util.*;

public record ReviewResolutionRequest(@NotBlank @Size(max = 500) String reason) {}
