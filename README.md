# Boldfit checkout slice

Five products, a cart, stock-safe checkout, a local payment provider (FitPay), a signed webhook, and customer order status. Java 17 / Spring Boot 3.5, PostgreSQL 17 with Flyway, React / TypeScript / Vite. No external payment account or credentials required.

## Run

Install Docker, Java 17+ and Maven, Node 22+ and npm. From the repository root:

```sh
docker compose up -d db
mvn -f backend/pom.xml spring-boot:run
```

In another terminal:

```sh
npm --prefix frontend ci
npm --prefix frontend run dev
```

Open [localhost:5173](http://127.0.0.1:5173). The backend runs on **8081**, PostgreSQL on **54329**. The frontend proxies `/api` to the backend. Tables and five sample SKUs are created automatically. Keep the backend running for callback delivery.

**Try it:** Add products → Bag → Continue to checkout → Continue to payment → choose a demo method → Pay. FitPay offers preset UPI, card, and netbanking instruments. No payment details are collected. The browser shows processing until the signed HTTP callback confirms the order.

## Assumptions and limits

- INR amounts are integer paise. Catalog prices include tax; delivery is free. No address collection or fulfillment is implemented.
- Cart is browser-local. Stock is reserved only at checkout, for 15 minutes. Displayed availability is a snapshot; the server checks again atomically.
- `demo-alex` and `demo-sam` are intentionally public demo bearer tokens. The UI uses Alex. They demonstrate authorization, not production identity security. Servers bind to loopback.
- FitPay is a local provider simulation, not a payment-rail integration. Failure controls are API-only and enabled by `DEMO_ENABLED` for development.
- Partial/mismatched payments and late success enter `PAYMENT_REVIEW`. Manual resolution/refunds are out of scope. Review retains an existing reservation; already released inventory is not reacquired.
- Callback delivery persists and retries every five seconds after failure. This demo has no retry exhaustion alert or reconciliation UI.

## Design decisions

1. **Database idempotency, not button-only protection.** Customer + operation + key identifies a request; normalized input detects changed-payload reuse (`409`). The business mutation and stored successful response commit together. Rollback leaves a safe retry. Keys persist indefinitely for the demo. A random new key is a new purchase, not deduplication by cart contents. Pay is idempotent by attempt identity and fixed outcome/method. See [CheckoutService](backend/src/main/java/com/boldfit/checkout/CheckoutService.java).
2. **Reserve inventory in PostgreSQL, without Redis.** Conditional stock decrements and order creation share one transaction; a failed item rolls back the entire cart. Sorted SKU access reduces deadlocks. Short database locks protect reservation data; no lock stays open while a shopper pays. Expiry and confirmation serialize on the order row. This keeps the source of truth and concurrency control together; Redis leases would introduce a second coordination system.
3. **Webhook confirmation and browser polling.** The local provider delivers real HTTP callbacks after commit. HMAC-SHA256 signs the exact body and delivery timestamp; a five-minute tolerance rejects stale deliveries. Persisted event IDs, payload hashes, and state guards prevent repeated effects. A fresh signature allows legitimate redelivery of the same event. Polling keeps navigation/refresh simple compared with WebSockets; browser success never marks an order paid. See [WebhookController](backend/src/main/java/com/boldfit/checkout/WebhookController.java) and [BackgroundJobs](backend/src/main/java/com/boldfit/checkout/BackgroundJobs.java).

### Edge cases and evidence

[CheckoutIntegrationTest](backend/src/test/java/com/boldfit/checkout/CheckoutIntegrationTest.java) exercises real PostgreSQL:

| Case | Behavior |
| --- | --- |
| Two customers buy the last unit | One checkout wins; stock cannot become negative |
| Multi-item checkout cannot fulfill an item | Entire checkout and idempotency record roll back |
| Concurrent double-submit | Same checkout/attempt response; one business mutation |
| Same key with different input | Conflict; no second mutation |
| Failed payment then retry | New attempt allowed after definitive failure; old failure cannot undo success |
| Partial payment | Review; no fulfillment or blind retry |
| Invalid, modified, stale, or future-dated webhook | Rejected before mutation |
| Concurrent duplicate event / repeated success with new event ID | One business effect |
| Event ID reused with different body | Conflict |
| Expiry versus success / late success | Release once; never confirm released stock |
| Cross-customer payment creation, submission, and mock resolution | `404`, with every application table unchanged; tested in both directions |
| Missing/invalid bearer token | `401` on all customer mutation endpoints |
| Null or invalid cart quantity | JSON validation error, no mutation |

### What changed after the first pass

Replaced developer outcome buttons with a dedicated mock payment experience. Added exact selected quantities and restrained low-stock messaging. Kept failure simulation out of the shopper UI. Replaced generic API responses with explicit DTOs and made order reads consistent database snapshots. Fixed old-order visits clearing a new cart and extended polling through the reservation period. Cut external payment integration to stay within the mock-only brief.

### Week 2

Introduce real authentication, operational alerts and a reconciliation workflow for unresolved payments and review-held inventory. Define production idempotency retention and retry/backoff policies. Before integrating a real provider, explicitly handle the non-atomic boundary between remote payment creation and local persistence. Measure stock contention and shopper recovery from changed availability before adding infrastructure. Improve delivery/address handling only when expanding beyond this checkout slice.

## API contract

See [API reference](docs/API.md) for bodies, status codes, authentication, and a failure simulation example. Request records validate input; response records are defined in [ApiDtos](backend/src/main/java/com/boldfit/checkout/ApiDtos.java).

## Verification

Create the isolated test database once (skip if it already exists):

```sh
docker compose exec -T db createdb -U checkout checkout_test
mvn -f backend/pom.xml test
npm --prefix frontend run build
```

Tests clear application data only in `checkout_test`. CI runs the backend suite on PostgreSQL and builds the frontend. Local backend tests currently run on Java 25 targeting Java 17; the CI workflow targets Java 17. CI execution is not verified until pushed. 26 integration cases pass locally. A live HTTP checkout was repeated after restarting the backend and returned the identical saved response. Full automated browser checks and callback-delivery restart recovery remain pending; do not interpret a passing build as browser coverage.

## Research and interview preparation

- [PostgreSQL locking](https://www.postgresql.org/docs/current/explicit-locking.html)
- [Stripe idempotency](https://docs.stripe.com/api/idempotent_requests) and [webhook delivery](https://docs.stripe.com/webhooks) as design references, not our mock's exact contract
- [Product decisions and UX sources](docs/PRODUCT_DECISIONS.md)
- [Interview checkpoints](docs/INTERVIEW.md)

Repository should remain private; the assignment permits ZIP submission as an alternative to a public repository.
