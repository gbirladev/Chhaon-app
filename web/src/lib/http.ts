import { resolveLocalUrl } from "./hosts";
import { authToken } from "./auth";

/** One place for every call to our Java API: base URL, bearer token, JSON in/out, typed errors. */

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

const base = () => resolveLocalUrl(import.meta.env.VITE_API_URL);

export async function apiFetch<T>(path: string, options: { method?: string; body?: unknown } = {}): Promise<T> {
  const token = authToken();
  const res = await fetch(`${base()}${path}`, {
    method: options.method ?? "GET",
    headers: {
      ...(options.body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  });
  if (res.status === 204) return undefined as T;
  if (!res.ok) {
    let message = `HTTP ${res.status}`;
    try {
      const body = (await res.json()) as { detail?: string; message?: string };
      message = body.detail ?? body.message ?? message;
    } catch { /* no JSON body */ }
    throw new ApiError(res.status, message);
  }
  return (await res.json()) as T;
}
