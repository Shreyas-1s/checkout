package com.boldfit.checkout.dto;

import jakarta.validation.constraints.*;
import java.util.*;

public record SimulationRequest(
    @NotNull @Pattern(regexp = "SUCCEEDED|FAILED|PARTIAL") String outcome) {}
