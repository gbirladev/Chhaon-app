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

/** Never throws. "off" covers unknown, revoked and malformed links alike (the server does not say which). */
export async function fetchPublicCard(token: string, signal?: AbortSignal): Promise<CardResult> {
  try {
    const response = await fetch(`${resolveLocalUrl(import.meta.env.VITE_API_URL)}/public/emergency/${encodeURIComponent(token)}`, { signal, cache: "no-store", referrerPolicy: "no-referrer" });
    if (response.status === 404) return { kind: "off" };
    if (response.status === 429) return { kind: "busy" };
    if (!response.ok) return { kind: "error" };
    return { kind: "ok", card: (await response.json()) as PublicCard };
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
