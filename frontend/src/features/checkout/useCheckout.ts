import { checkoutApi } from "../../shared/api/checkoutApi";
import { ApiError } from "../../shared/api/client";
import { stableKey, storage } from "../../shared/lib/storage";
import { useMutation } from "../../shared/lib/useMutation";
import type { Cart } from "../cart/cart";

export function useCheckout(
  cart: Cart,
  clear: () => void,
  refreshProducts: () => void,
) {
  const mutation = useMutation();
  const checkout = () =>
    mutation.run(
      async () => {
        const items = Object.entries(cart)
          .map(([skuId, quantity]) => ({ skuId, quantity }))
          .sort((a, b) => a.skuId.localeCompare(b.skuId));
        const scope = `checkout:${JSON.stringify(items)}`;
        const order = await checkoutApi.createOrder(items, stableKey(scope));
        storage.setItem("boldfit-last-order", order.id);
        clear();
        storage.removeItem(scope);
        location.hash = `order/${order.id}`;
      },
      (error) => {
        if (error instanceof ApiError && error.code === "OUT_OF_STOCK")
          refreshProducts();
      },
    );
  return { ...mutation, checkout };
}
