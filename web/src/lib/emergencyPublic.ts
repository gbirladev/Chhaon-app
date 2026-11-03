// Used by the public emergency page. Deliberately imports NOTHING that touches the Supabase session.
import { resolveLocalUrl } from "./hosts";

export type PublicCard = {
  name: string;
  age: number | null;
  blood_group: string | null;
  conditions: string[];
  allergies: string[];
  medicines: { name: string; dose: string; times: string[] }[];
  contacts: { name: string; phone: string }[];
  doctor: string | null;
};

export type CardResult = { kind: "ok"; card: PublicCard } | { kind: "off" } | { kind: "busy" } | { kind: "error" };

/**
 * Never throws. "off" covers unknown, revoked, deleted and malformed links alike (the database does not say which).
 *
 * The card is read straight from the database through one function (`public_emergency_card`, migration 0038), called
 * with the public anon key. A plain request, not the Supabase library: this page must stay tiny for slow phones and
 * must never start a login session.
 */
export async function fetchPublicCard(token: string, signal?: AbortSignal): Promise<CardResult> {
  try {
    const key = String(import.meta.env.VITE_SUPABASE_ANON_KEY);
    const headers: Record<string, string> = { apikey: key, "Content-Type": "application/json" };
    if (key.startsWith("eyJ")) headers.Authorization = `Bearer ${key}`;  // the older anon key is a JWT; newer publishable keys are sent as apikey only
    const response = await fetch(`${resolveLocalUrl(import.meta.env.VITE_SUPABASE_URL)}/rest/v1/rpc/public_emergency_card`, {
      method: "POST", signal, cache: "no-store", referrerPolicy: "no-referrer", headers, body: JSON.stringify({ p_token: token }),
    });
    if (response.status === 429) return { kind: "busy" };
    if (!response.ok) return { kind: "error" };
    const data: unknown = await response.json();
    if (data === null) return { kind: "off" };
    if (typeof data !== "object" || Array.isArray(data)) return { kind: "error" };
    return { kind: "ok", card: data as PublicCard };
  } catch {
    return { kind: "error" };
  }
}

export const publicLinkToken = (hash: string): string | null => {
  const match = /^#\/e\/([^/?#]*)/.exec(hash);
  if (!match) return null;
  try {
    return decodeURIComponent(match[1]).trim().toLowerCase();  // tokens are lowercase hex; tolerate pasted capitals
  } catch {
    return null;  // malformed escape such as %E0%A4%A: shown as "link is turned off"
  }
};

/** True for any address that is an emergency card link (checked before the caregiver app does anything with the hash). */
export const isPublicCardHash = (hash: string) => /^#\/e(\/|$)/.test(hash);
