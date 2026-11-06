import { apiFetch } from "./http";

/**
 * Family activity feed (feature 4): what happened in the last 7 days for everyone the caregiver cares for. Read-only; the only
 * thing a family member can do is give a ♥ (seen by the family only, never sent on WhatsApp). The backend scopes it to the
 * caller's circles and never sends a phone number.
 */

export type FeedKind =
  | "dose_taken" | "dose_missed" | "checkin_answered" | "checkin_missed" | "vaccine_given"
  | "appointment_added" | "medicine_added" | "medicine_paused" | "medicine_stopped" | "consent_given" | "consent_stopped";

/** Who did it: me | member (a family member who marked it) | person | helper | caregiver (another family member gets the reminders) | app. */
export type FeedActor = { kind: "me" | "member" | "person" | "helper" | "caregiver" | "app"; name: string | null };

export type FeedPerson = {
  id: string; call: string; careType: string | null; timezone: string | null; isSelf: boolean;
  reminderTo: string; reminderName: string | null; reminderRelation: string | null; reminderIsMe: boolean;
};

export type FeedItem = {
  key: string; kind: FeedKind; recipientId: string; at: string; title: string | null; titleHi: string | null;
  detail: string | null; dueAt: string | null; answer: "fine" | "call" | null; actor: FeedActor | null;
  hearts: number; heartedByMe: boolean;
};

export type Feed = { people: FeedPerson[]; items: FeedItem[]; next: string | null };

export function loadFeed(args: { recipientId?: string | null; cursor?: string | null; limit?: number } = {}): Promise<Feed> {
  const q = new URLSearchParams();
  if (args.recipientId) q.set("recipient", args.recipientId);
  if (args.cursor) q.set("cursor", args.cursor);
  if (args.limit) q.set("limit", String(args.limit));
  const qs = q.toString();
  return apiFetch<Feed>(`/api/v1/activity${qs ? `?${qs}` : ""}`);
}

export const setHeart = (recipientId: string, itemKey: string, on: boolean) =>
  apiFetch<{ hearts: number; heartedByMe: boolean }>("/api/v1/activity/hearts", { method: "PUT", body: { recipientId, itemKey, on } });

/** "Today" / "Yesterday" / a date, on the viewer's own calendar. Returns the day key and how far back it is. */
export function dayOf(iso: string, now: Date = new Date()): { key: string; daysAgo: number } {
  const d = new Date(iso);
  const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  const startOf = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  return { key, daysAgo: Math.round((startOf(now) - startOf(d)) / 86_400_000) };
}

/** The items grouped by the viewer's day, newest first (the API already sorts them). */
export function groupByDay(items: FeedItem[], now: Date = new Date()): { key: string; daysAgo: number; items: FeedItem[] }[] {
  const groups: { key: string; daysAgo: number; items: FeedItem[] }[] = [];
  for (const item of items) {
    const day = dayOf(item.at, now);
    const last = groups[groups.length - 1];
    if (last && last.key === day.key) last.items.push(item);
    else groups.push({ ...day, items: [item] });
  }
  return groups;
}

/** A helper's label as the app writes it elsewhere: "Sunita (maid)". */
export function helperLabel(person: FeedPerson | undefined, fallback: string): string {
  if (!person) return fallback;
  const name = (person.reminderName ?? "").trim();
  const relation = (person.reminderRelation ?? "").trim();
  if (name && relation) return `${name} (${relation})`;
  return name || relation || fallback;
}
