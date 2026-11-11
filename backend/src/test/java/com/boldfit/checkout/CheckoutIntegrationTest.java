package com.boldfit.checkout;

import static org.assertj.core.api.Assertions.*;

import com.boldfit.checkout.dto.CartItem;
import com.boldfit.checkout.dto.CreatedAttempt;
import com.boldfit.checkout.dto.Order;
import com.boldfit.checkout.dto.PaymentEvent;
import com.boldfit.checkout.dto.Product;
import com.boldfit.checkout.exception.ApiException;
import com.boldfit.checkout.security.WebhookSignature;
import com.boldfit.checkout.service.CheckoutService;
import com.boldfit.checkout.service.PaymentDeliveryService;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:54329/checkout_test",
      "app.job-initial-delay=3600000"
    })
class CheckoutIntegrationTest {
  @Autowired CheckoutService service;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @Autowired TestRestTemplate http;
  @Autowired PaymentDeliveryService jobs;
  @LocalServerPort int port;

  @BeforeEach
  void reset() {
    db.execute(
        "TRUNCATE"
            + " agent_action_audit,review_resolution,mock_delivery,webhook_event,idempotency,payment_attempt,order_line,customer_order");
    db.update("UPDATE product SET available=10");
  }

  List<CartItem> items() {
    return List.of(new CartItem("mat", 1));
  }

  UUID order(String customer, String key) throws Exception {
    return service.checkout(customer, key, items()).id();
  }

  UUID attempt(UUID order, String key) throws Exception {
    return service.attempt("alex", order, key).id();
  }

  PaymentEvent event(UUID order, UUID attempt, String outcome, long amount) {
    return new PaymentEvent(UUID.randomUUID(), attempt, order, outcome, amount, "INR");
  }

  void receive(PaymentEvent event) throws Exception {
    service.receive(event, json.writeValueAsString(event));
  }

  String status(UUID order) {
    return service.order("alex", order).status();
  }

  int stock() {
    return db.queryForObject("SELECT available FROM product WHERE id='mat'", Integer.class);
  }

