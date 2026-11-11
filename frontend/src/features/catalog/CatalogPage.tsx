import { ArrowRight, Leaf, Minus, Plus, ShoppingBag } from "lucide-react";
import type { Product } from "../../shared/api/contracts";
import ProductArt from "../../shared/components/ProductArt";
import { money } from "../../shared/lib/money";
import type { Cart } from "../cart/cart";

import { LOW_STOCK_THRESHOLD, stockMessage } from "./stock";
type Props = {
  products: Product[];
  loading: boolean;
  cart: Cart;
  count: number;
  total: number;
  change: (id: string, delta: number) => void;
};
export default function CatalogPage({
  products,
  loading,
  cart,
  count,
  total,
  change,
}: Props) {
  return (
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
                  {cart[p.id] > 0 && <span>{cart[p.id]} in your bag · </span>}
                  {stockMessage(p.available)}
                </div>
              </article>
            ))}
          </div>
        )}
        {count > 0 && (
          <aside className="cart-nudge" aria-label="Your shopping bag">
            <div className="cart-nudge-details">
              <ShoppingBag size={21} aria-hidden="true" />
              <div aria-live="polite" aria-atomic="true">
                <strong>
                  {count} {count === 1 ? "item" : "items"} in your bag
                </strong>
                <span>{loading ? "Updating total…" : money(total)}</span>
              </div>
            </div>
            <a
              href="#checkout"
              onClick={() => window.scrollTo({ top: 0, behavior: "auto" })}
            >
              View bag <ArrowRight size={18} aria-hidden="true" />
            </a>
          </aside>
        )}
      </section>
    </>
  );
}
function ArrowDown() {
  return <ArrowRight size={17} style={{ transform: "rotate(90deg)" }} />;
}
