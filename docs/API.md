# API contract

Base URL: `http://127.0.0.1:8081`. JSON requests and responses. Public catalog; other `/api` endpoints require `Authorization: Bearer demo-alex` or `Bearer demo-sam`. Identity comes from the token, never a customer ID in the body. Foreign and unknown resources both return `404`. The webhook uses provider HMAC, not customer bearer auth.

| Method/path | Input | Success |
| --- | --- | --- |
| GET `/api/products` | None | 200, array of product DTOs |
| POST `/api/orders` | `{"items":[{"skuId":"mat","quantity":1}]}` + `Idempotency-Key` | 201, order DTO and Location |
| GET `/api/orders/{id}` | None | 200, owned order with line snapshots and payment attempts |
| POST `/api/orders/{id}/payment-attempts` | `Idempotency-Key` | 201, `{id,orderId,status}` |
| POST `/api/payments/{attemptId}/pay` | `{"method":"UPI"}` (`CARD`, `NETBANKING` also accepted) | 202, `{"status":"PROCESSING"}` |
| POST `/api/demo/payments/{attemptId}/resolve` | `{"outcome":"FAILED"}` (`PARTIAL`, `SUCCEEDED` also accepted) | 202, message; development-only mock control |
| POST `/webhooks/payment` | Signed provider event | 200, `{"status":"received"}` |

Keys are 8–100 ASCII letters/digits/underscores/hyphens; UUIDs are suitable. Reuse a key for retries of the same mutation. Reusing it with changed input returns `409`. Replay returns the original creation response, so read the order endpoint for current status. Quantities are 1–10 per SKU after duplicate SKU normalization, with at most 20 submitted lines. Prices are server-derived.

Errors use `{code,message,requestId,fields}` with an `X-Request-Id` header: `400` validation, `401` authentication/signature, `404` missing/not-owned resource, `409` stock/state/idempotency conflict, `503` retryable database contention. For `503`, retry with the same key after `Retry-After`.

## Demo failure walkthrough

Create an order and attempt as Alex through the UI. Before clicking Pay, use the attempt UUID from the order response:

```sh
curl -X POST http://127.0.0.1:8081/api/demo/payments/ATTEMPT_ID/resolve \
  -H 'Authorization: Bearer demo-alex' \
  -H 'Content-Type: application/json' \
  -d '{"outcome":"FAILED"}'
```

The provider queues a signed callback; the UI then permits another attempt. Use `PARTIAL` with a fresh pending attempt to demonstrate review. An outcome is fixed after the first submission: changing it is rejected, repeating it requests redelivery. The customer Pay endpoint always selects success.

## Webhook contract

Headers: `X-Payment-Timestamp` (Unix seconds), `X-Payment-Signature` (hex HMAC-SHA256).

Signed bytes: `timestamp + "." + raw JSON body`, using `app.webhook-secret`. The body contains `eventId`, `attemptId`, `orderId` (UUIDs), `outcome` (`SUCCEEDED`/`FAILED`), `amount` (integer paise), and `currency` (`INR`). The supplied amount and currency must match the order or the order enters review. Signature verification precedes parsing and deduplication. A duplicate authentic event returns 200 without repeating its effects.

Our mock contract fixes one terminal outcome per payment attempt. Contradictory events go to review. Real providers have different lifecycle and signature rules; swapping providers requires an adapter and contract tests.

## Local operator recovery

Requires `Authorization: Bearer demo-operator` and demo mode enabled. Customer identities receive 403.

- `GET /api/ops/attention`: review orders and exhausted callbacks.
- `POST /api/ops/agent-actions/retry-failed-payment`: validate a human-approved internal-agent action, reset an exhausted delivery, and queue the same signed event. The contract is defined by [`schemas/retry-failed-payment.schema.json`](../schemas/retry-failed-payment.schema.json). Missing approval is rejected, `actionId` makes execution idempotent, and reuse with different content returns 409.
- `POST /api/ops/orders/{orderId}/resolve-review`, body `{"reason":"Mock refund recorded"}`: audit a simulated refund and cancel a review order, releasing reserved stock once. Repeating resolution is harmless; later signed callbacks cannot confirm the cancelled order. No actual money moves.

Callback delivery stops after five failures (backoff 5, 10, 20, 40 seconds). Order attempts expose `deliveryState`: NOT_SUBMITTED, QUEUED, DELIVERED, or EXHAUSTED. Exhaustion is logged and requires operator recovery. Invalid JSON is 400, unsupported method 405, unsupported media 415, and unexpected exceptions return sanitized 500 errors. An unacceptable response format returns empty 406.

The agent-action body is deliberately plain JSON:

```json
{
  "actionId": "b77e1a51-1ab6-43d0-89a6-591ea7360674",
  "action": "RETRY_FAILED_PAYMENT",
  "orderId": "4f44cbbd-3802-4b1f-bde5-daca82e0f244",
  "paymentAttemptId": "6cf73db1-cb9a-45e1-ad0b-25c2341b7cd5",
  "reason": "Webhook delivery exhausted after provider success",
  "approvalStatus": "APPROVED",
  "approvedBy": "ops-lead@boldfit.test",
  "approvedAt": "2026-11-11T04:30:00Z"
}
```

The internal agent may prepare this object but cannot supply a useful unapproved action: execution requires the operator bearer identity, an explicit `APPROVED` decision, a named approver, and approval from the preceding 24 hours. Every successful execution records the approver, executor, reason, and timestamps in `agent_action_audit`. This is a demo approval boundary; production would derive approver identity from the organization identity provider rather than trusting a body field.
