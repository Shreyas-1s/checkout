package com.boldfit.checkout.security;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Shared signing primitive for the mock provider and webhook authentication. */
public final class WebhookSignature {
  private WebhookSignature() {}

  public static String sign(String secret, String timestamp, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(mac.doFinal(body));
    } catch (java.security.GeneralSecurityException ex) {
      throw new IllegalStateException("Cannot initialize webhook signing", ex);
    }
  }
}
