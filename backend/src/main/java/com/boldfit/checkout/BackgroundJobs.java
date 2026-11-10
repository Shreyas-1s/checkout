package com.boldfit.checkout;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.nio.charset.StandardCharsets;

@Component
public class BackgroundJobs {
    private static final Logger log=LoggerFactory.getLogger(BackgroundJobs.class);
    private final CheckoutService service;
    private final JdbcTemplate db;
    private final String secret, callback;
    private final boolean enabled;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public BackgroundJobs(CheckoutService service,JdbcTemplate db,@Value("${app.webhook-secret}") String secret,@Value("${app.callback-url}") String callback,@Value("${app.demo-enabled}") boolean enabled) {
        this.service=service;this.db=db;this.secret=secret;this.callback=callback;this.enabled=enabled;
    }
    @Scheduled(fixedDelayString="${app.expiry-delay:5000}",initialDelayString="${app.job-initial-delay:1000}")
    public void expire() { for(var id:service.expiring()) service.expire(id); }
    /** Delivery happens outside a DB transaction. Repeated delivery is intentional and safe. */
    @Scheduled(fixedDelayString="${app.delivery-delay:1000}",initialDelayString="${app.job-initial-delay:1000}")
    public void deliver() {
        if(!enabled) return;
        for(var row:db.queryForList("SELECT attempt_id,payload FROM mock_delivery WHERE delivered=false AND next_delivery<=now() LIMIT 10")) {
            try {
                byte[] body=((String)row.get("payload")).getBytes(StandardCharsets.UTF_8);
                String timestamp=Long.toString(Instant.now().getEpochSecond());
                var request=HttpRequest.newBuilder(URI.create(callback)).timeout(Duration.ofSeconds(3))
                    .header("Content-Type","application/json").header("X-Payment-Timestamp",timestamp)
                    .header("X-Payment-Signature",WebhookController.sign(secret,timestamp,body))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                var response=http.send(request,HttpResponse.BodyHandlers.discarding());
                if(response.statusCode()/100==2) db.update("UPDATE mock_delivery SET delivered=true WHERE attempt_id=?",row.get("attempt_id"));
                else retry(row.get("attempt_id"));
            } catch(InterruptedException ex) { Thread.currentThread().interrupt(); return; }
            catch(Exception ex) { log.warn("Mock delivery failed for attempt {}",row.get("attempt_id")); retry(row.get("attempt_id")); }
        }
    }
    private void retry(Object id) {
        db.update("UPDATE mock_delivery SET retries=retries+1,next_delivery=now()+interval '5 seconds' WHERE attempt_id=?",id);
    }
}
