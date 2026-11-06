/**
 * "Prepared for: Dr <name>, <clinic>" on the doctor summary. The family types it for THEIR OWN doctor; it is never shown to
 * anyone else and never promotes a clinic. Kept on this device only (per parent), not on the server: the first doctor in
 * "Your doctors" (name + clinic) is only the starting value, and the family can change it for one visit.
 */
export const DOCTOR_MAX = 60;
export const CLINIC_MAX = 80;
const PREFIX = "chhaon-doctor-for-";

export type DoctorAddress = { doctor: string; clinic: string };

// Control characters, and the invisible marks that can flip text direction or hide text. Joiners (U+200C/U+200D) stay: Hindi uses them.
// eslint-disable-next-line no-control-regex
const HIDDEN = /[\u0000-\u001F\u007F-\u009F‎‏‪-‮⁦-⁩﻿]/g;

/** While typing: hidden characters removed, length capped (the input's maxLength also caps it). */
export function cleanTyped(value: string, max: number): string {
  return Array.from(value.replace(HIDDEN, " ")).slice(0, max).join("");
}

/** For showing: one line, single spaces, trimmed, capped. */
export function cleanLine(value: string | null | undefined, max: number): string {
  return Array.from((value ?? "").replace(HIDDEN, " ").replace(/\s+/g, " ").trim()).slice(0, max).join("").trim();
}

/** The name without a typed "Dr" / "Doctor" / "डॉ." in front, so the sheet never says "Dr Dr Sharma". */
export function withoutTitle(name: string): string {
  return name.replace(/^(?:dr\.?|doctor|डॉ\.?|डॉक्टर)(?:\s+|\.(?=\S))/iu, "").trim();
}

const parse = (raw: string | null): DoctorAddress | null => {
  if (!raw) return null;
  try {
    const value = JSON.parse(raw) as Partial<DoctorAddress> | null;
    if (!value || typeof value !== "object") return null;
    return { doctor: cleanTyped(String(value.doctor ?? ""), DOCTOR_MAX), clinic: cleanTyped(String(value.clinic ?? ""), CLINIC_MAX) };
  } catch { return null; }
};

/** What this device last used for this parent; otherwise the parent's first doctor from the profile (editable). */
export function loadDoctorAddress(recipientId: string, doctorContacts: unknown): DoctorAddress {
  let saved: DoctorAddress | null = null;
  try { saved = parse(localStorage.getItem(PREFIX + recipientId)); } catch { /* storage blocked */ }
  if (saved) return saved;
  const first = Array.isArray(doctorContacts)
    ? doctorContacts.find((c): c is { name: string; clinic?: unknown } => !!c && typeof c === "object" && typeof (c as { name?: unknown }).name === "string" && !!cleanLine((c as { name: string }).name, DOCTOR_MAX))
    : undefined;
  // "Your doctors" (2026-11-06) also keeps the first doctor's clinic or hospital.
  return { doctor: first ? cleanLine(first.name, DOCTOR_MAX) : "", clinic: first && typeof first.clinic === "string" ? cleanLine(first.clinic, CLINIC_MAX) : "" };
}

export function saveDoctorAddress(recipientId: string, value: DoctorAddress) {
  try { localStorage.setItem(PREFIX + recipientId, JSON.stringify(value)); } catch { /* storage full or blocked: it is only a convenience */ }
}

/** On sign-out: forget every parent's doctor on this device. */
export function forgetDoctorAddresses() {
  try {
    const keys: string[] = [];
    for (let i = 0; i < localStorage.length; i += 1) { const key = localStorage.key(i); if (key?.startsWith(PREFIX)) keys.push(key); }
    keys.forEach((key) => localStorage.removeItem(key));
  } catch { /* storage blocked */ }
}
