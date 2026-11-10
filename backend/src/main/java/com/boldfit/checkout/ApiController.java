package com.boldfit.checkout;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;

@RestController
public class ApiController {
    private final CheckoutService service;
    private final boolean demoEnabled;
    private final ObjectMapper json;
    public ApiController(CheckoutService service,ObjectMapper json,@Value("${app.demo-enabled}") boolean demoEnabled) { this.service=service; this.json=json; this.demoEnabled=demoEnabled; }
    public record Item(@NotBlank String skuId,@Min(1) @Max(10) int quantity) {}
    public record Checkout(@NotEmpty @Size(max=20) List<@NotNull @Valid Item> items) {}
    public record Simulation(@NotNull @Pattern(regexp="SUCCEEDED|FAILED|PARTIAL") String outcome) {}
    @GetMapping("/api/products") public List<ApiDtos.Product> products() { return service.products().stream().map(row -> json.convertValue(row,ApiDtos.Product.class)).toList(); }
    @PostMapping("/api/orders") public ResponseEntity<ApiDtos.Order> checkout(HttpServletRequest request,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Checkout input) throws Exception {
        String body=service.checkout(customer(request),key,input.items());
        ApiDtos.Order response=json.readValue(body,ApiDtos.Order.class);
        return ResponseEntity.created(URI.create("/api/orders/"+response.id())).body(response);
    }
    @GetMapping("/api/orders/{id}") public ApiDtos.Order order(HttpServletRequest request,@PathVariable UUID id) { return json.convertValue(service.order(customer(request),id),ApiDtos.Order.class); }
    @PostMapping("/api/orders/{id}/payment-attempts") public ResponseEntity<ApiDtos.CreatedAttempt> attempt(HttpServletRequest request,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key) throws Exception {
        return ResponseEntity.status(201).body(json.readValue(service.attempt(customer(request),id,key),ApiDtos.CreatedAttempt.class));
    }
    @PostMapping("/api/demo/payments/{id}/resolve") public ResponseEntity<ApiDtos.Message> simulate(HttpServletRequest request,@PathVariable UUID id,@Valid @RequestBody Simulation input) {
        if(!demoEnabled) throw new ApiException(404,"NOT_FOUND","Not found.");
        service.simulate(customer(request),id,input.outcome());
        return ResponseEntity.accepted().body(new ApiDtos.Message("Mock provider queued its signed callback."));
    }
    public record Payment(@NotNull @Pattern(regexp="UPI|CARD|NETBANKING") String method) {}
    @PostMapping("/api/payments/{id}/pay") public ResponseEntity<ApiDtos.PaymentStatus> pay(HttpServletRequest request,@PathVariable UUID id,@Valid @RequestBody Payment input) {
        if(!demoEnabled) throw new ApiException(404,"NOT_FOUND","Not found.");
        service.pay(customer(request),id,input.method());
        return ResponseEntity.accepted().body(new ApiDtos.PaymentStatus("PROCESSING"));
    }
    private String customer(HttpServletRequest request) { return (String)request.getAttribute("customer"); }
}
