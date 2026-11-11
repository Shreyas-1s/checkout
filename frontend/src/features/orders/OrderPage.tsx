import {
  ArrowRight,
  CheckCircle2,
  ChevronLeft,
  Clock3,
  RefreshCw,
  ShieldCheck,
} from "lucide-react";
import type { Order, PaymentMethod } from "../../shared/api/contracts";
import ProductArt from "../../shared/components/ProductArt";
import { money } from "../../shared/lib/money";

import ErrorNotice from "../../shared/components/ErrorNotice";
import MockPayment from "../payment/MockPayment";
import { usePayment } from "../payment/usePayment";
import { useOrderStatus } from "./useOrderStatus";
export default function OrderPage({ orderId }: { orderId: string }) {
  const status = useOrderStatus(orderId);
  const payment = usePayment(status.order, status.refresh);
  return (
    <>
      <OrderView
        order={status.order}
        statusError={status.error}
        busy={payment.busy}
        notice={payment.notice}
        pay={payment.pay}
        submitPayment={payment.submitPayment}
        refresh={status.refresh}
      />
      <ErrorNotice
        message={payment.error || status.error}
        retry={() => {
          payment.resetError();
          status.refresh();
        }}
      />
    </>
  );
}
type Props = {
  order: Order | null;
  statusError: string;
  busy: boolean;
  notice: string;
  pay: () => void;
  submitPayment: (method: PaymentMethod) => void;
  refresh: () => void;
};
export function OrderView({
  order,
  statusError,
  busy,
  notice,
  pay,
  submitPayment,
  refresh,
}: Props) {
  const last = order?.attempts.at(-1),
    pending = last?.status === "PENDING";
  return (
    <section className="checkout-page">
      <a className="back" href="#shop">
        <ChevronLeft size={16} /> Back to essentials
      </a>
      <div className="section-head">
        <div className="eyebrow">YOUR NEXT MOVE</div>
        <h1>Good things are on the way.</h1>
      </div>
      {!order ? (
        <div className="empty">
          {statusError
            ? "Order status is unavailable. Your order has not been discarded."
            : "Loading your order…"}
        </div>
      ) : (
        <div className="checkout-grid">
          <div className="status-card">
            <div
              className={`status-icon ${order.status === "CONFIRMED" ? "success" : ""}`}
            >
              {order.status === "CONFIRMED" ? (
                <CheckCircle2 size={32} />
              ) : (
                <Clock3 size={32} />
              )}
            </div>
            <div className="eyebrow">
              ORDER {order.id.slice(0, 8).toUpperCase()}
            </div>
            <h2>
              {
                {
                  CANCELLED: "Your order has been cancelled.",
                  CONFIRMED: "You're all set.",
                  EXPIRED: "Your reservation expired.",
                  PAYMENT_REVIEW: "Your payment needs a closer look.",
                  AWAITING_PAYMENT: pending
                    ? last?.submitted || !!notice
                      ? "Awaiting confirmation."
                      : "Choose how you’d like to pay."
                    : last?.status === "FAILED"
                      ? "Let’s give that another try."
                      : "One step from your next move.",
                }[order.status]
              }
            </h2>
            <p>
              {order.status === "CONFIRMED"
                ? "Payment confirmed. Your essentials are officially yours."
                : order.status === "CANCELLED"
                  ? "The demo payment review is resolved and your reservation has been released. No real money moved."
                  : order.status === "EXPIRED"
                    ? "Your items have been released. Start a fresh bag to check availability."
                    : order.status === "PAYMENT_REVIEW"
                      ? "We could not safely confirm this payment. Please do not pay again; this order needs manual review."
                      : pending
                        ? last?.submitted || !!notice
                          ? "Your payment is processing. Your order updates once confirmation arrives."
                          : "Select a demo payment method below to complete your checkout."
                        : last?.status === "FAILED"
                          ? "The payment was declined. Your items are still reserved until the time below."
                          : "Your items are reserved for 15 minutes. Continue to payment to complete your order."}
            </p>
            {order.status === "AWAITING_PAYMENT" && (
              <>
                <div className="reservation">
                  <Clock3 size={16} /> Reserved until{" "}
                  {new Date(order.expiresAt).toLocaleTimeString("en-IN", {
                    hour: "2-digit",
                    minute: "2-digit",
                  })}
                </div>
                {!pending ? (
                  <button className="primary" onClick={pay} disabled={busy}>
                    {busy
                      ? "Preparing payment…"
                      : last
                        ? "Try payment again"
                        : "Continue to payment"}
                    <ArrowRight size={18} />
                  </button>
                ) : (
                  <MockPayment
                    amount={money(order.total)}
                    busy={busy}
                    submitted={!!last?.submitted || !!notice}
                    exhausted={last?.deliveryState === "EXHAUSTED"}
                    onPay={submitPayment}
                  />
                )}
              </>
            )}
            {notice && order.status === "AWAITING_PAYMENT" && (
              <p role="status" className="notice">
                {notice}
              </p>
            )}
            {order.status !== "AWAITING_PAYMENT" && (
              <a className="primary" href="#shop">
                Back to the collection <ArrowRight size={18} />
              </a>
            )}
            <button className="refresh" onClick={refresh} disabled={busy}>
              <RefreshCw size={14} /> Refresh order status
            </button>
          </div>
          <aside className="summary">
            <div className="eyebrow">YOUR ESSENTIALS</div>
            {order.lines.map((l) => (
              <div className="summary-line" key={l.skuId}>
                <ProductArt id={l.skuId} />
                <div>
                  <strong>{l.name}</strong>
                  <small>Qty {l.quantity}</small>
                </div>
                <span>{money(l.unitPrice * l.quantity)}</span>
              </div>
            ))}
            <div className="total">
              <span>Total</span>
              <strong>{money(order.total)}</strong>
            </div>
            <div className="trust">
              <ShieldCheck size={17} /> Payment status verified by our server
            </div>
          </aside>
        </div>
      )}
    </section>
  );
}
