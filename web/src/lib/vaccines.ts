import type { useTranslations } from "use-intl";
import { apiFetch } from "./http";
import type { Recipient } from "./api";
import { formatDate, type Language } from "./format";

/**
 * A child's vaccination plan (docs/use-cases.md, step 3). The timetable itself lives in ONE data file on the backend
 * (api-java/src/main/resources/vaccines/iap-acvip-2023.json, IAP-ACVIP 2023, to be reviewed by a paediatrician before release);
 * the API sends the names, the age group and a note key with every dose. Every date is a suggestion "as advised by your doctor"
 * and the family can change it. No analytics or tracking on child profiles: nothing here sends anything anywhere else.
 */

export type ChildInfo = {
  dob: string | null; guardianConfirmedAt: string | null; careType: string | null; isSelf: boolean;
  planDoses: number; edition: string; source: string; reviewedByPaediatrician: boolean;
  /** The last weight the family recorded (migration 0052), a plain fact; absent from an older backend. */
  weightKg?: number | null; weightOn?: string | null;
};

export type VaccineStatus = "given" | "not_given" | "overdue" | "due_soon" | "later";

export type Vaccine = {
  id: string; key: string; vaccine: string; vaccineEn: string; vaccineHi: string; dose: string; group: string | null;
  note: string | null; suggestedDueDate: string; dueDate: string; dueDateByFamily: boolean; givenOn: string | null;
  notGiven: boolean; notes: string | null; status: VaccineStatus; edition: string;
  /** Given, but the date was not recorded ("already given?" step): givenOn is only the plan date, never shown as the date given. */
  givenDateUnknown?: boolean;
  /** The date has passed and the family has not said "not given yet": ask "was it given?" instead of calling it overdue. */
  askIfGiven?: boolean;
};

export type VaccineChange = { status?: "given" | "not_given" | "due"; givenOn?: string; dueDate?: string; notes?: string; resetDueDate?: boolean };

export const loadChild = (recipientId: string) => apiFetch<ChildInfo>(`/api/v1/recipients/${recipientId}/child`);

/** Saves the date of birth and the "I am the parent or legal guardian" confirmation (needed the first time). */
export const saveChild = (recipientId: string, dob: string, guardianConfirmed: boolean) =>
  apiFetch<ChildInfo>(`/api/v1/recipients/${recipientId}/child`, { method: "PUT", body: { dob, guardianConfirmed } });

/** Records the child's weight (0.5 to 200 kg) and the day it was measured; null clears it. The guardian confirmation comes first. */
export const saveChildWeight = (recipientId: string, weightKg: number | null, weightOn: string | null) =>
  apiFetch<ChildInfo>(`/api/v1/recipients/${recipientId}/child/weight`, { method: "PUT", body: { weightKg, weightOn } });

export const loadVaccines =(recipientId: string) => apiFetch<Vaccine[]>(`/api/v1/recipients/${recipientId}/vaccines`);

/** Makes the plan from the date of birth. Safe to call again: only missing doses are added. */
export const makeVaccinePlan = (recipientId: string) => apiFetch<Vaccine[]>(`/api/v1/recipients/${recipientId}/vaccines/plan`, { method: "POST" });

/** "Which ones were already given?": marks past doses given (date not recorded) and others "not given yet", in one call. */
export const vaccinesGivenEarlier = (recipientId: string, given: string[], notGivenYet: string[]) =>
  apiFetch<Vaccine[]>(`/api/v1/recipients/${recipientId}/vaccines/given-earlier`, { method: "POST", body: { given, notGivenYet } });

export const updateVaccine = (id: string, change: VaccineChange) => apiFetch<Vaccine>(`/api/v1/vaccines/${id}`, { method: "PUT", body: change });

/**
 * Is this profile a child? Reads `care_type` defensively: the column comes with the care-type branch (migration 0043).
 * TODO(care-type merge): once lib/api.ts maps careType onto the Recipient, this keeps working unchanged; if it maps it under another
 * name, change it here only.
 */
