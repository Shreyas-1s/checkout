import { useState } from "react";
import { checkoutApi } from "../../shared/api/checkoutApi";
import type { Order, PaymentMethod } from "../../shared/api/contracts";
import { stableKey } from "../../shared/lib/storage";
import { useMutation } from "../../shared/lib/useMutation";

export function usePayment(order: Order | null, refresh: () => void) {
  const mutation = useMutation();
  const [notice, setNotice] = useState("");
  const pay = () =>
    mutation.run(async () => {
      if (!order) return;
      setNotice("");
      const last = order.attempts.at(-1);
      await checkoutApi.createAttempt(
        order.id,
        stableKey(`payment:${order.id}:${last?.id || "first"}`),
      );
      refresh();
    });
  const submitPayment = (method: PaymentMethod) =>
    mutation.run(async () => {
      const attempt = order?.attempts.at(-1);
      if (!attempt) return;
      await checkoutApi.pay(attempt.id, method);
      setNotice("Payment submitted. Waiting for confirmation…");
      refresh();
    });
  return { ...mutation, notice, pay, submitPayment };
}
