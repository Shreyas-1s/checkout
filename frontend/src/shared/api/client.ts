export class ApiError extends Error {
  constructor(
    message: string,
    public status = 0,
    public code = "NETWORK_ERROR",
    public retryAfter = 0,
  ) {
    super(message);
    this.name = "ApiError";
  }
}
/** Never automatically replay mutations: the caller retains its operation key. */
export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const controller = new AbortController();
  const abort = () => controller.abort();
  init?.signal?.addEventListener("abort", abort, { once: true });
  if (init?.signal?.aborted) controller.abort();
  const timeout = setTimeout(() => controller.abort(), 10000);
  try {
    const response = await fetch(path, {
      ...init,
      signal: controller.signal,
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer demo-alex",
        ...init?.headers,
      },
    });
    if (!response.headers.get("content-type")?.includes("application/json"))
      throw new ApiError(
        "The checkout service returned an unexpected response. Refresh status before retrying a payment.",
        response.status,
        "INVALID_RESPONSE",
      );
    let body;
    try {
      body = await response.json();
    } catch {
      throw new ApiError(
        "The checkout service returned an unreadable response. Please refresh status.",
        response.status,
        "INVALID_RESPONSE",
      );
    }
    if (!response.ok) {
      const fields =
        body.fields && typeof body.fields === "object"
          ? Object.entries(body.fields)
              .map(([field, message]) => `${field}: ${message}`)
              .join("; ")
          : "";
      const reference =
        typeof body.requestId === "string"
          ? ` Reference: ${body.requestId}.`
          : "";
      const wait = Number(response.headers.get("Retry-After")) || 0;
      const guidance =
        wait > 0
          ? ` Wait ${wait} seconds before retrying the same request.`
          : "";
      throw new ApiError(
        `${body.message || "The request could not be completed."}${fields ? ` ${fields}.` : ""}${guidance}${reference}`,
        response.status,
        body.code,
        wait,
      );
    }
    return body as T;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    const timedOut = controller.signal.aborted;
    throw new ApiError(
      timedOut
        ? "The request timed out. It may have completed. Refresh order status, or retry checkout with the same bag."
        : "Cannot reach checkout. Check your connection. A submitted payment may still complete; refresh order status before trying again.",
      0,
      timedOut ? "TIMEOUT" : "NETWORK_ERROR",
    );
  } finally {
    clearTimeout(timeout);
    init?.signal?.removeEventListener("abort", abort);
  }
}
