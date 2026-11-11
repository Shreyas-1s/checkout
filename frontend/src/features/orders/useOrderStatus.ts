import { useCallback, useEffect, useState } from "react";
import { checkoutApi } from "../../shared/api/checkoutApi";
import { ApiError } from "../../shared/api/client";
import type { Order } from "../../shared/api/contracts";

export function useOrderStatus(orderId: string) {
  const [order, setOrder] = useState<Order | null>(null);
  const [error, setError] = useState("");
  const [generation, setGeneration] = useState(0);
  const refresh = useCallback(() => setGeneration((value) => value + 1), []);
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    let failures = 0;
    const poll = async () => {
      try {
        const result = await checkoutApi.order(orderId, controller.signal);
        if (controller.signal.aborted) return;
        setOrder(result);
        setError("");
        failures = 0;
        if (result.status === "AWAITING_PAYMENT")
          timer = setTimeout(poll, 3000);
      } catch (error) {
        if (controller.signal.aborted) return;
        const retryable =
          !(error instanceof ApiError) ||
          error.status === 0 ||
          error.status >= 500;
        const again = retryable && failures++ < 5;
        setError(
          (error instanceof Error ? error.message : "Unable to fetch order.") +
            (again
              ? " Reconnecting…"
              : " Automatic refresh stopped. Use Retry to check again."),
        );
        if (again)
          timer = setTimeout(poll, Math.min(15000, 2000 * 2 ** failures));
      }
    };
    void poll();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [orderId, generation]);
  return { order, error, refresh };
}
