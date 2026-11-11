# Checkout slice

Five products, a cart, stock-safe checkout, a local payment provider (FitPay), a signed webhook, and customer order status. Java 17 / Spring Boot 3.5, PostgreSQL 17 with Flyway, React / TypeScript / Vite. No external payment account or credentials required.

## Run

Install Docker, Java 17+ and Maven, Node 22.12+ (22.x) or Node 24+ and npm. From the repository root:

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
- Cart uses browser `localStorage`, so it normally survives refreshes and browser restarts on the same device. It does not sync across devices and is lost when site data is cleared. Stock is reserved only at checkout, for 15 minutes. Displayed availability is a snapshot; the server checks again atomically.
- `demo-alex` and `demo-sam` are intentionally public demo bearer tokens. The UI uses Alex. They demonstrate authorization, not production identity security. Servers bind to loopback.
- FitPay is a local provider simulation, not a payment-rail integration. Failure controls are API-only and enabled by `DEMO_ENABLED` for development.
- Partial/mismatched payments and late success enter `PAYMENT_REVIEW`. An operator can record a mock refund/cancellation and release held stock once; real refunds are out of scope. Until resolution, review retains an existing reservation; already released inventory is not reacquired.
- Callback delivery persists and stops after five failures, with exponential backoff. Exhaustion produces a structured error log and is exposed through operator recovery APIs; there is no external alert integration or operator UI.

## Design diagrams

See the [illustrated flow guide](docs/DESIGN_DIAGRAMS.md) for architecture, checkout, stock races, retries, payment/webhook delivery, state machines, expiry, authorization, and the data model. Diagrams describe the current implementation and call out its limitations.

## Design decisions

The following three decisions concern correctness and delivery. Backend layering is described separately below.

### 1. Reserve stock in the database transaction

I chose PostgreSQL because inventory reservation and order creation must succeed or fail together. Checkout conditionally decrements available stock, snapshots prices, and creates the order in one transaction. If an item is unavailable, everything rolls back. SKU access is sorted to reduce deadlocks.

I considered Redis locking, but inventory already lives in PostgreSQL. A Redis lease would add expiry and coordination failure modes without making the database writes atomic. The tradeoff is contention on popular SKU rows. For this small catalog, a short transaction is a reasonable starting point; I would measure contention before adding infrastructure. The database lock lasts only for the transaction; the 15-minute reservation is persisted data.

### 2. Persist retry identity with the business mutation

Checkout and payment-attempt creation use a client-generated key scoped to customer and operation. The normalized request fingerprint and original successful response commit with the mutation. A repeated request returns that response; changed input under the same key gets `409`.

Disabling a button cannot protect against network retries or concurrent clients, and an in-memory cache loses protection after restart. Persisting keys costs storage, and replayed responses can be stale: callers read the order endpoint for current status. Keys are retained indefinitely for this demo; production needs an explicit retention policy. A new key means a new operation, even for identical cart contents. Pay itself deduplicates by payment-attempt identity and fixed outcome/method.

### 3. Confirm through a signed webhook; display status through polling

FitPay queues a callback and sends it over HTTP after commit. The webhook authenticates the raw body and timestamp, then records the event and its effects atomically. Timestamp checks reject stale deliveries; event IDs, payload hashes, and state guards handle repeated deliveries. The browser never decides that an order is paid.

I chose polling over WebSockets because the small status page needs simple refresh and navigation recovery. The cost is repeated reads and a short delay before confirmation appears. Order and payment-attempt states are separate: a definitive failed attempt can be retried, while an unknown outcome stays pending. Partial or late payments enter review rather than risking another payment or fulfilling released stock.

Implementation: [CheckoutServiceImpl](backend/src/main/java/com/boldfit/checkout/service/impl/CheckoutServiceImpl.java), [CheckoutRepository](backend/src/main/java/com/boldfit/checkout/repository/CheckoutRepository.java), [WebhookController](backend/src/main/java/com/boldfit/checkout/controller/WebhookController.java), and [BackgroundJobs](backend/src/main/java/com/boldfit/checkout/job/BackgroundJobs.java). See the [backend architecture guide](docs/BACKEND_ARCHITECTURE.md) for package responsibilities and transaction boundaries.

## Backend architecture

The request path is **controller → service interface → service implementation → repository → PostgreSQL**. Spring injects implementations through constructors. Standalone Java records define request validation, response contracts, and internal data snapshots.

```text
backend/src/main/java/com/boldfit/checkout/
├── controller/     HTTP routes, validation and response status codes
├── dto/            Request and response records
├── service/        Checkout, recovery and payment-delivery interfaces
│   └── impl/       Business rules and transaction boundaries
├── repository/     JDBC queries, row mapping and database locks
├── domain/         Typed order, attempt, delivery and audit records
├── security/       Demo authentication, request IDs and HMAC signing
├── exception/      Domain exceptions and centralized error responses
└── job/            Scheduled triggers that delegate to services
```

