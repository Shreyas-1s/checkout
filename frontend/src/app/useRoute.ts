import { useEffect, useState } from "react";
// Preserve existing shareable hash URLs without requiring server route rewrites.
export function useRoute() {
  const [route, setRoute] = useState(() => location.hash.slice(1) || "shop");
  useEffect(() => {
    const change = () => setRoute(location.hash.slice(1) || "shop");
    window.addEventListener("hashchange", change);
    return () => window.removeEventListener("hashchange", change);
  }, []);
  return route;
}
