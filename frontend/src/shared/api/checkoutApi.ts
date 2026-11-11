import { api } from "./client";
import type {
  CartItem,
  CreatedAttempt,
  Order,
  PaymentMethod,
  Product,
} from "./contracts";

export const checkoutApi = {
  products: (signal?: AbortSignal) =>
    api<Product[]>("/api/products", { signal }),
  order: (id: string, signal?: AbortSignal) =>
    api<Order>(`/api/orders/${encodeURIComponent(id)}`, { signal }),
  createOrder: (items: CartItem[], key: string) =>
    api<Order>("/api/orders", {
      method: "POST",
      headers: { "Idempotency-Key": key },
      body: JSON.stringify({ items }),
    }),
  createAttempt: (orderId: string, key: string) =>
    api<CreatedAttempt>(
      `/api/orders/${encodeURIComponent(orderId)}/payment-attempts`,
      { method: "POST", headers: { "Idempotency-Key": key } },
    ),
  pay: (attemptId: string, method: PaymentMethod) =>
    api<{ status: "PROCESSING" }>(
      `/api/payments/${encodeURIComponent(attemptId)}/pay`,
      { method: "POST", body: JSON.stringify({ method }) },
    ),
};
