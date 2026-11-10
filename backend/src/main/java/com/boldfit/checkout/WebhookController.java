package com.boldfit.checkout;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@RestController
public class WebhookController {
    private final CheckoutService service;
    private final ObjectMapper json;
    private final String secret;
    public WebhookController(CheckoutService service,ObjectMapper json,@Value("${app.webhook-secret}") String secret) { this.service=service;this.json=json;this.secret=secret; }
    static String sign(String secret,String timestamp,byte[] body) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            mac.update((timestamp+".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    @PostMapping("/webhooks/payment")
    public Map<String,String> receive(@RequestBody byte[] body,@RequestHeader(value="X-Payment-Timestamp",required=false) String timestamp,@RequestHeader(value="X-Payment-Signature",required=false) String signature) {
        try {
            long supplied=Long.parseLong(timestamp), now=Instant.now().getEpochSecond();
            if(supplied<now-300 || supplied>now+300 || signature==null || signature.length()!=64 ||
                !MessageDigest.isEqual(HexFormat.of().parseHex(signature),HexFormat.of().parseHex(sign(secret,timestamp,body)))) throw new IllegalArgumentException();
        } catch(Exception ex) { throw new ApiException(401,"INVALID_SIGNATURE","Invalid or expired payment signature."); }
        CheckoutService.PaymentEvent event;
        try {
            event=json.readValue(body,CheckoutService.PaymentEvent.class);
            if(event.eventId()==null || event.attemptId()==null || event.orderId()==null || event.amount()<0 ||
                event.currency()==null || !Set.of("SUCCEEDED","FAILED").contains(event.outcome())) throw new IllegalArgumentException();
        } catch(Exception ex) { throw new ApiException(400,"INVALID_EVENT","Invalid payment event."); }
        service.receive(event,new String(body,StandardCharsets.UTF_8));
        return Map.of("status","received");
    }
}