Start with [ApiController](backend/src/main/java/com/boldfit/checkout/controller/ApiController.java), then [CheckoutService](backend/src/main/java/com/boldfit/checkout/service/CheckoutService.java), [CheckoutServiceImpl](backend/src/main/java/com/boldfit/checkout/service/impl/CheckoutServiceImpl.java), and [CheckoutRepository](backend/src/main/java/com/boldfit/checkout/repository/CheckoutRepository.java). Recovery and callback delivery have their own service interfaces and implementations.

Controllers handle HTTP and delegate application work; they contain no SQL or transactional recovery logic. Services return typed DTOs rather than serialized JSON. Repositories map database rows into typed records rather than exposing generic maps. Transaction boundaries remain on service implementations so an entire checkout or webhook transition commits atomically across repository calls. Scheduled HTTP callback delivery runs outside database transactions.

Service interfaces make the use cases explicit and separate callers from their implementations. Repositories remain concrete JDBC components: adding an interface for each would introduce another layer without a second persistence implementation. JDBC keeps conditional stock updates and locking visible; internal records model persisted data without introducing JPA. The cost is explicit SQL and row mapping. See [backend architecture and transaction details](docs/BACKEND_ARCHITECTURE.md).

## Frontend architecture

React and TypeScript use feature folders with focused hooks. Vite serves the development app and builds production assets. The root app composes pages and shared cart state; it no longer owns payment submission, order polling or storage details.

```text
frontend/src/
├── app/                  App shell and existing hash URLs
├── features/
│   ├── catalog/          Catalog page, availability labels, useCatalog
│   ├── cart/             Quantity rules, persistence, useCart
│   ├── checkout/         Bag/checkout page and useCheckout
│   ├── payment/          FitPay screen and usePayment
│   └── orders/           Order page and useOrderStatus
└── shared/
    ├── api/              HTTP client, status unions, named API operations
    ├── components/       Shared artwork and error display
    └── lib/              Money formatting, storage and mutation state
```

Components render the UI; hooks coordinate state and lifecycle; API functions describe operations such as creating an order or submitting payment. Order reads are cancelled when obsolete, and polling stops on terminal states. Failed checkout requests retain their idempotency key and cart. Payment submission shows processing until a server read reports confirmation. Money remains integer paise until formatting, including fractional rupees such as `₹399.50`.

This uses React state and focused hooks without adding a global state library. Existing hash URLs remain shareable. The tradeoff is maintaining our small routing and polling hooks; richer navigation or shared server-state needs could justify dedicated libraries later. See [frontend architecture and tests](docs/FRONTEND_ARCHITECTURE.md).

## Discussion: scope and product choices

| Choice | Reason and accepted limitation |
| --- | --- |
| One Spring application and PostgreSQL | Keeps checkout transactions and local setup understandable. FitPay is a separate logical component, but not an independently deployed service. This does not prove recovery across a real external provider boundary. |
| Layered services, explicit JDBC and standalone DTOs | Separates HTTP, business rules and persistence while keeping conditional updates and locks visible. Service interfaces define use cases; repositories return typed records. The cost is more files and explicit row mapping. |
| Browser-local cart; server-owned prices | Avoids a cart persistence subsystem for five products. The server accepts SKU IDs and quantities and calculates the payable total. Cross-device cart sync is out of scope. |
| Demo tokens plus resource ownership checks | Demonstrates both authentication and authorization without building signup. Knowing an order ID or possessing a different valid token does not grant access. Public demo tokens are not production identity protection. |
| Local payment screen with preset instruments | Meets the mocked-provider requirement while showing a complete customer journey. No account setup, real credentials, or card storage. UPI/card/netbanking are simulated choices, not payment-rail implementations. |
| Exact cart quantities; restrained availability messages | Shoppers can see and adjust what they selected. Inventory is presented as in stock, low stock, or sold out. Five units is an initial low-stock threshold, not a proven optimum; it is based on actual available stock. |
| Sticky bag summary | Keeps item count, total, and the checkout action accessible while browsing. It appears only with a nonempty bag and is absent from checkout and order pages. |

Research and the distinction between evidence and product assumptions are recorded in [product decision notes](docs/PRODUCT_DECISIONS.md).

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

### One failure mode to walk through

