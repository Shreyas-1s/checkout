import { useCallback, useEffect, useState } from "react";
import { storage } from "../../shared/lib/storage";
import { changeQuantity, parseCart } from "./cart";

export function useCart() {
  const [cart, setCart] = useState(() =>
    parseCart(storage.getItem("boldfit-cart")),
  );
  useEffect(
    () => storage.setItem("boldfit-cart", JSON.stringify(cart)),
    [cart],
  );
  const change = useCallback(
    (id: string, delta: number) =>
      setCart((previous) => changeQuantity(previous, id, delta)),
    [],
  );
  const clear = useCallback(() => setCart({}), []);
  return {
    cart,
    change,
    clear,
    count: Object.values(cart).reduce((sum, quantity) => sum + quantity, 0),
  };
}
