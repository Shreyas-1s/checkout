import {
  ArrowUpRight,
  Dumbbell,
  ShieldCheck,
  ShoppingBag,
  Truck,
} from "lucide-react";
import { useEffect } from "react";
import { useCart } from "../features/cart/useCart";
import CatalogPage from "../features/catalog/CatalogPage";
import { useCatalog } from "../features/catalog/useCatalog";
import CheckoutPage from "../features/checkout/CheckoutPage";
import { useCheckout } from "../features/checkout/useCheckout";
import OrderPage from "../features/orders/OrderPage";
import { storage } from "../shared/lib/storage";
import { useRoute } from "./useRoute";

export default function App() {
  const route = useRoute();
  const orderId = route.startsWith("order/") ? route.slice(6) : null;
  const { cart, change, clear, count } = useCart();
  const {
    products,
    loading,
    error: catalogError,
    refresh: loadProducts,
  } = useCatalog(route);
  const {
    checkout,
    busy,
    error: checkoutError,
    resetError,
  } = useCheckout(cart, clear, loadProducts);
  useEffect(resetError, [route, resetError]);
  const error = checkoutError || catalogError;
  const selected = products.filter((p) => cart[p.id] > 0);
  const total = selected.reduce((sum, p) => sum + p.price * cart[p.id], 0);
  const lastOrder = storage.getItem("boldfit-last-order");
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
        {orderId ? (
          <OrderPage key={orderId} orderId={orderId} />
        ) : route === "checkout" ? (
          <CheckoutPage
            selected={selected}
            cart={cart}
            count={count}
            total={total}
            busy={busy}
            loading={loading}
            change={change}
            checkout={checkout}
          />
        ) : (
          <CatalogPage
            products={products}
            loading={loading}
            cart={cart}
            count={count}
            total={total}
            change={change}
          />
        )}
        {error && (
          <div role="alert" className="error">
            {error}
            <button onClick={loadProducts}>Retry</button>
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
      {count > 0 && !orderId && route !== "checkout" && (
        <div className="cart-nudge-space" aria-hidden="true" />
      )}
    </>
  );
}
