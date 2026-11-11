# Frontend architecture

React, TypeScript (strict mode), and Vite remain the application stack. The original root component mixed pages, storage, API calls and polling. Features now own their UI and behavior; the root composes them.

```text
src/
├── app/                  Application shell and hash routing
├── features/
│   ├── catalog/          Catalog page, stock labels, product-loading hook
│   ├── cart/             Quantity rules and persistent cart hook
│   ├── checkout/         Checkout page and idempotent submission hook
│   ├── payment/          FitPay component and payment actions
│   └── orders/           Order page and lifecycle-aware status polling
└── shared/
    ├── api/              HTTP client, typed contracts, named API operations
    ├── components/       Product artwork and error display
    └── lib/              Storage, money formatting and mutation state
```

## Data ownership

- `useCart` owns browser-persisted SKU quantities. Derived counts and totals are computed rather than duplicated in state. The server still owns pricing, stock and reservation decisions.
- `useCatalog` owns catalog loading and refresh. It cancels obsolete requests on navigation and unmount.
- `useCheckout` retains the same idempotency key after an ambiguous failure and clears the bag only after order creation is acknowledged. A stock conflict refreshes availability while preserving quantities.
- `useOrderStatus` polls every three seconds while awaiting payment, retries read failures with bounded backoff, and stops polling on terminal states. Unmount/refresh cancels the previous request so old results cannot overwrite a newer page.
- `usePayment` creates attempts and submits methods. Acknowledging submission shows processing; only the server's order status shows confirmation.
- `useMutation` blocks overlapping submissions within a mounted hook. Server idempotency remains the cross-client guarantee.

## Decisions and limits

Feature folders and custom hooks separate responsibilities without adding a global state library. Cart state is owned by the app and passed to the pages that need it; order state belongs to its page. Existing hash URLs are preserved. A routing library or server-state cache can be introduced if the application grows; this slice does not need them to demonstrate checkout correctly.

Contracts use explicit status unions and money stays in integer paise until display. The formatter preserves fractional rupees, for example `39950` displays as `₹399.50`. TypeScript contracts are compile-time checks, not runtime schema validation of server responses.

Storage falls back to memory when browser storage is unavailable. That fallback lasts only for the current tab. There is no cross-device cart or order-history page. Opening a completed order does not clear a new cart or its checkout retry keys.

## Tests

`npm test` runs the HTTP-client tests followed by Vitest/React Testing Library tests in jsdom. Coverage includes cart remount persistence, corrupt storage, unavailable storage, ambiguous checkout retry, double-submit suppression, stock conflicts, payment processing versus server confirmation, polling completion, request cancellation and fractional currency formatting. These are DOM/hook tests; real-browser layout and end-to-end coverage remain separate verification work.
