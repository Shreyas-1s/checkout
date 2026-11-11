import { useCallback, useEffect, useState } from "react";
import { checkoutApi } from "../../shared/api/checkoutApi";
import type { Product } from "../../shared/api/contracts";

export function useCatalog(route: string) {
  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [generation, setGeneration] = useState(0);
  const refresh = useCallback(() => setGeneration((value) => value + 1), []);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    checkoutApi
      .products(controller.signal)
      .then((result) => {
        if (!controller.signal.aborted) setProducts(result);
      })
      .catch((error) => {
        if (!controller.signal.aborted) setError(error.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [route, generation]);
  useEffect(() => {
    const focus = () => {
      if (document.visibilityState === "visible") refresh();
    };
    window.addEventListener("focus", focus);
    return () => window.removeEventListener("focus", focus);
  }, [refresh]);
  return { products, loading, error, refresh };
}
