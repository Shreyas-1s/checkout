import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  render,
  renderHook,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useCart } from "../src/features/cart/useCart";
import { parseCart } from "../src/features/cart/cart";
import { useCheckout } from "../src/features/checkout/useCheckout";
import { useOrderStatus } from "../src/features/orders/useOrderStatus";
import OrderPage from "../src/features/orders/OrderPage";
import { checkoutApi } from "../src/shared/api/checkoutApi";
import { ApiError } from "../src/shared/api/client";
import type { Order } from "../src/shared/api/contracts";
import { createStorage, storage } from "../src/shared/lib/storage";
import { money } from "../src/shared/lib/money";

const order: Order = {
  id: "order-one",
  status: "AWAITING_PAYMENT",
  total: 39950,
  currency: "INR",
  expiresAt: "2030-01-01T12:15:00Z",
  lines: [
    {
      skuId: "rope",
      name: "Speed skipping rope",
      quantity: 1,
      unitPrice: 39950,
    },
  ],
  attempts: [],
};
const pending: Order = {
  ...order,
  attempts: [
    {
      id: "attempt-one",
      status: "PENDING",
      submitted: false,
      deliveryState: "NOT_SUBMITTED",
      method: null,
    },
  ],
};

beforeEach(() => {
  localStorage.clear();
  storage.removeItem("boldfit-cart");
  storage.removeItem('checkout:[{"skuId":"rope","quantity":1}]');
  window.location.hash = "shop";
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("cart persistence", () => {
  it("preserves quantity across unmount/remount and removes zero quantities", () => {
    const first = renderHook(() => useCart());
    act(() => first.result.current.change("rope", 2));
    expect(JSON.parse(localStorage.getItem("boldfit-cart")!)).toEqual({
      rope: 2,
    });
    first.unmount();
    const second = renderHook(() => useCart());
    expect(second.result.current.cart).toEqual({ rope: 2 });
    act(() => second.result.current.change("rope", -2));
    expect(second.result.current.count).toBe(0);
  });
  it("rejects corrupted and invalid quantities", () => {
    expect(parseCart("not-json")).toEqual({});
    expect(parseCart("[1,2]")).toEqual({});
    expect(parseCart('{"mat":2,"rope":-1,"bands":11,"bottle":"2"}')).toEqual({
      mat: 2,
    });
  });
  it("keeps working when storage access is denied", () => {
    const fallback = createStorage(() => {
      throw new DOMException("Denied");
    });
    fallback.setItem("cart", "saved");
    expect(fallback.getItem("cart")).toBe("saved");
    fallback.removeItem("cart");
    expect(fallback.getItem("cart")).toBeNull();
  });
});

describe("checkout recovery", () => {
  it("retains the bag and reuses the key after an ambiguous network failure", async () => {
    const create = vi
      .spyOn(checkoutApi, "createOrder")
      .mockRejectedValueOnce(new ApiError("Connection lost"))
      .mockResolvedValueOnce(order);
    const clear = vi.fn();
    const { result } = renderHook(() =>
      useCheckout({ rope: 1 }, clear, vi.fn()),
    );
    await act(() => result.current.checkout());
    expect(clear).not.toHaveBeenCalled();
    expect(result.current.error).toBe("Connection lost");
    await act(() => result.current.checkout());
    expect(create.mock.calls[0][1]).toBe(create.mock.calls[1][1]);
    expect(clear).toHaveBeenCalledTimes(1);
    expect(window.location.hash).toBe("#order/order-one");
  });
  it("blocks concurrent double clicks before React rerenders", async () => {
    let finish!: (value: Order) => void;
    const create = vi.spyOn(checkoutApi, "createOrder").mockReturnValue(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    const { result } = renderHook(() =>
      useCheckout({ rope: 1 }, vi.fn(), vi.fn()),
    );
    await act(async () => {
      const first = result.current.checkout();
      await result.current.checkout();
      finish(order);
      await first;
    });
    expect(create).toHaveBeenCalledTimes(1);
  });
  it("refreshes stale stock without discarding the bag", async () => {
    vi.spyOn(checkoutApi, "createOrder").mockRejectedValue(
      new ApiError("Unavailable", 409, "OUT_OF_STOCK"),
    );
    const clear = vi.fn(),
      refresh = vi.fn();
    const { result } = renderHook(() =>
      useCheckout({ rope: 1 }, clear, refresh),
    );
    await act(() => result.current.checkout());
    expect(refresh).toHaveBeenCalledOnce();
    expect(clear).not.toHaveBeenCalled();
  });
});

describe("order confirmation", () => {
  it("shows processing after Pay and confirms only after the server reports confirmation", async () => {
    let current = pending;
    const read = vi
      .spyOn(checkoutApi, "order")
      .mockImplementation(async () => current);
    const pay = vi.spyOn(checkoutApi, "pay").mockImplementation(async () => {
      current = {
        ...pending,
        attempts: [
          { ...pending.attempts[0], submitted: true, deliveryState: "QUEUED" },
        ],
      };
      return { status: "PROCESSING" };
    });
    storage.setItem("boldfit-cart", '{"mat":2}');
    render(<OrderPage orderId="order-one" />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: /Pay ₹399.5/ }));
    expect(pay).toHaveBeenCalledWith("attempt-one", "UPI");
    expect(await screen.findByText("Confirming your payment")).toBeTruthy();
    expect(screen.queryByText("You're all set.")).toBeNull();
    current = { ...current, status: "CONFIRMED" };
    await user.click(
      screen.getByRole("button", { name: "Refresh order status" }),
    );
    expect(await screen.findByText("You're all set.")).toBeTruthy();
    expect(read.mock.calls.length).toBeGreaterThanOrEqual(3);
    expect(storage.getItem("boldfit-cart")).toBe('{"mat":2}');
  });
  it("polls pending orders, then stops after confirmation", async () => {
    vi.useFakeTimers();
    const read = vi
      .spyOn(checkoutApi, "order")
      .mockResolvedValueOnce(pending)
      .mockResolvedValue({ ...pending, status: "CONFIRMED" });
    const { result } = renderHook(() => useOrderStatus("order-one"));
    await act(async () => {});
    expect(result.current.order?.status).toBe("AWAITING_PAYMENT");
    await act(() => vi.advanceTimersByTimeAsync(3000));
    expect(result.current.order?.status).toBe("CONFIRMED");
    await act(() => vi.advanceTimersByTimeAsync(15000));
    expect(read).toHaveBeenCalledTimes(2);
  });
  it("aborts a pending status request when the order page unmounts", async () => {
    let signal: AbortSignal | undefined;
    vi.spyOn(checkoutApi, "order").mockImplementation((_id, value) => {
      signal = value;
      return new Promise(() => {});
    });
    const { unmount } = renderHook(() => useOrderStatus("order-one"));
    unmount();
    expect(signal?.aborted).toBe(true);
  });
});

it("shows fractional rupees without rounding away paise", () => {
  expect(money(39950)).toBe("₹399.50");
  expect(money(39901)).toBe("₹399.01");
  expect(money(39900)).toBe("₹399");
});
