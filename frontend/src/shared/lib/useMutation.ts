import { useCallback, useEffect, useRef, useState } from "react";

export function useMutation() {
  const active = useRef(false);
  const mounted = useRef(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  const resetError = useCallback(() => setError(""), []);
  const run = async (
    action: () => Promise<void>,
    onError?: (error: unknown) => void,
  ) => {
    if (active.current) return;
    active.current = true;
    setBusy(true);
    setError("");
    try {
      await action();
    } catch (error) {
      if (mounted.current) {
        setError(
          error instanceof Error
            ? error.message
            : "The request could not be completed.",
        );
        onError?.(error);
      }
    } finally {
      active.current = false;
      if (mounted.current) setBusy(false);
    }
  };
  return { busy, error, resetError, run };
}
