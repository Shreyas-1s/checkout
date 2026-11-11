export type Cart = Record<string, number>;
export function parseCart(raw: string | null): Cart {
  try {
    const value: unknown = JSON.parse(raw || "null");
    if (!value || typeof value !== "object" || Array.isArray(value)) return {};
    return Object.fromEntries(
      Object.entries(value).filter(
        ([, quantity]) =>
          Number.isInteger(quantity) &&
          Number(quantity) > 0 &&
          Number(quantity) <= 10,
      ),
    );
  } catch {
    return {};
  }
}
export function changeQuantity(cart: Cart, id: string, delta: number): Cart {
  const next = {
    ...cart,
    [id]: Math.max(0, Math.min(10, (cart[id] || 0) + delta)),
  };
  if (!next[id]) delete next[id];
  return next;
}