  <T> List<T> race(Callable<T> a, Callable<T> b) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch gate = new CountDownLatch(1);
    try {
      Future<T> one =
          pool.submit(
              () -> {
                gate.await();
                return a.call();
              });
      Future<T> two =
          pool.submit(
              () -> {
                gate.await();
                return b.call();
              });
      gate.countDown();
      return List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void lastUnitHasOneWinner() throws Exception {
    db.update("UPDATE product SET available=1 WHERE id='mat'");
    Callable<Boolean> a =
        () -> {
          try {
            service.checkout("alex", "race-key-alex", items());
            return true;
          } catch (ApiException ex) {
            assertThat(ex.code).isEqualTo("OUT_OF_STOCK");
            return false;
          }
        };
    Callable<Boolean> b =
        () -> {
          try {
            service.checkout("sam", "race-key-sam", items());
            return true;
          } catch (ApiException ex) {
            assertThat(ex.code).isEqualTo("OUT_OF_STOCK");
            return false;
          }
        };
    assertThat(race(a, b)).containsExactlyInAnyOrder(true, false);
    assertThat(stock()).isZero();
    assertThat(db.queryForObject("SELECT count(*) FROM customer_order", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void concurrentCheckoutReplaysSameResponseAndRejectsChangedInput() throws Exception {
    var responses =
        race(
            () -> service.checkout("alex", "same-key-123", items()),
            () -> service.checkout("alex", "same-key-123", items()));
    assertThat(responses.get(0)).isEqualTo(responses.get(1));
    assertThat(stock()).isEqualTo(9);
    assertThatThrownBy(
            () -> service.checkout("alex", "same-key-123", List.of(new CartItem("mat", 2))))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("different request");
  }

  @Test
  void multiItemFailureRollsBackStockAndIdempotency() {
    assertThatThrownBy(
            () ->
                service.checkout(
                    "alex",
                    "rollback-123",
                    List.of(new CartItem("mat", 1), new CartItem("zzz", 1))))
        .isInstanceOf(ApiException.class);
    assertThat(stock()).isEqualTo(10);
    assertThat(db.queryForObject("SELECT count(*) FROM idempotency", Integer.class)).isZero();
  }

  @Test
  void concurrentAttemptCreationIsIdempotent() throws Exception {
    UUID order = order("alex", "order-key-1");
    var replies =
        race(
            () -> service.attempt("alex", order, "attempt-key-1"),
            () -> service.attempt("alex", order, "attempt-key-1"));
    assertThat(replies.get(0)).isEqualTo(replies.get(1));
    assertThatThrownBy(() -> service.attempt("alex", order, "different-key"))
        .isInstanceOf(ApiException.class);
    assertThat(db.queryForObject("SELECT count(*) FROM payment_attempt", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void concurrentAndSemanticDuplicateCallbacksHaveOneEffect() throws Exception {
    UUID order = order("alex", "order-key-1"), attempt = attempt(order, "attempt-key-1");
    var event = event(order, attempt, "SUCCEEDED", 129900);
    race(
        () -> {
          receive(event);
          return true;
        },
        () -> {
          receive(event);
          return true;
        });
    receive(event(order, attempt, "SUCCEEDED", 129900));
    assertThat(status(order)).isEqualTo("CONFIRMED");
    assertThat(stock()).isEqualTo(9);
    assertThat(db.queryForObject("SELECT count(*) FROM webhook_event", Integer.class)).isEqualTo(2);
  }

  @Test
  void failedAttemptCanRetryAndOldFailureDoesNotRegressConfirmation() throws Exception {
    UUID order = order("alex", "order-key-1"), first = attempt(order, "attempt-key-1");
    receive(event(order, first, "FAILED", 129900));
    UUID second = attempt(order, "attempt-key-2");
    receive(event(order, second, "SUCCEEDED", 129900));
    receive(event(order, first, "FAILED", 129900));
    assertThat(status(order)).isEqualTo("CONFIRMED");
  }

  @Test
  void partialPaymentRequiresReviewAndBlocksRetry() throws Exception {
    UUID order = order("alex", "order-key-1"), attempt = attempt(order, "attempt-key-1");
    receive(event(order, attempt, "SUCCEEDED", 60000));
    assertThat(status(order)).isEqualTo("PAYMENT_REVIEW");
    assertThatThrownBy(() -> service.attempt("alex", order, "attempt-key-2"))
        .isInstanceOf(ApiException.class);
    assertThat(stock()).isEqualTo(9);
  }

  @Test
  void expiryReleasesExactlyOnceAndLateSuccessRequiresReview() throws Exception {
    UUID order = order("alex", "order-key-1"), attempt = attempt(order, "attempt-key-1");
    db.update("UPDATE customer_order SET expires_at=now()-interval '1 minute' WHERE id=?", order);
    race(
        () -> {
          service.expire(order);
          return true;
        },
        () -> {
          service.expire(order);
          return true;
        });
    assertThat(status(order)).isEqualTo("EXPIRED");
    assertThat(stock()).isEqualTo(10);
    receive(event(order, attempt, "SUCCEEDED", 129900));
    assertThat(status(order)).isEqualTo("PAYMENT_REVIEW");
    assertThat(stock()).isEqualTo(10);
  }

  @Test
  void expiryAndSuccessRaceCannotConfirmReleasedStock() throws Exception {
    UUID order = order("alex", "order-key-1"), attempt = attempt(order, "attempt-key-1");
    db.update("UPDATE customer_order SET expires_at=now()-interval '1 minute' WHERE id=?", order);
    var event = event(order, attempt, "SUCCEEDED", 129900);
    race(
        () -> {
          service.expire(order);
          return true;
        },
        () -> {
          receive(event);
          return true;
        });
    assertThat(status(order)).isEqualTo("PAYMENT_REVIEW");
    assertThat(stock()).isEqualTo(10);
  }

  @Test
  void authenticationOwnershipAndValidationAreEnforced() throws Exception {
    UUID order = order("alex", "order-key-1");
    assertThat(http.getForEntity("/api/orders/" + order, String.class).getStatusCode().value())
        .isEqualTo(401);
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("demo-sam");
    assertThat(
            http.exchange(
                    "/api/orders/" + order, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode()
                .value())
        .isEqualTo(404);
    headers.set("Idempotency-Key", "invalid-quantity");
    headers.setContentType(MediaType.APPLICATION_JSON);
    assertThat(
            http.postForEntity(
                    "/api/orders",
                    new HttpEntity<>("{\"items\":[{\"skuId\":\"mat\",\"quantity\":-1}]}", headers),
                    String.class)
                .getStatusCode()
                .value())
        .isEqualTo(400);
  }

  ResponseEntity<String> send(String body, String timestamp, String signature) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Payment-Timestamp", timestamp);
    headers.set("X-Payment-Signature", signature);
    return http.postForEntity("/webhooks/payment", new HttpEntity<>(body, headers), String.class);
  }

  String sign(String body, String timestamp) {
    return WebhookSignature.sign(
        "local-demo-signing-secret-not-for-production",
        timestamp,
        body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void rawBodySignatureAndFreshnessAreVerifiedBeforeMutation() throws Exception {
    UUID order = order("alex", "order-key-1"), attempt = attempt(order, "attempt-key-1");
    String body = json.writeValueAsString(event(order, attempt, "SUCCEEDED", 129900)),
        now = "" + Instant.now().getEpochSecond(),
        old = "" + (Instant.now().getEpochSecond() - 301),
        future = "" + (Instant.now().getEpochSecond() + 301);
    assertThat(send(body, now, "0".repeat(64)).getStatusCode().value()).isEqualTo(401);
    assertThat(send(body + " ", now, sign(body, now)).getStatusCode().value()).isEqualTo(401);
    assertThat(send(body, old, sign(body, old)).getStatusCode().value()).isEqualTo(401);
    assertThat(send(body, future, sign(body, future)).getStatusCode().value()).isEqualTo(401);
    assertThat(status(order)).isEqualTo("AWAITING_PAYMENT");
    assertThat(send(body, now, sign(body, now)).getStatusCode().value()).isEqualTo(200);
    assertThat(send(body, now, sign(body, now)).getStatusCode().value()).isEqualTo(200);
    assertThat(status(order)).isEqualTo("CONFIRMED");
  }

  @Test
  void reusedEventIdWithChangedBodyIsRejected() throws Exception {
    UUID order = order("alex", "order-key-1"), attempt = attempt(order, "attempt-key-1");
    var event = event(order, attempt, "SUCCEEDED", 129900);
    receive(event);
    var changed = new PaymentEvent(event.eventId(), attempt, order, "FAILED", 129900, "INR");
    assertThatThrownBy(() -> receive(changed))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("different payload");
    assertThat(status(order)).isEqualTo("CONFIRMED");
  }

  @Test
  void mockOutcomeIsDurableAndImmutable() throws Exception {
    UUID order = order("alex", "order-key-1"), attempt = attempt(order, "attempt-key-1");
    service.simulate("alex", attempt, "SUCCEEDED");
    service.simulate("alex", attempt, "SUCCEEDED");
    assertThat(db.queryForObject("SELECT count(*) FROM mock_delivery", Integer.class)).isEqualTo(1);
    assertThatThrownBy(() -> service.simulate("alex", attempt, "FAILED"))
        .isInstanceOf(ApiException.class);
    assertThat(status(order)).isEqualTo("AWAITING_PAYMENT");
  }

  @Test
  void customerPaymentQueuesOneCallbackAndDoesNotConfirmDirectly() throws Exception {
    UUID order = order("alex", "order-key-1"), attempt = attempt(order, "attempt-key-1");
    race(
        () -> {
          service.pay("alex", attempt, "UPI");
          return true;
        },
        () -> {
          service.pay("alex", attempt, "UPI");
          return true;
        });
    assertThat(status(order)).isEqualTo("AWAITING_PAYMENT");
    assertThat(db.queryForObject("SELECT count(*) FROM mock_delivery", Integer.class)).isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT method FROM payment_attempt WHERE id=?", String.class, attempt))
        .isEqualTo("UPI");
    assertThatThrownBy(() -> service.pay("alex", attempt, "CARD")).isInstanceOf(ApiException.class);
    String payload =
        db.queryForObject(
            "SELECT payload FROM mock_delivery WHERE attempt_id=?", String.class, attempt);
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    assertThat(send(payload, timestamp, sign(payload, timestamp)).getStatusCode().value())
        .isEqualTo(200);
    assertThat(status(order)).isEqualTo("CONFIRMED");
  }

  @Test
  void expiredReservationCannotStartMockPayment() throws Exception {
    UUID order = order("alex", "order-key-1"), attempt = attempt(order, "attempt-key-1");
    db.update("UPDATE customer_order SET expires_at=now()-interval '1 minute' WHERE id=?", order);
    assertThatThrownBy(() -> service.pay("alex", attempt, "CARD")).isInstanceOf(ApiException.class);
    assertThat(db.queryForObject("SELECT count(*) FROM mock_delivery", Integer.class)).isZero();
  }

  // HTTP-level authorization regressions: exercise the filter, controller, and service together.
  private ResponseEntity<String> mutation(String token, String path, Object body, String key)
      throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (token != null && !token.isEmpty()) headers.setBearerAuth(token);
    if (key != null) headers.set("Idempotency-Key", key);
    return http.postForEntity(
        path, new HttpEntity<>(json.writeValueAsString(body), headers), String.class);
  }

  private Map<String, List<String>> databaseSnapshot() {
    Map<String, List<String>> snapshot = new TreeMap<>();
    // Fixed table names, never user input. Include retry metadata and replay records as well as
    // orders.
    for (String table :
        List.of(
            "agent_action_audit",
            "review_resolution",
            "product",
            "customer_order",
            "order_line",
            "payment_attempt",
            "idempotency",
            "mock_delivery",
            "webhook_event"))
      snapshot.put(
          table,
          db.queryForList(
              "SELECT row_to_json(t)::text FROM " + table + " t ORDER BY row_to_json(t)::text",
              String.class));
    return snapshot;
  }

  private void assertNotFound(ResponseEntity<String> response) throws Exception {
    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(response.getHeaders().getContentType()).isNotNull();
    assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON))
        .isTrue();
    assertThat(json.readTree(response.getBody()).path("code").asText()).isEqualTo("NOT_FOUND");
  }

  @ParameterizedTest
  @CsvSource({"alex,sam", "sam,alex"})
  void anotherCustomerCannotCreateOrReplayPaymentAttempts(String owner, String other)
      throws Exception {
    UUID order = order(owner, "ownership-order");
    String path = "/api/orders/" + order + "/payment-attempts";
    var before = databaseSnapshot();
    assertNotFound(mutation("demo-" + other, path, Map.of(), "ownership-attempt"));
    assertThat(databaseSnapshot()).isEqualTo(before);
    var allowed = mutation("demo-" + owner, path, Map.of(), "ownership-attempt");
    assertThat(allowed.getStatusCode().value()).isEqualTo(201);
    before = databaseSnapshot();
    // Knowing the owner's key must not expose its saved response or authorize a mutation.
    assertNotFound(mutation("demo-" + other, path, Map.of(), "ownership-attempt"));
    assertNotFound(mutation("demo-" + other, path, Map.of(), "new-attacker-key"));
    assertThat(databaseSnapshot()).isEqualTo(before);
  }

  @ParameterizedTest
  @CsvSource({"alex,sam", "sam,alex"})
  void anotherCustomerCannotPayOrReplayPayment(String owner, String other) throws Exception {
    UUID order = order(owner, "ownership-order");
    UUID attempt = service.attempt(owner, order, "ownership-attempt").id();
    String path = "/api/payments/" + attempt + "/pay";
    var before = databaseSnapshot();
    assertNotFound(mutation("demo-" + other, path, Map.of("method", "CARD"), null));
    assertThat(databaseSnapshot()).isEqualTo(before);
    assertThat(
            mutation("demo-" + owner, path, Map.of("method", "UPI"), null).getStatusCode().value())
        .isEqualTo(202);
    before = databaseSnapshot();
    assertNotFound(mutation("demo-" + other, path, Map.of("method", "UPI"), null));
    assertNotFound(mutation("demo-" + other, path, Map.of("method", "CARD"), null));
    assertThat(databaseSnapshot()).isEqualTo(before);
  }

  @ParameterizedTest
  @CsvSource({"alex,sam", "sam,alex"})
  void anotherCustomerCannotResolveOrRedeliverMockPayment(String owner, String other)
      throws Exception {
    UUID order = order(owner, "ownership-order");
    UUID attempt = service.attempt(owner, order, "ownership-attempt").id();
    String path = "/api/demo/payments/" + attempt + "/resolve";
    var before = databaseSnapshot();
    for (String outcome : List.of("SUCCEEDED", "FAILED", "PARTIAL"))
      assertNotFound(mutation("demo-" + other, path, Map.of("outcome", outcome), null));
    assertThat(databaseSnapshot()).isEqualTo(before);
    assertThat(
            mutation("demo-" + owner, path, Map.of("outcome", "SUCCEEDED"), null)
                .getStatusCode()
                .value())
        .isEqualTo(202);
    db.update("UPDATE mock_delivery SET delivered=true WHERE attempt_id=?", attempt);
    before = databaseSnapshot();
    assertNotFound(mutation("demo-" + other, path, Map.of("outcome", "SUCCEEDED"), null));
    assertThat(databaseSnapshot()).isEqualTo(before);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"invalid-token"})
  void missingOrInvalidAuthenticationBlocksEveryCustomerMutation(String token) throws Exception {
    UUID order = order("alex", "ownership-order"), attempt = attempt(order, "ownership-attempt");
    var before = databaseSnapshot();
    List<ResponseEntity<String>> responses =
        List.of(
            mutation(
                token,
                "/api/orders",
                Map.of("items", List.of(Map.of("skuId", "mat", "quantity", 1))),
                "unauth-checkout"),
            mutation(
                token, "/api/orders/" + order + "/payment-attempts", Map.of(), "unauth-attempt"),
            mutation(token, "/api/payments/" + attempt + "/pay", Map.of("method", "UPI"), null),
            mutation(
                token,
                "/api/demo/payments/" + attempt + "/resolve",
                Map.of("outcome", "SUCCEEDED"),
                null));
    for (var response : responses) {
      assertThat(response.getStatusCode().value()).isEqualTo(401);
      assertThat(json.readTree(response.getBody()).path("code").asText()).isEqualTo("UNAUTHORIZED");
    }
    assertThat(databaseSnapshot()).isEqualTo(before);
  }

  @Test
  void nullCartItemIsAValidationErrorWithNoSideEffects() throws Exception {
    var before = databaseSnapshot();
    var response =
        mutation(
            "demo-alex",
            "/api/orders",
            Map.of("items", Arrays.asList((Object) null)),
            "null-item-key");
    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(json.readTree(response.getBody()).path("code").asText())
        .isEqualTo("INVALID_REQUEST");
    assertThat(databaseSnapshot()).isEqualTo(before);
  }

  @Test
  void publicResponseContractsExposeTypedDatesAndPaymentState() throws Exception {
    var products = http.getForEntity("/api/products", Product[].class);
    assertThat(products.getStatusCode().value()).isEqualTo(200);
    assertThat(products.getBody()).hasSize(5);
    var created =
        mutation(
            "demo-alex",
            "/api/orders",
            Map.of("items", List.of(Map.of("skuId", "mat", "quantity", 1))),
            "contract-checkout");
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    Order order = json.readValue(created.getBody(), Order.class);
    assertThat(order.expiresAt()).isAfter(order.createdAt());
    assertThat(order.lines()).hasSize(1);
    assertThat(created.getHeaders().getLocation().toString())
        .isEqualTo("/api/orders/" + order.id());
    var attemptResponse =
        mutation(
            "demo-alex",
            "/api/orders/" + order.id() + "/payment-attempts",
            Map.of(),
            "contract-attempt");
    CreatedAttempt attempt = json.readValue(attemptResponse.getBody(), CreatedAttempt.class);
    assertThat(attempt.orderId()).isEqualTo(order.id());
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("demo-alex");
    var fetched =
        http.exchange(
            "/api/orders/" + order.id(), HttpMethod.GET, new HttpEntity<>(headers), Order.class);
    assertThat(fetched.getBody().attempts()).hasSize(1);
    assertThat(fetched.getBody().attempts().get(0).submitted()).isFalse();
    assertThat(fetched.getBody().attempts().get(0).createdAt()).isNotNull();
  }

  @Test
  void callbackExhaustionIsBoundedAndOperatorCanRecover() throws Exception {
    UUID order = order("alex", "recovery-order"), attempt = attempt(order, "recovery-attempt");
    service.simulate("alex", attempt, "SUCCEEDED");
    for (int i = 0; i < 7; i++) jobs.recordFailure(attempt, "HTTP_503");
    assertThat(
            db.queryForObject(
                "SELECT retries FROM mock_delivery WHERE attempt_id=?", Integer.class, attempt))
        .isEqualTo(5);
    assertThat(
            db.queryForObject(
                "SELECT exhausted FROM mock_delivery WHERE attempt_id=?", Boolean.class, attempt))
        .isTrue();
    String path = "/api/ops/agent-actions/retry-failed-payment";
    UUID actionId = UUID.randomUUID();
    String approvedAt = Instant.now().toString();
    var action =
        Map.of(
            "actionId",
            actionId,
            "action",
            "RETRY_FAILED_PAYMENT",
            "orderId",
            order,
            "paymentAttemptId",
            attempt,
            "reason",
            "Webhook delivery exhausted after provider success",
            "approvalStatus",
            "APPROVED",
            "approvedBy",
            "ops-lead@boldfit.test",
            "approvedAt",
            approvedAt);
    var before = databaseSnapshot();
    assertThat(mutation("demo-alex", path, action, null).getStatusCode().value()).isEqualTo(403);
    assertThat(databaseSnapshot()).isEqualTo(before);
    var missingApproval = new HashMap<String, Object>(action);
    missingApproval.remove("approvedBy");
    assertThat(mutation("demo-operator", path, missingApproval, null).getStatusCode().value())
        .isEqualTo(400);
    var pendingApproval = new HashMap<String, Object>(action);
    pendingApproval.put("approvalStatus", "PENDING");
    var pendingResponse = mutation("demo-operator", path, pendingApproval, null);
    assertThat(pendingResponse.getStatusCode().value()).isEqualTo(400);
    assertThat(json.readTree(pendingResponse.getBody()).path("code").asText())
        .isEqualTo("HUMAN_APPROVAL_REQUIRED");
    var wrongOrder = new HashMap<String, Object>(action);
    wrongOrder.put("orderId", UUID.randomUUID());
    assertThat(mutation("demo-operator", path, wrongOrder, null).getStatusCode().value())
        .isEqualTo(400);
    assertThat(databaseSnapshot()).isEqualTo(before);
    assertThat(mutation("demo-operator", path, action, null).getStatusCode().value())
        .isEqualTo(200);
    assertThat(mutation("demo-operator", path, action, null).getStatusCode().value())
        .isEqualTo(200);
    assertThat(
            db.queryForObject(
                "SELECT retries FROM mock_delivery WHERE attempt_id=?", Integer.class, attempt))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT exhausted FROM mock_delivery WHERE attempt_id=?", Boolean.class, attempt))
        .isFalse();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM agent_action_audit WHERE action_id=?",
                Integer.class,
                actionId))
        .isEqualTo(1);
    String payload =
        db.queryForObject(
            "SELECT payload FROM mock_delivery WHERE attempt_id=?", String.class, attempt);
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    assertThat(send(payload, timestamp, sign(payload, timestamp)).getStatusCode().value())
        .isEqualTo(200);
    assertThat(send(payload, timestamp, sign(payload, timestamp)).getStatusCode().value())
        .isEqualTo(200);
    assertThat(status(order)).isEqualTo("CONFIRMED");
    assertThat(stock()).isEqualTo(9);
  }

  @Test
  void reviewResolutionIsAuthorizedIdempotentAndCannotBeResurrected() throws Exception {
    UUID order = order("alex", "review-order"), attempt = attempt(order, "review-attempt");
    receive(event(order, attempt, "SUCCEEDED", 60000));
    String path = "/api/ops/orders/" + order + "/resolve-review";
    var body = Map.of("reason", "Mock refund recorded after partial payment");
    var before = databaseSnapshot();
    assertThat(mutation("demo-sam", path, body, null).getStatusCode().value()).isEqualTo(403);
    assertThat(databaseSnapshot()).isEqualTo(before);
    assertThat(mutation("demo-operator", path, body, null).getStatusCode().value()).isEqualTo(200);
    assertThat(mutation("demo-operator", path, body, null).getStatusCode().value()).isEqualTo(200);
    receive(event(order, attempt, "SUCCEEDED", 129900));
    assertThat(status(order)).isEqualTo("CANCELLED");
    assertThat(stock()).isEqualTo(10);
    assertThat(db.queryForObject("SELECT count(*) FROM review_resolution", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void validationAndAuthenticationErrorsCarryRequestReferences() throws Exception {
    var response =
        mutation(
            "demo-alex",
            "/api/orders",
            Map.of("items", List.of(Map.of("skuId", "mat", "quantity", 0))),
            "invalid-quantity");
    var body = json.readTree(response.getBody());
    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(body.path("fields").has("items[0].quantity")).isTrue();
    assertThat(body.path("requestId").asText())
        .isEqualTo(response.getHeaders().getFirst("X-Request-Id"))
        .isNotBlank();
    response = mutation(null, "/api/orders", Map.of(), null);
    assertThat(json.readTree(response.getBody()).path("requestId").asText())
        .isEqualTo(response.getHeaders().getFirst("X-Request-Id"))
        .isNotBlank();
  }
}
