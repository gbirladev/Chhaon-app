import { zonedToUtc } from "./appointments";
import { deviceZone, formatClock, formatDate, formatDateTime, localDate } from "./format";

/**
 * When the first dose of a just-saved medicine is due, on the PARENT's clock. Same rule as the scheduler (generate_dose_events):
 * a time that has already passed today gets no dose today, so the first one is the earliest time still to come today, otherwise
 * the earliest time tomorrow. `times` are "HH:MM". Returns null when there is no readable time.
 */
export function firstDoseAt(times: string[], timeZone: string, now: Date = new Date()): { at: Date; tomorrow: boolean } | null {
  const sorted = [...new Set(times.filter((time) => /^\d{2}:\d{2}$/.test(time)))].sort();
  if (!sorted.length) return null;
  const today = localDate(now, timeZone);
  for (const time of sorted) {
    const iso = zonedToUtc(today, time, timeZone);
    if (iso && new Date(iso).getTime() >= now.getTime()) return { at: new Date(iso), tomorrow: false };
  }
  const [y, m, d] = today.split("-").map(Number);
  const tomorrow = new Date(Date.UTC(y, m - 1, d + 1)).toISOString().slice(0, 10);
  const iso = zonedToUtc(tomorrow, sorted[0], timeZone);
  return iso ? { at: new Date(iso), tomorrow: true } : null;
}

export type WhenKey = "today" | "tomorrow" | "date" | "both";

/**
 * "today 9:00 PM" / "tomorrow 8:00 AM" / "12 Nov, 8:00 AM" on the PERSON's clock, plus the caregiver's own time ("both") when their
 * phone is in another time zone. `say` turns a key and its values into the words (the caller picks its own message keys).
 */
export function reminderWhen(at: Date, zone: string, language: string, say: (key: WhenKey, values: Record<string, string>) => string, now: Date = new Date()): string {
  const time = formatClock(at, zone, language);
  const day = localDate(at, zone);
  const today = localDate(now, zone);
  const [y, m, d] = today.split("-").map(Number);
  const tomorrow = new Date(Date.UTC(y, m - 1, d + 1)).toISOString().slice(0, 10);
  let when = day === today ? say("today", { time })
    : day === tomorrow ? say("tomorrow", { time })
    : say("date", { date: formatDate(at, language, { day: "numeric", month: "short", timeZone: zone }), time });
  const mine = deviceZone();
  if (formatDateTime(at, language, mine) !== formatDateTime(at, language, zone)) {
    const sameDay = localDate(at, mine) === localDate(at, zone);
    when = say("both", { when, mine: sameDay ? formatClock(at, mine, language) : formatDateTime(at, language, mine) });
  }
  return when;
}

// The first-reminder card on Today (FirstReminderCard): set on this device when a NEW medicine is saved, removed once a real
// reminder has gone out. "dismissed" = closed by hand (it does not come back with the next medicine).
const cardKey = (recipientId: string) => `chhaon-first-reminder-${recipientId}`;
export type FirstReminderCardState = "pending" | "dismissed" | null;
export function firstReminderCardState(recipientId: string): FirstReminderCardState {
  try { const v = localStorage.getItem(cardKey(recipientId)); return v === "pending" || v === "dismissed" ? v : null; } catch { return null; }
}
export function setFirstReminderCardState(recipientId: string, value: FirstReminderCardState) {
  try { if (value === null) localStorage.removeItem(cardKey(recipientId)); else localStorage.setItem(cardKey(recipientId), value); } catch { /* storage blocked: the card just does not stay */ }
}
/** After a new medicine is saved: show the first-reminder card on Today (unless it was closed by hand before). */
export function markFirstReminderPending(recipientId: string) {
  if (firstReminderCardState(recipientId) !== "dismissed") setFirstReminderCardState(recipientId, "pending");
}
