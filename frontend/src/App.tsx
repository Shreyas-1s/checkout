import ProductArt from "./ProductArt";
import MockPayment, { type PaymentMethod } from "./MockPayment";
import { useEffect, useState } from "react";
import {
  ArrowRight,
  ArrowUpRight,
  CheckCircle2,
  ChevronLeft,
  Clock3,
  Dumbbell,
  Leaf,
  LockKeyhole,
  Minus,
  Plus,
  RefreshCw,
  ShieldCheck,
  ShoppingBag,
  Truck,
  X,
} from "lucide-react";

type Product = {
  id: string;
  name: string;
  category: string;
  description: string;
  price: number;
  available: number;
};
type Cart = Record<string, number>;
type Attempt = {
  id: string;
  status: string;
  submitted: boolean;
  method: PaymentMethod | null;
};
type Order = {
  id: string;
  status: string;
  total: number;
  currency: string;
  expiresAt: string;
  lines: { skuId: string; name: string; quantity: number; unitPrice: number }[];
  attempts: Attempt[];
};
// Initial merchandising threshold, not a proven conversion optimum.
const LOW_STOCK_THRESHOLD = 5;
const stockMessage = (available: number) =>
  available <= 0
    ? "Currently sold out"
    : available <= LOW_STOCK_THRESHOLD
      ? "Only a few left"
      : "In stock";
const money = (paise: number) =>
  new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 0,
  }).format(paise / 100);
