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

Expected errors use `{code,message}`: `400` validation, `401` authentication/signature, `404` missing/not-owned resource, `409` stock/state/idempotency conflict, `503` retryable database contention. For `503`, retry with the same key after `Retry-After`.

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
