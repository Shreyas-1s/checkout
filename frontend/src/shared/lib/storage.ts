// Keep a usable cart and operation keys even when browser storage is unavailable.
export function createStorage(getBackend: () => Storage) {
  const memory = new Map<string, string>();
  const removed = new Set<string>();
  return {
    getItem(key: string): string | null {
      if (removed.has(key)) return null;
      if (memory.has(key)) return memory.get(key)!;
      try {
        return getBackend().getItem(key);
      } catch {
        return null;
      }
    },
    setItem(key: string, value: string) {
      removed.delete(key);
      memory.set(key, value);
      try {
        getBackend().setItem(key, value);
      } catch {
        /* Tab-local fallback. */
      }
    },
    removeItem(key: string) {
      memory.delete(key);
      removed.add(key);
      try {
        getBackend().removeItem(key);
      } catch {
        /* Ignore unavailable storage. */
      }
    },
  };
}
export const storage = createStorage(() => localStorage);
export function stableKey(scope: string) {
  const key = storage.getItem(scope) || crypto.randomUUID();
  storage.setItem(scope, key);
  return key;
}
