import { test, afterEach } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import ts from "typescript";
const source = ts.transpileModule(
  readFileSync(new URL("../src/shared/api/client.ts", import.meta.url), "utf8"),
  {
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.ES2022,
    },
  },
).outputText;
const { api } = await import(
  `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`
);
const originalFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = originalFetch;
});
test("HTML proxy responses produce actionable errors instead of JSON parse errors", async () => {
  globalThis.fetch = async () =>
    new Response("<html>Error</html>", {
      status: 502,
      headers: { "Content-Type": "text/html" },
    });
  await assert.rejects(
    api("/api/orders"),
    (e) => e.code === "INVALID_RESPONSE" && e.status === 502,
  );
});
test("malformed JSON is reported as an invalid response", async () => {
  globalThis.fetch = async () =>
    new Response("{", { headers: { "Content-Type": "application/json" } });
  await assert.rejects(
    api("/api/orders"),
    (e) => e.code === "INVALID_RESPONSE",
  );
});
test("network errors never automatically replay a mutation", async () => {
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    throw new TypeError("offline");
  };
  await assert.rejects(
    api("/api/orders", { method: "POST" }),
    (e) => e.code === "NETWORK_ERROR",
  );
  assert.equal(calls, 1);
});
test("API errors preserve validation, retry guidance and request reference", async () => {
  globalThis.fetch = async () =>
    new Response(
      JSON.stringify({
        code: "RETRY_REQUIRED",
        message: "Try later",
        fields: { quantity: "Invalid" },
        requestId: "reference-123",
      }),
      {
        status: 503,
        headers: { "Content-Type": "application/json", "Retry-After": "1" },
      },
    );
  await assert.rejects(
    api("/api/orders"),
    (e) =>
      e.status === 503 &&
      e.retryAfter === 1 &&
      e.message.includes("reference-123") &&
      e.message.includes("quantity: Invalid"),
  );
});
test("aborted requests explain that the operation may already have completed", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  globalThis.fetch = async (_path, { signal }) =>
    new Promise((_resolve, reject) =>
      signal.addEventListener("abort", () => reject(new Error("abort"))),
    );
  const result = assert.rejects(
    api("/api/orders", { method: "POST" }),
    (e) => e.code === "TIMEOUT" && e.message.includes("may have completed"),
  );
  t.mock.timers.tick(10000);
  await result;
});
