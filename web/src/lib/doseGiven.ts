// "Mark as given" on Today (migration 20261105000047): a family member marks a waiting / missed dose as given in person or as
// told on the phone. The backend records who and how, uses stock like a WhatsApp "1" and allows Undo for 10 minutes.
import { apiFetch } from "./http";
import type { TodayDose } from "./api";

export type ConfirmSource = "whatsapp" | "caregiver_in_person" | "caregiver_told";
export type GivenSource = Exclude<ConfirmSource, "whatsapp">;
export type MarkResult = "marked" | "already" | "not_allowed" | "too_early" | "too_old";
export type UndoResult = "undone" | "not_marked" | "not_yours" | "too_late";

/** How long the family member who marked a dose can take it back (the backend enforces it too). */
export const UNDO_MS = 10 * 60 * 1000;
/** A dose that is not yet waiting can be marked at most this early (same rule as the backend). */
const EARLY_MS = 60 * 60 * 1000;

export const isManual = (source: string | null | undefined): source is GivenSource =>
  source === "caregiver_in_person" || source === "caregiver_told";

/** A waiting (pending/sent), not-sent (skipped) or missed dose that is due, or due within the hour. */
export function canMarkGiven(d: TodayDose, now = Date.now()) {
  if (!["pending", "sent", "skipped", "missed"].includes(d.status)) return false;
  return new Date(d.scheduledAt).getTime() - now <= EARLY_MS;
}

/** Undo is offered to the family member who marked it, for 10 minutes. */
export function canUndo(d: TodayDose, userId: string | null, now = Date.now()) {
  return isManual(d.confirmSource) && !!userId && d.confirmedBy === userId && !!d.respondedAt
    && now - new Date(d.respondedAt).getTime() < UNDO_MS;
}

export async function markDoseGiven(doseId: string, source: GivenSource): Promise<{ result: MarkResult; source?: string }> {
  return apiFetch(`/api/v1/doses/${doseId}/given`, { method: "POST", body: { source } });
}

export async function undoDoseGiven(doseId: string): Promise<{ result: UndoResult }> {
  return apiFetch(`/api/v1/doses/${doseId}/given/undo`, { method: "POST" });
}
