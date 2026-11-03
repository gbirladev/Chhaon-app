import { supabase } from "./supabase";
import { localDate } from "./format";
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

// PostgREST returns at most 1000 rows per request (the hosted default), so the history is read in pages.
const PAGE = 1000;
const MAX_PAGES = 10;

export async function loadAdherence(recipient: Recipient, days = 30): Promise<Adherence> {
  const now = new Date();
  const from = new Date(now.getTime() - days * 24 * 3600_000);
  const rows: DoseRow[] = [];
  for (let page = 0; page < MAX_PAGES; page++) {
    const { data, error } = await supabase
      .from("dose_events")
      .select("id, status, scheduled_at, medicine_schedules!inner(medicines!inner(id, recipient_id, name, dosage, active))")
      .eq("medicine_schedules.medicines.recipient_id", recipient.id)
      .gte("scheduled_at", from.toISOString())
      .lte("scheduled_at", now.toISOString())
      .order("scheduled_at", { ascending: true })
      .order("id", { ascending: true })   // a stable order, so no row is read twice or skipped between pages
      .range(page * PAGE, page * PAGE + PAGE - 1);
    if (error) throw error;
    for (const d of data) {
      const m = d.medicine_schedules.medicines;
      rows.push({ status: d.status, medicineId: m.id, name: m.name, dosage: m.dosage, active: m.active });
    }
    if (data.length < PAGE) break;
  }

  const { data: checkins, error: checkinError } = await supabase
    .from("checkins").select("status")
    .eq("recipient_id", recipient.id)
    .gte("date", localDate(from, recipient.timezone))
    .lte("date", localDate(now, recipient.timezone));
  if (checkinError) throw checkinError;

  const doses = summarizeDoses(rows);
  const checks = summarizeCheckins(checkins.map((c) => c.status));
  return { days, ...doses, checkinsAnswered: checks.answered, checkinsTotal: checks.total };
}
