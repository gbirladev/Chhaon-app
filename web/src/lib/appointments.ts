import { apiFetch } from "./http";

/** Doctor visits (step 8). Times are entered and shown in the PARENT's time zone and stored in UTC. */
export type Appointment = { id: string; doctor: string; at: string; location: string | null; notes: string | null; remind: boolean };

export async function loadAppointments(recipientId: string): Promise<Appointment[]> {
  return apiFetch<Appointment[]>(`/api/v1/recipients/${recipientId}/appointments`);
}

export type AppointmentInput = { doctor: string; at: string; location: string | null; notes: string | null; remind: boolean };

export async function createAppointment(recipientId: string, input: AppointmentInput): Promise<boolean> {
  try { await apiFetch(`/api/v1/recipients/${recipientId}/appointments`, { method: "POST", body: input }); return true; } catch { return false; }
}

export async function updateAppointment(id: string, input: AppointmentInput): Promise<boolean> {
  try { await apiFetch(`/api/v1/appointments/${id}`, { method: "PUT", body: input }); return true; } catch { return false; }
}

export async function deleteAppointment(id: string): Promise<boolean> {
  try { await apiFetch(`/api/v1/appointments/${id}`, { method: "DELETE" }); return true; } catch { return false; }
}

/** Offset (ms) of a time zone from UTC at a given instant. */
function zoneOffset(instant: number, timeZone: string): number {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-US", { timeZone, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" })
      .formatToParts(new Date(instant)).map((p) => [p.type, p.value]),
  );
  const asUtc = Date.UTC(Number(parts.year), Number(parts.month) - 1, Number(parts.day), Number(parts.hour), Number(parts.minute), Number(parts.second));
  return asUtc - Math.floor(instant / 1000) * 1000;
}

/** "2026-10-17" + "11:00" on the wall clock of `timeZone` -> the UTC instant (ISO). */
export function zonedToUtc(date: string, time: string, timeZone: string): string | null {
  const d = date.match(/^(\d{4})-(\d{2})-(\d{2})$/), t = time.match(/^(\d{2}):(\d{2})$/);
  if (!d || !t) return null;
  const guess = Date.UTC(Number(d[1]), Number(d[2]) - 1, Number(d[3]), Number(t[1]), Number(t[2]));
  let utc = guess - zoneOffset(guess, timeZone);
  utc = guess - zoneOffset(utc, timeZone);  // second pass settles daylight-saving edges
  const out = new Date(utc);
  return Number.isNaN(out.getTime()) ? null : out.toISOString();
}

/** The reverse: a UTC instant -> "YYYY-MM-DD" and "HH:MM" on that zone's wall clock (for the edit form). */
export function utcToZoned(iso: string, timeZone: string): { date: string; time: string } {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-CA", { timeZone, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })
      .formatToParts(new Date(iso)).map((p) => [p.type, p.value]),
  );
  return { date: `${parts.year}-${parts.month}-${parts.day}`, time: `${parts.hour}:${parts.minute}` };
}

/** True when this wall-clock time really exists in the zone (a daylight-saving jump skips an hour). */
export function localTimeExists(date: string, time: string, timeZone: string): boolean {
  const utc = zonedToUtc(date, time, timeZone);
  if (!utc) return false;
  const back = utcToZoned(utc, timeZone);
  return back.date === date && back.time === time;
}

/** "Dr. Mehta" / "Doctor Mehta" / "डॉ. मेहता" -> "Mehta" / "मेहता", so the screen's own "Dr." prefix is never doubled. */
export function doctorName(raw: string): string {
  let name = raw.trim();
  for (let i = 0; i < 3; i++) {
    const next = name.replace(/^(?:dr|doctor|डॉक्टर|डॉ|डाॅ)(?:\.\s*|\s+)/i, "").trim();
    if (next === name) break;
    name = next;
  }
  return name || raw.trim();
}
