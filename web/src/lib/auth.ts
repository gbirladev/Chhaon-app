import { resolveLocalUrl } from "./hosts";

/**
 * Phone-OTP login, owned by our Java backend (replaces Supabase Auth / GoTrue).
 *
 * The backend returns a Supabase-compatible access token (signed with the Supabase JWT secret,
 * role=authenticated, sub=profiles.id). We keep it in localStorage and hand it to:
 *  - the supabase-js client (see lib/supabase.ts `accessToken`), so every direct DB/storage call and its
 *    RLS keep working exactly as before; and
 *  - the Java API, as the Bearer token.
 *
 * There is no refresh token yet, so the token is long-lived; when it expires the user logs in again.
 */

const KEY = "chhaon-token";
const api = () => resolveLocalUrl(import.meta.env.VITE_API_URL);

let cached: string | null | undefined;

export function authToken(): string | null {
  if (cached !== undefined) return cached;
  try { cached = localStorage.getItem(KEY); } catch { cached = null; }
  return cached ?? null;
}

function setToken(token: string) {
  cached = token;
  try { localStorage.setItem(KEY, token); } catch { /* storage blocked (private window) */ }
}

export function clearToken() {
  cached = null;
  sbCached = null;
  try { localStorage.removeItem(KEY); localStorage.removeItem(SB_KEY); } catch { /* storage blocked */ }
}

/**
 * The Supabase-compatible token (HS256, the project's legacy JWT secret, role=authenticated) the backend also hands out at
 * login. The app's direct Supabase calls (reports wallet, photos, feedback pictures) must use it: Supabase does not accept
 * our own API token. null on an older login: then the user logs in again to get one.
 */
const SB_KEY = "chhaon-sb-token";
let sbCached: string | null | undefined;
export function supabaseToken(): string | null {
  if (sbCached !== undefined) return sbCached;
  try { sbCached = localStorage.getItem(SB_KEY); } catch { sbCached = null; }
  return sbCached ?? null;
}
function setSupabaseToken(token: string | null | undefined) {
  sbCached = token ?? null;
  try { if (token) localStorage.setItem(SB_KEY, token); else localStorage.removeItem(SB_KEY); } catch { /* storage blocked */ }
}

type Claims = { sub?: string; exp?: number };

function decode(token: string | null): Claims | null {
  if (!token) return null;
  try {
    const payload = token.split(".")[1];
    const json = atob(payload.replace(/-/g, "+").replace(/_/g, "/"));
    return JSON.parse(json) as Claims;
  } catch { return null; }
}

/** The logged-in caregiver's profile id (JWT `sub`), or null. */
export function authUserId(): string | null {
  return decode(authToken())?.sub ?? null;
}

/** True when there is no token, or the token has expired. */
export function authExpired(): boolean {
  const token = authToken();
  if (!token) return true;
  const exp = decode(token)?.exp;
  if (!exp) return false;            // a token with no exp is treated as valid
  return exp * 1000 <= Date.now();
}

/** Signed in = we hold a token that has not expired. */
export function isSignedIn(): boolean {
  return !authExpired();
}

export type OtpError = "invalid_phone" | "too_many" | "network" | "send_failed" | "wrong_code" | "error";

/** Ask the backend to send a login code over WhatsApp. */
export async function requestOtp(phone: string): Promise<{ ok: true } | { ok: false; error: OtpError }> {
  try {
    const res = await fetch(`${api()}/api/v1/auth/otp/request`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ phone }),
    });
    if (res.ok) return { ok: true };
    if (res.status === 400) return { ok: false, error: "invalid_phone" };
    if (res.status === 429) return { ok: false, error: "too_many" };
    return { ok: false, error: "send_failed" };
  } catch {
    return { ok: false, error: "network" };
  }
}

/** Verify a code; on success the token is stored and the caller is logged in. */
export async function verifyOtp(phone: string, code: string): Promise<{ ok: true; isNew: boolean } | { ok: false; error: OtpError }> {
  try {
    const res = await fetch(`${api()}/api/v1/auth/otp/verify`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ phone, code }),
    });
    if (res.ok) {
      const body = (await res.json()) as { token: string; isNew: boolean; supabaseToken?: string | null };
      setToken(body.token);
      setSupabaseToken(body.supabaseToken);
      return { ok: true, isNew: body.isNew };
    }
    if (res.status === 429) return { ok: false, error: "too_many" };
    if (res.status === 401) return { ok: false, error: "wrong_code" };
    return { ok: false, error: "error" };
  } catch {
    return { ok: false, error: "network" };
  }
}
