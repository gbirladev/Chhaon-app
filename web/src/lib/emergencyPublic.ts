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
  /** Migration 0052: only for a pregnancy / a child, null otherwise (absent from an older database). */
  pregnancy?: { week: number | null; due_date: string | null } | null;
  child?: { born: string | null; weight_kg: number | null; weight_on: string | null } | null;
};

export type CardResult = { kind: "ok"; card: PublicCard } | { kind: "off" } | { kind: "busy" } | { kind: "error" };

/**
 * Never throws. "off" covers unknown, revoked, deleted and malformed links alike (the database does not say which).
 *
 * The card is read straight from the database through one function (`public_emergency_card`, migration 0038), called
 * with the public anon key. A plain request, not the Supabase library: this page must stay tiny for slow phones and
 * must never start a login session.
 */
function callCard(fn: "public_emergency_card" | "public_emergency_doctors", token: string, signal?: AbortSignal) {
  const key = String(import.meta.env.VITE_SUPABASE_ANON_KEY);
  const headers: Record<string, string> = { apikey: key, "Content-Type": "application/json" };
  if (key.startsWith("eyJ")) headers.Authorization = `Bearer ${key}`;  // the older anon key is a JWT; newer publishable keys are sent as apikey only
  return fetch(`${resolveLocalUrl(import.meta.env.VITE_SUPABASE_URL)}/rest/v1/rpc/${fn}`, {
    method: "POST", signal, cache: "no-store", referrerPolicy: "no-referrer", headers, body: JSON.stringify({ p_token: token }),
  });
}

export async function fetchPublicCard(token: string, signal?: AbortSignal): Promise<CardResult> {
  try {
    const response = await callCard("public_emergency_card", token, signal);
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

export type PublicDoctor = { name: string; speciality: string | null; phone: string | null };

/**
 * "Your doctors" for the same link (migration 0059, a separate function so the card itself is unchanged): name, speciality
 * and phone, at most 5. Never throws; null when it cannot be read (an older database, a network error), and the page then
 * shows the card's single doctor name as before.
 */
export async function fetchPublicDoctors(token: string, signal?: AbortSignal): Promise<PublicDoctor[] | null> {
  try {
    const response = await callCard("public_emergency_doctors", token, signal);
    if (!response.ok) return null;
    const data: unknown = await response.json();
    if (!Array.isArray(data)) return null;
    return data.flatMap((d): PublicDoctor[] => {
      if (!d || typeof d !== "object") return [];
      const o = d as Record<string, unknown>;
      const text = (v: unknown) => (typeof v === "string" && v.trim() ? v : null);
      const name = text(o.name);
      const phone = text(o.phone);
      return name ? [{ name, speciality: text(o.speciality), phone: phone && /^\+[1-9]\d{7,14}$/.test(phone) ? phone : null }] : [];
    });
  } catch {
    return null;
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
