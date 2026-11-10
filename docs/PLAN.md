# Initial delivery plan

Historical planning notes. For implemented behavior, run instructions, and verification limits, see the root README and API reference.

## Scope and scoring

The supplied assignment recommends 4–6 hours and scores API correctness (30%), checkout/payment failures (25%), design decisions (20%), UI (15%), and code/README (10%). Use this as a scope budget, not a promise that all work can be completed in six hours. Learning discussions may take additional time.

The candidate requests Java/Spring, a private GitHub repository, deliberate research, and interview readiness. The PDF is a requirements reference, not authorization to send email or publish publicly. No submission action is planned.

## Milestones

| Order | Work | Done when | Interview checkpoint |
| --- | --- | --- | --- |
| 1 | Contract and lifecycle, about 45 min | API/error shapes, state transitions, stock and late-event policies written; versions selected | Explain which component is authoritative for price, stock, and payment |
| 2 | Project foundation, about 30 min | Spring + React scaffold, PostgreSQL/migrations, five SKUs, demo identity, repeatable startup; private GitHub remote created and verified | Explain why one app and one database are enough |
| 3 | Cart to checkout, about 75 min | Server prices, immutable order lines, atomic stock reservation, durable checkout idempotency; stock-race and retry tests pass | Explain the transaction boundary and lost-response retry |
| 4 | Payment and webhook, about 90 min | Intent/attempt API, HTTP mock callback, signature/freshness/deduplication, lifecycle guards; failure tests pass | Explain duplicate versus replay and pending versus failed |
| 5 | Customer UI, about 60 min | Catalog/cart/checkout/status flow; loading/error/empty states; pending and failure recovery; polling cleanup | Explain why browser success is insufficient |
| 6 | Evidence and handoff, about 60 min | Clean startup verified, focused integration tests and UI smoke check, concise README with code links, demo script, private push verified | Walk through one race and one rejected alternative |

Implement a complete thin happy path before adding secondary features. Do not spend the timebox on admin screens, elaborate catalog/search, full authentication, deployment infrastructure, or the optional agent schema. Optional work comes after core evidence.

## Proposed topology

React -> Spring REST API -> PostgreSQL.

A small mock provider adapter/simulator runs in the same application but delivers a real signed HTTP callback after the payment-attempt transaction commits. Its demo controls must be explicitly local/demo-only. The webhook handler must not be bypassed by a direct order update. Document that this mock boundary does not demonstrate atomicity across a real external provider and our database.

Prefer packages by capability: catalog, checkout/orders, payments, and shared API/security. Avoid a generic framework or microservices.

## Contract decisions to settle before coding

- Browser-local cart for this scope; checkout accepts SKU IDs and quantities, never trusted prices. Normalize duplicate SKUs, validate positive bounded quantities, and reject unknown SKUs.
- Proposed API: `GET /api/products`, `POST /api/orders` (checkout), `GET /api/orders/{id}`, `POST /api/orders/{id}/payment-attempts`, and required `POST /webhooks/payment`.
- Checkout and attempt creation require `Idempotency-Key`. Concurrent duplicate keys must converge on the same committed response; key reuse with different input returns `409`. New keys are not universal duplicate-purchase detection.
- First creation returns `201` and Location; replay returns the stored response. Validation uses `400`, unauthenticated requests `401`, stock/state conflicts `409`, and missing or other-customer orders `404`.
- Payment callbacks use HMAC-SHA256 over delivery timestamp plus exact raw body, constant-time signature comparison, and bounded clock skew. Event ID belongs inside the signed body. Authenticate before deduplicating. Legitimate redelivery uses the same event ID with a fresh delivery signature.
- Lock order/attempt and atomically record event consumption with its effects. A duplicate committed event receives success; a transient transaction failure must remain retryable. Also guard business transitions against semantically duplicate events with different IDs.
- Validate provider attempt ID, order linkage, amount, and currency. A signed payload is authentic but can still violate the business contract.
- Proposed order states: AWAITING_PAYMENT, CONFIRMED, EXPIRED, PAYMENT_REVIEW. Proposed attempt states: PENDING, SUCCEEDED, FAILED, REVIEW. Only permit a new attempt after definitive failure; a timeout is not definitive failure.
- Partial/overpayment or contradictory terminal results require review, never automatic confirmation or a blind retry. Define stock ownership in review explicitly.
- Reserve stock at checkout in a short transaction; lock SKUs in a consistent order. Proposed 15-minute hold. Expiry and confirmation must contend on the same order lock, and stock release must happen once. A late success after release must enter review rather than confirm unavailable inventory. Manual reconciliation/refunds remain out of scope but must be visible as limitations.
- Keep mock terminal outcomes immutable per attempt. Define how success/failure events from older attempts are handled; never allow an old failure to undo confirmation.
- Use integer minor units with checked arithmetic and snapshot currency/unit prices. Declare shipping/tax assumptions; no invented tax engine.
- Choose supported Spring/JDK/Node releases after checking official compatibility and reproducible tooling. Environment initially has Java 17, Node 23.5.0, and Docker CLI; Docker daemon availability is not yet verified.

## Tests worth spending time on

Use real PostgreSQL integration tests (prefer Testcontainers if Docker works) for locking and uniqueness, not an H2 substitute for concurrency behavior.

1. Two customers race for the last unit: one order/reservation wins, stock stays nonnegative.
2. Concurrent same-key checkout and payment requests: one business effect; same response. Changed payload conflicts. Retry after lost response and after app restart remains safe.
3. Invalid signature, modified bytes, missing/stale/future timestamp: no mutation.
4. Valid event delivered twice, including concurrently: one transition and one inventory effect.
5. Different event IDs describing the same success: no repeated effect.
6. Wrong amount/currency or partial payment: no confirmation, clear review outcome.
7. Failed attempt then successful retry; old events cannot regress order state.
8. Confirmation races expiry; late success after release cannot fulfill unreserved stock.
9. Customer A cannot read or mutate customer B's order.
10. End-to-end happy path: UI stays pending until the signed callback commits, then shows confirmed after polling.

## Learning workflow

For each milestone, record the problem, the smallest viable choice, the rejected alternative, a counterexample, and the test proving the invariant. Explain the decision before implementing it, then walk through actual code and evidence. Keep the final README short; retain deeper working notes here.