export function isChild(recipient: Recipient | null | undefined): boolean {
  const r = recipient as unknown as { care_type?: string | null; careType?: string | null } | null | undefined;
  return !!r && !(recipient as Recipient).is_self && (r.care_type ?? r.careType) === "child";
}

/** "2026-11-05" today in the child's time zone. */
export function localToday(timeZone: string): string {
  const parts = Object.fromEntries(new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" })
    .formatToParts(new Date()).map((p) => [p.type, p.value]));
  return `${parts.year}-${parts.month}-${parts.day}`;
}

/** A plain date ("2026-11-05") as a Date at UTC midnight, to format with timeZone "UTC" (never shifts a day). */
export const plainDate = (iso: string) => new Date(`${iso}T00:00:00Z`);

/** The age group of the table ("birth", "6w", "16-18m", "4-6y") as parts the screen can word in each language. */
export function ageGroup(group: string | null): { kind: "birth" } | { kind: "weeks" | "months" | "years"; from: number; to?: number } | null {
  if (!group) return null;
  if (group === "birth") return { kind: "birth" };
  const m = group.match(/^(\d+)(?:-(\d+))?([wmy])$/);
  if (!m) return null;
  const kind = m[3] === "w" ? "weeks" : m[3] === "m" ? "months" : "years";
  return { kind, from: Number(m[1]), to: m[2] ? Number(m[2]) : undefined };
}

/** A plain date in the user's language: "5 Nov 2026". */
export const fmtPlainDate = (iso: string, language: Language) =>
  formatDate(plainDate(iso), language, { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });

export type VaccineT = ReturnType<typeof useTranslations<"vaccines">>;

/** "Dose 1", "Booster 1", "Booster", or nothing for a single dose. */
export function doseText(t: VaccineT, dose: string | null | undefined): string {
  if (!dose) return "";
  const b = dose.match(/^B(\d*)$/);
  if (b) return b[1] ? t("boosterN", { n: b[1] }) : t("booster");
  return t("dose", { n: dose });
}

/** Text of a "vaccine_due" notification (made by the backend a few days before a due date). */
export function vaccineDueText(payload: unknown, language: Language, call: string, t: VaccineT): string {
  const p = (payload ?? {}) as { vaccine_en?: string; vaccine_hi?: string; dose?: string; due_date?: string };
  const name = language === "hi" && p.vaccine_hi ? p.vaccine_hi : p.vaccine_en;
  if (!name || !p.due_date || !/^\d{4}-\d{2}-\d{2}$/.test(p.due_date)) return t("notifDueGeneric", { call });
  const dose = doseText(t, p.dose);
  return t("notifDue", { call, vaccine: dose ? `${name} · ${dose}` : name, date: fmtPlainDate(p.due_date, language) });
}

// ---------------------------------------------------------------- date of birth + guardian confirmation (child profiles)

export type ChildDetails = { dob: string; guardian: boolean };

/** Errors of the child details, by field id (empty = fine). `today` is the child's local date ("YYYY-MM-DD"). */
export function childDetailsErrors(details: ChildDetails, today: string, guardianAlreadyConfirmed: boolean, text: { errDob: string; errGuardian: string }): Record<string, string> {
  const found: Record<string, string> = {};
  const [y, m, d] = today.split("-").map(Number);
  const eighteenYearsAgo = `${String(y - 18).padStart(4, "0")}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(details.dob) || details.dob > today || details.dob <= eighteenYearsAgo) found["child-dob"] = text.errDob;
  if (!details.guardian && !guardianAlreadyConfirmed) found["child-guardian"] = text.errGuardian;
  return found;
}

/** Saves the child details after the profile itself was saved (for the add/edit form once care-type is merged). */
export const saveChildDetails = (recipientId: string, dob: string) => saveChild(recipientId, dob, true);
