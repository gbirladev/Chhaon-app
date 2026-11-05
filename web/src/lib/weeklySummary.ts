import { apiFetch } from "./http";

/**
 * The "This week" card on the Family tab (feature F3, docs/weekly-summary.md): the same numbers as the Sunday WhatsApp summary to
 * the family. Read-only; the backend counts the 7 full days before today on the person's clock.
 */

export type WeekMissed = { medicine: string; day: string; part: "morning" | "afternoon" | "evening" | "night" };
export type WeekGiver = { kind: "you" | "person" | "helper" | "member" | "family"; name: string | null; count: number };

export type WeekSummary = {
  recipientId: string; name: string; zone: string; from: string; to: string; pregnancyWeek: number | null;
  confirmed: number; total: number; missed: WeekMissed[]; givers: WeekGiver[]; refills: { medicine: string; left: number }[];
  nextVisit: { doctor: string; at: string } | null;
  nextVaccine: { en: string; hi: string; dose: string | null; due: string } | null;
  checkinsAnswered: number; checkinsTotal: number;
};

export const loadWeekSummary = (recipientId: string) => apiFetch<WeekSummary>(`/api/v1/recipients/${recipientId}/weekly-summary`);

/** Nothing to show: no counted dose and no check-in that week (the WhatsApp summary leaves such a person out too). */
export const weekIsQuiet = (w: WeekSummary) => w.total === 0 && w.checkinsTotal === 0;

/** The "Given by" line only says something when someone other than the person gave a dose. */
export const showGivers = (w: WeekSummary) => w.givers.some((g) => g.kind !== "person");
