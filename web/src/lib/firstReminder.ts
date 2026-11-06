import { zonedToUtc } from "./appointments";
import { localDate } from "./format";

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
