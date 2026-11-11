# Backend structure

The backend uses explicit JDBC with layered Spring components. The original thin slice grouped request records inside controllers and SQL inside application services. This pass separates those concerns while preserving HTTP contracts and database transactions.

```text
com.boldfit.checkout
├── controller     HTTP routes, request validation, response status codes
├── dto            Standalone immutable request/response records
├── service        CheckoutService, RecoveryService, PaymentDeliveryService interfaces
│   └── impl       Use cases, state transitions, transaction boundaries
├── repository     SQL, row mapping, conditional writes and row locks
├── domain         Typed persistence snapshots and audit/delivery records
├── security       Demo identity, request references and HMAC signing
├── exception      Domain failures and centralized HTTP error mapping
└── job            Scheduled triggers delegating to services
```

Controllers depend on service interfaces. Service implementations coordinate repositories. Repositories return typed records rather than JDBC maps, and application service methods return typed DTOs rather than serialized JSON. Spring constructor injection supplies the implementations. HTTP details stay in controllers and filters; SQL stays in repositories. Payment delivery shares a security signing utility with the webhook adapter rather than depending on a controller.

## Transaction ownership

- `CheckoutServiceImpl.checkout`: one transaction covers the idempotency claim, stock reservation, order lines and saved response. Conditional stock updates and deterministic SKU ordering are unchanged.
- `CheckoutServiceImpl.attempt`: ownership is checked even on replay; a locked order protects attempt creation.
- `CheckoutServiceImpl.receive`: webhook deduplication and order/payment transitions commit together under the order lock.
- `CheckoutServiceImpl.pay`: fixed mock outcome and selected method persist together. Its call to `simulate` stays inside the outer transaction; it does not rely on a self-invoked annotation starting another transaction.
- `CheckoutServiceImpl.order`: external order reads retain repeatable-read isolation for a consistent order/lines/attempts snapshot. Calls from checkout participate in the existing write transaction.
- `RecoveryServiceImpl.retryFailedPayment`: approval audit and delivery requeue share a transaction. Review resolution delegates through the checkout service proxy to its transactional operation.
- `PaymentDeliveryServiceImpl`: network calls remain outside database transactions. It persists delivery acknowledgement or retry metadata after each attempt. The scheduler itself only invokes services.

## Why interfaces and JDBC

Service interfaces make application capabilities explicit and keep HTTP adapters independent of implementation classes. Concrete repositories own the PostgreSQL-specific SQL; another interface per repository would add indirection without a second persistence implementation. Immutable records model stored data without introducing JPA entities. JDBC makes conditional updates and locking visible, which is useful for the stock and payment guarantees being demonstrated.

## Verification

The PostgreSQL integration suite exercises the service interfaces and HTTP endpoints, including stock races, duplicate checkout, replayed webhooks, expiry races, cross-customer mutations and approved recovery. HTTP paths, payloads and schema migrations are unchanged by this refactor. Frontend changes are not required.
