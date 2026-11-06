/**
 * "Your doctors": the family's own list of the person's doctors (stored in care_recipients.doctor_contacts, at most 5).
 * Only what the family typed: Chhaon never suggests, ranks or recommends a doctor, and never asks for anyone's location
 * (Directions opens a Google Maps search of the typed address in a new tab).
 */
import { cleanLine } from "./doctorAddress";

export const DOCTORS_MAX = 5;
export const NAME_MAX = 60;
export const SPECIALITY_MAX = 40;
export const CLINIC_MAX = 80;
export const ADDRESS_MAX = 200;

export type Doctor = { name: string; speciality: string; clinic: string; phone: string; address: string };

const str = (value: unknown, max: number) => (typeof value === "string" ? cleanLine(value, max) : "");

/** The stored list, read defensively (an old row may be only a name, or only a phone, or a plain string). */
export function doctorsOf(raw: unknown): Doctor[] {
  if (!Array.isArray(raw)) return [];
  return raw.flatMap((item): Doctor[] => {
    if (typeof item === "string") { const name = str(item, NAME_MAX); return name ? [{ name, speciality: "", clinic: "", phone: "", address: "" }] : []; }
    if (!item || typeof item !== "object") return [];
    const o = item as Record<string, unknown>;
    const doctor = { name: str(o.name, NAME_MAX), speciality: str(o.speciality, SPECIALITY_MAX), clinic: str(o.clinic, CLINIC_MAX), phone: str(o.phone, 20), address: str(o.address, ADDRESS_MAX) };
    return doctor.name ? [doctor] : [];   // the name is required (a nameless old row is left out, like on the card)
  });
}

/** What the backend takes: empty fields as null. */
export const toPayload = (list: Doctor[]) => list.map((d) => ({
  name: d.name, speciality: d.speciality || null, clinic: d.clinic || null, phone: d.phone || null, address: d.address || null,
}));

/** A Google Maps search of the typed address (with the clinic name, which helps the search). Never the user's location. */
export const directionsUrl = (d: Pick<Doctor, "clinic" | "address">) =>
  `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent([d.clinic, d.address].filter(Boolean).join(", "))}`;

/** "Dr Mehta (Heart)" for a share text or the emergency card. */
export const doctorLine = (d: Pick<Doctor, "name" | "speciality">) => (d.speciality ? `${d.name} (${d.speciality})` : d.name);
