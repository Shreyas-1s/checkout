import { useState } from "react";
import {
  ArrowRight,
  Check,
  CreditCard,
  Landmark,
  LoaderCircle,
  LockKeyhole,
  Smartphone,
} from "lucide-react";

export type PaymentMethod = "UPI" | "CARD" | "NETBANKING";
type Props = {
  amount: string;
  busy: boolean;
  submitted: boolean;
  onPay: (method: PaymentMethod) => void;
};
const methods = [
  {
    id: "UPI" as const,
    label: "UPI",
    description: "Pay with a demo UPI account",
    icon: Smartphone,
  },
  {
    id: "CARD" as const,
    label: "Debit / Credit card",
    description: "Use a saved demo card",
    icon: CreditCard,
  },
  {
    id: "NETBANKING" as const,
    label: "Netbanking",
    description: "Pay from a demo bank account",
    icon: Landmark,
  },
];
export default function MockPayment({ amount, busy, submitted, onPay }: Props) {
  const [method, setMethod] = useState<PaymentMethod>("UPI");
  return (
    <section className="payment-provider" aria-label="Mock payment provider">
      <div className="provider-heading">
        <div>
          <span className="provider-mark">
            <LockKeyhole size={16} />
          </span>
          <strong>FitPay</strong>
        </div>
        <span className="test-badge">DEMO MODE</span>
      </div>
      {submitted ? (
        <div className="payment-processing" role="status">
          <LoaderCircle className="payment-spinner" size={30} />
          <h3>Confirming your payment</h3>
          <p>
            Your payment was submitted. You can leave this page and return to
            your order. Please don’t pay again.
          </p>
          <div className="payment-progress">
            <span>
              <Check size={14} /> Payment submitted
            </span>
            <span>
              <LoaderCircle size={14} /> Waiting for confirmation
            </span>
          </div>
        </div>
      ) : (
        <>
          <div className="provider-amount">
            <div>
              <small>PAYING BOLDFIT</small>
              <strong>{amount}</strong>
            </div>
            <LockKeyhole size={21} />
          </div>
          <fieldset className="payment-methods" disabled={busy}>
            <legend>Choose how to pay</legend>
            {methods.map(({ id, label, description, icon: Icon }) => (
              <label
                key={id}
                className={`payment-method ${method === id ? "selected" : ""}`}
              >
                <input
                  type="radio"
                  name="payment-method"
                  value={id}
                  checked={method === id}
                  onChange={() => setMethod(id)}
                />
                <Icon size={21} />
                <span>
                  <strong>{label}</strong>
                  <small>{description}</small>
                </span>
                <span className="method-radio" aria-hidden="true">
                  {method === id && <span />}
                </span>
              </label>
            ))}
          </fieldset>
          <div className="demo-instrument">
            {method === "UPI" ? (
              <>
                <Smartphone size={20} />
                <div>
                  <strong>Demo UPI account</strong>
                  <span>shopper@fitpay.test</span>
                </div>
              </>
            ) : method === "CARD" ? (
              <>
                <CreditCard size={20} />
                <div>
                  <strong>Demo card •••• 4242</strong>
                  <span>Test customer · No card details required</span>
                </div>
              </>
            ) : (
              <>
                <Landmark size={20} />
                <div>
                  <strong>FitPay Demo Bank</strong>
                  <span>Test savings account •••• 0001</span>
                </div>
              </>
            )}
            <Check size={17} />
          </div>
          <button
            className="primary"
            disabled={busy}
            onClick={() => onPay(method)}
          >
            {busy ? "Submitting payment…" : `Pay ${amount}`}{" "}
            {busy ? (
              <LoaderCircle className="payment-spinner" size={18} />
            ) : (
              <ArrowRight size={18} />
            )}
          </button>
        </>
      )}
      <p className="provider-disclaimer">
        FitPay is a local mock provider. No money moves and no real payment
        details are collected.
      </p>
    </section>
  );
}
