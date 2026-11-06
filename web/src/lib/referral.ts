import { patchMe } from "./api";
import { ApiError, apiFetch } from "./http";

/**
 * Which doctor brought a family (migration 0058). A doctor's printed QR card opens /app/?ref=D001. The code is kept on this
 * device until the person has signed in (that can be hours later, after closing the browser), then sent once with the profile.
 * Counting only: nothing is shown to the doctor (Regulation 6.4.1).
 */

const KEY = "chhaon-ref";
const KEEP_DAYS = 30;
/** Same characters as the cards (web/public/doctors-page.js); anything else in the address is ignored. */
const CODE = /^[A-Za-z0-9_-]{1,32}$/;

type Stored = { code: string; at: number };

function read(): Stored | null {
  try {
    const value = JSON.parse(localStorage.getItem(KEY) ?? "null") as Stored | null;
    if (!value || typeof value.code !== "string" || !CODE.test(value.code) || typeof value.at !== "number") return null;
    if (Date.now() - value.at > KEEP_DAYS * 86_400_000) { localStorage.removeItem(KEY); return null; }
    return value;
  } catch { return null; }
}

/** Reads ?ref= once at start-up, keeps the FIRST code seen, and takes it out of the address bar (the #/screen part stays). */
function captureReferralFromUrl() {
  try {
    const url = new URL(window.location.href);
    if (!url.searchParams.has("ref")) return;
    const raw = url.searchParams.get("ref") ?? "";
    if (CODE.test(raw) && !read()) localStorage.setItem(KEY, JSON.stringify({ code: raw.toUpperCase(), at: Date.now() }));
    url.searchParams.delete("ref");
    history.replaceState(history.state, "", url.pathname + url.search + url.hash);
  } catch { /* storage blocked: the code simply is not counted */ }
}
captureReferralFromUrl();

let sending = false;
/** Signed in now: send a waiting code once. Kept on a network error (tried again next time), dropped once the server answered. */
export async function sendPendingReferral(): Promise<void> {
  const pending = read();
  if (!pending || sending) return;
  sending = true;
  try {
    await patchMe({ referredBy: pending.code });
    localStorage.removeItem(KEY);
  } catch (e) {
    const status = (e as ApiError).status;
    if (typeof status === "number" && status >= 400 && status < 500 && status !== 401 && status !== 408 && status !== 429) {
      try { localStorage.removeItem(KEY); } catch { /* ignore */ }   // refused for good: do not send it again
    }
  } finally {
    sending = false;
  }
}

/** The fixed answers to "How did you hear about Chhaon?" (same list as the database check). */
export const HEARD_FROM = ["doctor", "friend_family", "whatsapp", "google", "other"] as const;
export type HeardFrom = (typeof HEARD_FROM)[number];

/** Saves the optional answer. The first answer wins; a failure is ignored (the question is optional). */
export async function saveHeardFrom(answer: HeardFrom): Promise<void> {
  try { await patchMe({ heardFrom: answer }); } catch { /* optional: never block the person */ }
}

// ---------------------------------------------------------------- admin counts (Pilot stats)
export type ReferralStats = {
  total: number;
  withCode: number;
  byCode: { code: string; signups: number; withPerson: number; agreed: number }[];
  byHeardFrom: { answer: HeardFrom | "not_answered"; signups: number }[];
};

type Raw = {
  total: number; with_code: number;
  by_code: { code: string; signups: number; with_person: number; agreed: number }[];
  by_heard_from: { answer: HeardFrom | "not_answered"; signups: number }[];
};

export async function loadReferralStats(): Promise<ReferralStats> {
  const r = await apiFetch<Raw>("/api/v1/admin/referrals");
  if (!r || !Array.isArray(r.by_code) || !Array.isArray(r.by_heard_from)) throw new Error("bad referral stats");
  return {
    total: r.total,
    withCode: r.with_code,
    byCode: r.by_code.map((c) => ({ code: c.code, signups: c.signups, withPerson: c.with_person, agreed: c.agreed })),
    byHeardFrom: r.by_heard_from,
  };
}
