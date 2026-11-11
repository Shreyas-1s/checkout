// Initial merchandising threshold, not a proven conversion optimum.
export const LOW_STOCK_THRESHOLD = 5;
export const stockMessage = (available: number) =>
  available <= 0
    ? "Currently sold out"
    : available <= LOW_STOCK_THRESHOLD
      ? "Only a few left"
      : "In stock";
