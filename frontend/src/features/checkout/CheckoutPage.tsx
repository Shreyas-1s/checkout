import {
  ArrowRight,
  ChevronLeft,
  Leaf,
  LockKeyhole,
  Minus,
  Plus,
  ShoppingBag,
  Truck,
  X,
} from "lucide-react";
import type { Product } from "../../shared/api/contracts";
import ProductArt from "../../shared/components/ProductArt";
import { money } from "../../shared/lib/money";
import type { Cart } from "../cart/cart";

type Props = {
  selected: Product[];
  cart: Cart;
  count: number;
  total: number;
  busy: boolean;
  loading: boolean;
  change: (id: string, delta: number) => void;
  checkout: () => void;
};
export default function CheckoutPage({
  selected,
  cart,
  count,
  total,
  busy,
  loading,
  change,
  checkout,
}: Props) {
  return (
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
                      disabled={busy || cart[p.id] >= Math.min(10, p.available)}
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
            <p className="fine">Inclusive of applicable taxes. INR pricing.</p>
            <button
              className="primary"
              disabled={busy || loading || !selected.length}
              onClick={checkout}
            >
              {busy ? "Reserving your essentials…" : "Continue to checkout"}
              <ArrowRight size={18} />
            </button>
            <div className="trust">
              <LockKeyhole size={16} /> Secure checkout · Mock payments only
            </div>
            <div className="summary-footer">
              <Truck size={18} />
              <span>Free delivery on this demo collection</span>
            </div>
          </aside>
        </div>
      )}
    </section>
  );
}
