import { apiFetch } from "./http";
import type { Recipient } from "./api";

/**
 * What the parent answered over the last days, for the doctor summary. Only RESULTS count:
 *  - taken / taken_late            -> confirmed
 *  - missed                        -> no confirmation (the parent did not reply; the dose may still have been taken)
 *  - pending / sent / skipped      -> left out. They are still waiting, were paused, or expired before they were sent,
 *                                     so they must never lower the number.
 */
export type DoseRow = { status: string; medicineId: string; name: string; dosage: string | null; active: boolean };
export type MedicineAdherence = { medicineId: string; name: string; dosage: string | null; active: boolean; confirmed: number; total: number };
export type Adherence = {
  days: number;
  confirmed: number;
  total: number;
  medicines: MedicineAdherence[];
  checkinsAnswered: number;
  checkinsTotal: number;
};

const CONFIRMED = new Set(["taken", "taken_late"]);
const RESULT = new Set(["taken", "taken_late", "missed"]);

export function summarizeDoses(rows: DoseRow[]): { confirmed: number; total: number; medicines: MedicineAdherence[] } {
  const byMedicine = new Map<string, MedicineAdherence>();
  for (const row of rows) {
    if (!RESULT.has(row.status)) continue;
    const entry = byMedicine.get(row.medicineId) ?? { medicineId: row.medicineId, name: row.name, dosage: row.dosage, active: row.active, confirmed: 0, total: 0 };
    entry.total += 1;
    if (CONFIRMED.has(row.status)) entry.confirmed += 1;
    byMedicine.set(row.medicineId, entry);
  }
  // Active medicines first, then the ones with the most missed doses (the doctor's first question), then by name.
  const medicines = [...byMedicine.values()].sort((a, b) =>
    Number(b.active) - Number(a.active) || (b.total - b.confirmed) - (a.total - a.confirmed) || a.name.localeCompare(b.name));
  return {
    confirmed: medicines.reduce((n, m) => n + m.confirmed, 0),
    total: medicines.reduce((n, m) => n + m.total, 0),
    medicines,
  };
}

export function summarizeCheckins(statuses: string[]): { answered: number; total: number } {
  let answered = 0;
  let total = 0;
  for (const status of statuses) {
    if (status === "replied") { answered += 1; total += 1; }
    else if (status === "missed") total += 1;   // pending / sent: still open, not counted
  }
  return { answered, total };
}

export async function loadAdherence(recipient: Recipient, days = 30): Promise<Adherence> {
  const resp = await apiFetch<{ doses: DoseRow[]; checkins: string[] }>(
    `/api/v1/recipients/${recipient.id}/adherence?days=${days}`);
  const doses = summarizeDoses(resp.doses);
  const checks = summarizeCheckins(resp.checkins);
  return { days, ...doses, checkinsAnswered: checks.answered, checkinsTotal: checks.total };
}
