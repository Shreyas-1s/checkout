package com.boldfit.checkout.job;

import com.boldfit.checkout.service.PaymentDeliveryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduling only; delivery and expiry behavior live behind a service boundary. */
@Component
public class BackgroundJobs {
  private final PaymentDeliveryService service;

  public BackgroundJobs(PaymentDeliveryService service) {
    this.service = service;
  }

  @Scheduled(
      fixedDelayString = "${app.expiry-delay:5000}",
      initialDelayString = "${app.job-initial-delay:1000}")
  public void expire() {
    service.expire();
  }

  @Scheduled(
      fixedDelayString = "${app.delivery-delay:1000}",
      initialDelayString = "${app.job-initial-delay:1000}")
  public void deliver() {
    service.deliver();
  }
}