function stored<T>(key: string, fallback: T): T {
  try {
    return JSON.parse(localStorage.getItem(key) || "null") ?? fallback;
  } catch {
    return fallback;
  }
}
function stableKey(scope: string) {
  const key = localStorage.getItem(scope) || crypto.randomUUID();
  localStorage.setItem(scope, key);
  return key;
}
async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer demo-alex",
      ...init?.headers,
    },
  });
  if (!response.headers.get("content-type")?.includes("application/json"))
    throw new Error(
      "The checkout service is unavailable. Please retry in a moment.",
    );
  const body = await response.json();
  if (!response.ok)
    throw new Error(body.message || "Something went wrong. Please try again.");
  return body;
}
export default function App() {
  const [products, setProducts] = useState<Product[]>([]),
    [loading, setLoading] = useState(true),
    [error, setError] = useState("");
  const [cart, setCart] = useState<Cart>(() => stored("boldfit-cart", {}));
  const [route, setRoute] = useState(location.hash.slice(1) || "shop");
  const [order, setOrder] = useState<Order | null>(null),
    [busy, setBusy] = useState(false),
    [notice, setNotice] = useState("");
  const [pollGeneration, setPollGeneration] = useState(0);
  useEffect(() => {
    const change = () => {
      setRoute(location.hash.slice(1) || "shop");
      setError("");
      setNotice("");
    };
    window.addEventListener("hashchange", change);
    return () => window.removeEventListener("hashchange", change);
  }, []);
  const loadProducts = () => {
    setLoading(true);
    setError("");
    api<Product[]>("/api/products")
      .then(setProducts)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  };
  useEffect(loadProducts, []);
  useEffect(() => {
    const refresh = () => {
      if (document.visibilityState === "visible")
        api<Product[]>("/api/products")
          .then(setProducts)
          .catch(() => {});
    };
    refresh();
    window.addEventListener("focus", refresh);
    return () => window.removeEventListener("focus", refresh);
  }, [route]);
  useEffect(
    () => localStorage.setItem("boldfit-cart", JSON.stringify(cart)),
    [cart],
  );
  const orderId = route.startsWith("order/") ? route.slice(6) : null;
  useEffect(() => {
    setOrder(null);
    if (!orderId) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    let tries = 0;
    const poll = async () => {
      try {
        const result = await api<Order>(`/api/orders/${orderId}`);
        if (cancelled) return;
        setOrder(result);
        setError("");
        tries = 0;
        if (result.status === "AWAITING_PAYMENT")
          timer = setTimeout(poll, 3000);
      } catch (e) {
        if (!cancelled) {
          setError((e as Error).message);
          if (tries++ < 5) timer = setTimeout(poll, 4000);
        }
      }
    };
    poll();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [orderId, pollGeneration]);
  const count = Object.values(cart).reduce((a, b) => a + b, 0);
  const selected = products.filter((p) => cart[p.id] > 0);
  const total = selected.reduce((n, p) => n + p.price * cart[p.id], 0);
  const change = (id: string, delta: number) => {
    setCart((previous) => {
      const next = {
        ...previous,
        [id]: Math.max(0, Math.min(10, (previous[id] || 0) + delta)),
      };
      if (!next[id]) delete next[id];
      return next;
    });
    setError("");
  };
  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setError("");
    try {
      await action();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const checkout = () =>
    run(async () => {
      const items = selected
        .map((p) => ({ skuId: p.id, quantity: cart[p.id] }))
        .sort((a, b) => a.skuId.localeCompare(b.skuId));
      const key = stableKey(`checkout:${JSON.stringify(items)}`);
      const result = await api<Order>("/api/orders", {
        method: "POST",
        headers: { "Idempotency-Key": key },
        body: JSON.stringify({ items }),
      });
      localStorage.setItem("boldfit-last-order", result.id);
      setCart({});
      localStorage.removeItem(`checkout:${JSON.stringify(items)}`);
      setOrder(result);
      location.hash = `order/${result.id}`;
    });
  const pay = () =>
    run(async () => {
      if (!order) return;
      setNotice("");
      const last = order.attempts.at(-1);
      await api(`/api/orders/${order.id}/payment-attempts`, {
        method: "POST",
        headers: {
          "Idempotency-Key": stableKey(
            `payment:${order.id}:${last?.id || "first"}`,
          ),
        },
      });
      setOrder(await api<Order>(`/api/orders/${order.id}`));
    });
  const submitPayment = (method: PaymentMethod) =>
    run(async () => {
      if (!order) return;
      const attempt = order.attempts.at(-1);
      if (!attempt) return;
      await api(`/api/payments/${attempt.id}/pay`, {
        method: "POST",
        body: JSON.stringify({ method }),
      });
      setNotice("Payment submitted. Waiting for confirmation…");
      setOrder(await api<Order>(`/api/orders/${order.id}`));
    });
  const last = order?.attempts.at(-1),
    pending = last?.status === "PENDING";
  const lastOrder = localStorage.getItem("boldfit-last-order");
  return (
    <>
      <div className="announcement">
        A little movement. A better every day.{" "}
        <span>Made for your kind of fit.</span>
      </div>
      <header>
        <a href="#shop" className="logo">
          boldfit<span>®</span>
        </a>
        <nav>
          <a
            href="#shop"
            className={!orderId && route !== "checkout" ? "active" : ""}
          >
            The essentials
          </a>
          {lastOrder && <a href={`#order/${lastOrder}`}>Your order</a>}
        </nav>
        <a href="#checkout" className="bag-link">
          <ShoppingBag size={19} /> Bag <span>{count}</span>
        </a>
      </header>
      <main>
        {!orderId && route !== "checkout" ? (
          <>
            <section className="hero">
              <div>
                <div className="eyebrow">
                  <span /> EVERYDAY MOVEMENT, ELEVATED
                </div>
                <h1>
                  Small essentials.
                  <br />
                  Big possibilities.
                </h1>
                <p>
                  Build strength. Find balance. Show up for yourself.
                  <br />
                  Good gear for wherever you are in your journey.
                </p>
                <a
                  href="#collection"
                  className="text-link"
                  onClick={(e) => {
                    e.preventDefault();
                    document
                      .getElementById("collection")
                      ?.scrollIntoView({ behavior: "smooth" });
                  }}
                >
                  Find your next move <ArrowDown />
                </a>
              </div>
              <div className="hero-art">
                <div className="orbit" />
                <ProductArt id="mat" />
                <ProductArt id="bottle" />
                <span className="hero-tag">
                  <Leaf size={15} /> Built around you.
                </span>
                <span className="vertical-label">MOVE WELL. LIVE BOLD.</span>
              </div>
            </section>
            <section id="collection">
              <div className="section-head">
                <div className="eyebrow">THE EVERYDAY COLLECTION</div>
                <div>
                  <h2>Meet your new routine.</h2>
                  <span>5 essentials. Endless ways to move.</span>
                </div>
              </div>
              {loading ? (
                <div className="empty">Getting your essentials ready…</div>
              ) : (
                <div className="product-grid">
                  {products.map((p, i) => (
                    <article className="product" key={p.id}>
                      <div className="product-image">
                        <span className="product-number">
                          0{i + 1} / {p.category}
                        </span>
                        <ProductArt id={p.id} />
                        {cart[p.id] ? (
                          <div
                            className="product-quantity"
                            role="group"
                            aria-label={`${p.name} quantity`}
                          >
                            <button
                              aria-label={`Remove one ${p.name}`}
                              onClick={() => change(p.id, -1)}
                            >
                              <Minus size={15} />
                            </button>
                            <span
                              aria-live="polite"
                              aria-atomic="true"
                              aria-label={`${cart[p.id]} in bag`}
                            >
                              {cart[p.id]}
                            </span>
                            <button
                              aria-label={`Add one ${p.name}`}
                              disabled={cart[p.id] >= Math.min(10, p.available)}
                              onClick={() => change(p.id, 1)}
                            >
                              <Plus size={15} />
                            </button>
                          </div>
                        ) : (
                          <button
                            className="round-add"
                            aria-label={`Add ${p.name} to bag`}
                            disabled={!p.available}
                            onClick={() => change(p.id, 1)}
                          >
                            <Plus size={20} />
                          </button>
                        )}
                      </div>
                      <div className="product-title">
                        <h3>{p.name}</h3>
                        <span>{money(p.price)}</span>
                      </div>
                      <p>{p.description}</p>
                      <div
                        className={`stock ${p.available > 0 && p.available <= LOW_STOCK_THRESHOLD ? "stock-low" : ""}`}
                      >
                        {cart[p.id] > 0 && (
                          <span>{cart[p.id]} in your bag · </span>
                        )}
                        {stockMessage(p.available)}
                      </div>
                    </article>
                  ))}
                </div>
              )}
              {count > 0 && (
                <div className="bag-banner">
                  <div>
                    <ShoppingBag size={20} />
                    <span>
                      {count} {count === 1 ? "essential" : "essentials"} picked.
                      Make them yours.
                    </span>
                  </div>
                  <a href="#checkout">
                    View your bag · {money(total)} <ArrowRight size={18} />
                  </a>
                </div>
              )}
            </section>
          </>
        ) : orderId ? (
          <section className="checkout-page">
            <a className="back" href="#shop">
              <ChevronLeft size={16} /> Back to essentials
            </a>
            <div className="section-head">
              <div className="eyebrow">YOUR NEXT MOVE</div>
              <h1>Good things are on the way.</h1>
            </div>
            {!order ? (
              <div className="empty">Loading your order…</div>
            ) : (
              <div className="checkout-grid">
                <div className="status-card">
                  <div
                    className={`status-icon ${order.status === "CONFIRMED" ? "success" : ""}`}
                  >
                    {order.status === "CONFIRMED" ? (
                      <CheckCircle2 size={32} />
                    ) : (
                      <Clock3 size={32} />
                    )}
                  </div>
                  <div className="eyebrow">
                    ORDER {order.id.slice(0, 8).toUpperCase()}
                  </div>
                  <h2>
                    {
                      {
                        CONFIRMED: "You're all set.",
                        EXPIRED: "Your reservation expired.",
                        PAYMENT_REVIEW: "Your payment needs a closer look.",
                        AWAITING_PAYMENT: pending
                          ? last?.submitted || !!notice
                            ? "Awaiting confirmation."
                            : "Choose how you’d like to pay."
                          : last?.status === "FAILED"
                            ? "Let’s give that another try."
                            : "One step from your next move.",
                      }[order.status]
                    }
                  </h2>
                  <p>
                    {order.status === "CONFIRMED"
                      ? "Payment confirmed. Your essentials are officially yours."
                      : order.status === "EXPIRED"
                        ? "Your items have been released. Start a fresh bag to check availability."
                        : order.status === "PAYMENT_REVIEW"
                          ? "We could not safely confirm this payment. Please do not pay again; this order needs manual review."
                          : pending
                            ? last?.submitted || !!notice
                              ? "Your payment is processing. Your order updates once confirmation arrives."
                              : "Select a demo payment method below to complete your checkout."
                            : last?.status === "FAILED"
                              ? "The payment was declined. Your items are still reserved until the time below."
                              : "Your items are reserved for 15 minutes. Continue to payment to complete your order."}
                  </p>
                  {order.status === "AWAITING_PAYMENT" && (
                    <>
                      <div className="reservation">
                        <Clock3 size={16} /> Reserved until{" "}
                        {new Date(order.expiresAt).toLocaleTimeString("en-IN", {
                          hour: "2-digit",
                          minute: "2-digit",
                        })}
                      </div>
                      {!pending ? (
                        <button
                          className="primary"
                          onClick={pay}
                          disabled={busy}
                        >
                          {busy
                            ? "Preparing payment…"
                            : last
                              ? "Try payment again"
                              : "Continue to payment"}
                          <ArrowRight size={18} />
                        </button>
                      ) : (
                        <MockPayment
                          amount={money(order.total)}
                          busy={busy}
                          submitted={!!last?.submitted || !!notice}
                          onPay={submitPayment}
                        />
                      )}
                    </>
                  )}
                  {notice && order.status === "AWAITING_PAYMENT" && (
                    <p role="status" className="notice">
                      {notice}
                    </p>
                  )}
                  {order.status !== "AWAITING_PAYMENT" && (
                    <a
                      className="primary"
                      href="#shop"
                      onClick={() => {
                        Object.keys(localStorage)
                          .filter((k) => k.startsWith("checkout:"))
                          .forEach((k) => localStorage.removeItem(k));
                        loadProducts();
                      }}
                    >
                      Back to the collection <ArrowRight size={18} />
                    </a>
                  )}
                  <button
                    className="refresh"
                    onClick={() => setPollGeneration((value) => value + 1)}
                    disabled={busy}
                  >
                    <RefreshCw size={14} /> Refresh order status
                  </button>
                </div>
                <aside className="summary">
                  <div className="eyebrow">YOUR ESSENTIALS</div>
                  {order.lines.map((l) => (
                    <div className="summary-line" key={l.skuId}>
                      <ProductArt id={l.skuId} />
                      <div>
                        <strong>{l.name}</strong>
                        <small>Qty {l.quantity}</small>
                      </div>
                      <span>{money(l.unitPrice * l.quantity)}</span>
                    </div>
                  ))}
                  <div className="total">
                    <span>Total</span>
                    <strong>{money(order.total)}</strong>
                  </div>
                  <div className="trust">
                    <ShieldCheck size={17} /> Payment status verified by our
                    server
                  </div>
                </aside>
              </div>
            )}
          </section>
        ) : (
          <section className="checkout-page">
            <a href="#shop" className="back">
              <ChevronLeft size={16} /> Keep exploring
            </a>
            <div className="section-head">
              <div className="eyebrow">YOUR EVERYDAY UPGRADE</div>
              <h1>A good start is in the bag.</h1>
            </div>
            {!count ? (
              <div className="empty">
                <ShoppingBag size={36} />
                <h2>A little room for possibility.</h2>
                <p>Your bag is empty. Find something that moves you.</p>
                <a href="#shop" className="primary">
                  Explore the essentials <ArrowRight size={18} />
                </a>
              </div>
            ) : (
              <div className="checkout-grid">
                <div className="cart-items">
                  {selected.map((p) => (
                    <div className="cart-line" key={p.id}>
                      <ProductArt id={p.id} />
                      <div className="cart-info">
                        <div className="eyebrow">{p.category}</div>
                        <h3>{p.name}</h3>
                        <span>{money(p.price)}</span>
                        <div className="quantity">
                          <button
                            aria-label={`Remove one ${p.name}`}
                            onClick={() => change(p.id, -1)}
                            disabled={busy}
                          >
                            <Minus size={13} />
                          </button>
                          <span>{cart[p.id]}</span>
                          <button
                            aria-label={`Add one ${p.name}`}
                            onClick={() => change(p.id, 1)}
                            disabled={
                              busy || cart[p.id] >= Math.min(10, p.available)
                            }
                          >
                            <Plus size={13} />
                          </button>
                        </div>
                      </div>
                      <div className="line-end">
                        <button
                          aria-label={`Remove ${p.name}`}
                          disabled={busy}
                          onClick={() => change(p.id, -10)}
                        >
                          <X size={17} />
                        </button>
                        <strong>{money(p.price * cart[p.id])}</strong>
                      </div>
                    </div>
                  ))}
                  <div className="cart-note">
                    <Leaf size={19} />
                    <p>
                      Every great routine starts with showing up.
                      <br />
                      <strong>We’ll bring the essentials.</strong>
                    </p>
                  </div>
                </div>
                <aside className="summary">
                  <h2>Order summary</h2>
                  <div className="price-row">
                    <span>Subtotal ({count} items)</span>
                    <span>{money(total)}</span>
                  </div>
                  <div className="price-row">
                    <span>Delivery</span>
                    <span className="free">On us</span>
                  </div>
                  <div className="total">
                    <span>Total</span>
                    <strong>{money(total)}</strong>
                  </div>
                  <p className="fine">
                    Inclusive of applicable taxes. INR pricing.
                  </p>
                  <button
                    className="primary"
                    disabled={busy || loading || !selected.length}
                    onClick={checkout}
                  >
                    {busy
                      ? "Reserving your essentials…"
                      : "Continue to checkout"}
                    <ArrowRight size={18} />
                  </button>
                  <div className="trust">
                    <LockKeyhole size={16} /> Secure checkout · Mock payments
                    only
                  </div>
                  <div className="summary-footer">
                    <Truck size={18} />
                    <span>Free delivery on this demo collection</span>
                  </div>
                </aside>
              </div>
            )}
          </section>
        )}
        {error && (
          <div role="alert" className="error">
            {error}
            <button onClick={orderId ? () => location.reload() : loadProducts}>
              Retry
            </button>
          </div>
        )}
        <div className="values">
          <div>
            <Dumbbell size={21} />
            <span>Made for everyday movement</span>
          </div>
          <div>
            <ShieldCheck size={21} />
            <span>Thoughtfully built essentials</span>
          </div>
          <div>
            <Truck size={21} />
            <span>A little closer to your goals</span>
          </div>
        </div>
      </main>
      <footer>
        <a href="#shop" className="logo">
          boldfit<span>®</span>
        </a>
        <span>Find your fit. Make it yours.</span>
        <small>Interview project · Mock checkout · No real purchases</small>
        <ArrowUpRight size={18} />
      </footer>
    </>
  );
}
function ArrowDown() {
  return <ArrowRight size={17} style={{ transform: "rotate(90deg)" }} />;
}
