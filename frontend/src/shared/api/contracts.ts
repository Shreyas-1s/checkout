export type PaymentMethod = "UPI" | "CARD" | "NETBANKING";
export type OrderStatus =
  "AWAITING_PAYMENT" | "CONFIRMED" | "EXPIRED" | "PAYMENT_REVIEW" | "CANCELLED";
export type AttemptStatus = "PENDING" | "SUCCEEDED" | "FAILED" | "REVIEW";
export type DeliveryState =
  "NOT_SUBMITTED" | "QUEUED" | "DELIVERED" | "EXHAUSTED";
export type Product = {
  id: string;
  name: string;
  category: string;
  description: string;
  price: number;
  available: number;
};
export type CartItem = { skuId: string; quantity: number };
export type Attempt = {
  id: string;
  status: AttemptStatus;
  submitted: boolean;
  deliveryState: DeliveryState;
  method: PaymentMethod | null;
};
export type OrderLine = CartItem & { name: string; unitPrice: number };
export type Order = {
  id: string;
  status: OrderStatus;
  total: number;
  currency: string;
  expiresAt: string;
  lines: OrderLine[];
  attempts: Attempt[];
};
export type CreatedAttempt = {
  id: string;
  orderId: string;
  status: AttemptStatus;
};