Two customers request the last unit simultaneously. In `CheckoutServiceImpl.checkout`, both reach `CheckoutRepository.reserveStock`, which performs a conditional inventory update; only one can decrement the remaining unit. The other receives an out-of-stock conflict, and its transaction leaves no order or stock change behind. `lastUnitHasOneWinner` in the integration suite demonstrates this with two concurrent callers against PostgreSQL. This protects the invariant even when requests come from different application instances sharing the database; the test itself uses one application process.

### What changed after the first pass

The first payment UI exposed success/failure buttons. I replaced it with method selection, Pay, processing, and webhook-confirmed status; failure simulation remains an API-only development tool. Product feedback led to quantity controls, low-stock messaging, and a sticky bag summary. Review also led to explicit response DTOs, null-item validation, consistent order-read snapshots, and cross-customer mutation tests that assert no database changes. I fixed old-order visits clearing a new cart and removed the five-minute polling cutoff. I cut the unfinished external-provider integration because it exceeded the mock-only brief.

With another pass, I would finish browser automation for navigation, mobile layout, and failure recovery, and verify queued callback delivery across a process restart. Those are remaining verification gaps, not claims of completed coverage.

The backend was subsequently refactored into standalone DTOs, service interfaces and implementations, typed domain records, and JDBC repositories. SQL moved out of application services, and approval/recovery transactions moved out of the controller. Scheduler triggers now delegate to a delivery service, and HMAC signing lives in a shared security utility. API payloads and database migrations were preserved; all 31 backend tests passed after a clean build, and catalog/order reads were verified through the frontend proxy.

### Week 2 if this served real shoppers

My first priority would be production operational recovery: connect external alerts for stuck callbacks and review-held inventory and replace the local mock cancellation/refund resolution with real provider reconciliation. Before launch, replace demo identity and secrets and integrate a real provider with its own contract tests; a database transaction cannot atomically include an external HTTP payment operation. Define idempotency retention and monitoring for unresolved outcomes. Then measure stock contention and shopper recovery from changed availability before adding infrastructure. Address collection and delivery handling would be necessary when expanding this slice to actual fulfillment.

## API contract

See [API reference](docs/API.md) for bodies, status codes, authentication, and a failure simulation example. Standalone [request and response DTOs](backend/src/main/java/com/boldfit/checkout/dto) define validation and public JSON contracts. Service interfaces return typed records; controllers do not serialize service results or access the database.

## Verification

Create the isolated test database once (skip if it already exists):

```sh
docker compose exec -T db createdb -U checkout checkout_test
mvn -f backend/pom.xml test
npm --prefix frontend test
npm --prefix frontend run build
```

Tests clear application data only in `checkout_test`. CI runs the backend suite on PostgreSQL, frontend tests and the production build. Local backend tests run on Java 25 targeting Java 17; CI targets Java 17 and Node 22. CI execution remains unverified until pushed. All 29 integration cases and two error-handler unit tests passed after the backend refactor. Frontend coverage consists of five HTTP-client tests and ten Vitest/React Testing Library tests covering cart persistence, storage failures, ambiguous checkout retries, double-submit suppression, stock conflicts, payment confirmation, polling cleanup and fractional money formatting. These DOM/hook tests use jsdom; real-browser layout and complete end-to-end checks remain pending. Callback-delivery recovery across a process restart also remains unverified.

## Research and interview preparation

- [PostgreSQL locking](https://www.postgresql.org/docs/current/explicit-locking.html)
- [Stripe idempotency](https://docs.stripe.com/api/idempotent_requests) and [webhook delivery](https://docs.stripe.com/webhooks) as design references, not our mock's exact contract
- [Product decisions and UX sources](docs/PRODUCT_DECISIONS.md)

This repository contains a local interview demo. Included bearer tokens, webhook signing secret and database password are deliberately non-production defaults; no real payment credentials are required.

### Error handling and local recovery

API errors contain `code`, `message`, `requestId`, and field-level `fields`. The response also includes `X-Request-Id`. Unexpected errors are sanitized; transient database failures return 503 with `Retry-After`. The frontend times out requests after ten seconds, handles invalid/non-JSON responses, preserves checkout retry keys, and retries failed status reads with bounded backoff. Mutations are never automatically retried. Stock conflicts refresh availability without silently changing the bag.

The local `demo-operator` bearer identity can inspect `/api/ops/attention`, execute a human-approved retry action prepared by an internal agent, and resolve payment review through an audited mock refund/cancellation. The retry contract is a versioned [JSON Schema](schemas/retry-failed-payment.schema.json); execution requires recent approval metadata and is recorded in `agent_action_audit`. Customers cannot use these endpoints. Resolution releases inventory once and prevents later callbacks from resurrecting the order. This is a simulated resolution: no real refund occurs. Production requires real authentication, approval identity derived from an identity provider, provider reconciliation, external alerting, and an operator interface.
