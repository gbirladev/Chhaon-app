// The few facts a paramedic or doctor must see first (round 2, R3): a pregnancy (week + due date) and a child (date of birth, age,
// last recorded weight). Shared by the in-app emergency card, the public emergency card and the doctor summary.
// Deliberately imports nothing that touches the Supabase session (the public card uses it). Facts only: no judgement, no advice.

/** A plain date ("2026-06-08") as a UTC timestamp, or NaN. */
const parseDay = (ymd: string | null | undefined) => {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(ymd ?? "");
  return m ? Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : NaN;
};

/** Today on a time zone's clock as "YYYY-MM-DD" (falls back to India for an unknown zone). */
export function todayOn(timeZone: string | null | undefined): string {
  const make = (zone: string) => {
    const parts = Object.fromEntries(new Intl.DateTimeFormat("en-CA", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit" })
      .formatToParts(new Date()).map((p) => [p.type, p.value]));
    return `${parts.year}-${parts.month}-${parts.day}`;
  };
  try { return make(timeZone || "Asia/Kolkata"); } catch { return make("Asia/Kolkata"); }
}

/** "25 Feb 2027" (or "1 Nov" with withYear false), in English or Hindi, never shifted by a time zone. */
export function plainDate(ymd: string, language: string, withYear = true): string {
  const at = parseDay(ymd);
  if (Number.isNaN(at)) return ymd;
  return new Intl.DateTimeFormat(language === "hi" ? "hi-IN" : "en-GB", { day: "numeric", month: "short", ...(withYear ? { year: "numeric" } : {}), timeZone: "UTC" })
    .format(at);
}

/** Completed weeks of a pregnancy from the due date (280 days), like lib/pregnancy.ts; null before week 1 or after week 42. */
export function pregnancyWeek(dueDate: string | null | undefined, today: string): number | null {
  const due = parseDay(dueDate);
  const now = parseDay(today);
  if (Number.isNaN(due) || Number.isNaN(now)) return null;
  const gestation = 280 - Math.round((due - now) / 86_400_000);
  return gestation < 7 || gestation >= 43 * 7 ? null : Math.floor(gestation / 7);
}

/** Whole years between a date of birth and today (null when either is not a date or the birth is in the future). */
export function yearsSince(dob: string | null | undefined, today: string): number | null {
  const b = /^(\d{4})-(\d{2})-(\d{2})/.exec(dob ?? "");
  const t = /^(\d{4})-(\d{2})-(\d{2})/.exec(today);
  if (!b || !t) return null;
  let years = Number(t[1]) - Number(b[1]);
  if (Number(t[2]) * 100 + Number(t[3]) < Number(b[2]) * 100 + Number(b[3])) years -= 1;
  return years >= 0 ? years : null;
}

export type ChildAge = { unit: "days" | "weeks" | "months" | "years"; n: number };

/** A child's age the way people say it: days for a newborn, weeks under a month, months under 2 years, then years. */
export function childAge(dob: string | null | undefined, today: string): ChildAge | null {
  const b = /^(\d{4})-(\d{2})-(\d{2})/.exec(dob ?? "");
  const t = /^(\d{4})-(\d{2})-(\d{2})/.exec(today);
  if (!b || !t) return null;
  const days = Math.round((parseDay(today) - parseDay(dob)) / 86_400_000);
  if (days < 0) return null;
  let months = (Number(t[1]) - Number(b[1])) * 12 + Number(t[2]) - Number(b[2]);
  if (Number(t[3]) < Number(b[3])) months -= 1;
  if (months < 1) return days < 14 ? { unit: "days", n: days } : { unit: "weeks", n: Math.floor(days / 7) };
  if (months < 24) return { unit: "months", n: months };
  return { unit: "years", n: Math.floor(months / 12) };
}

export type KeyFacts = {
  pregnancy: { week: number | null; dueDate: string | null } | null;
  child: { born: string | null; weightKg: number | null; weightOn: string | null } | null;
};

/** Translator scoped to the "facts" messages. */
export type FactsT = (key: string, values?: Record<string, string | number>) => string;

/** "1 kg" style number: at most 2 decimals, no trailing zeros, the language's decimal mark. */
export const kg = (value: number, language: string) =>
  new Intl.NumberFormat(language === "hi" ? "hi-IN" : "en-IN", { maximumFractionDigits: 2 }).format(value);

/**
 * The lines to show at the top, in order: "PREGNANT · 24 weeks (due 25 Feb 2027)", "Born 8 Jun 2026 (5 months)",
 * "Weight 6.8 kg, on 1 Nov". Empty when there is nothing to say.
 */
export function keyFactLines(facts: KeyFacts, tf: FactsT, language: string, today: string): string[] {
  const lines: string[] = [];
  const p = facts.pregnancy;
  if (p) {
    const due = p.dueDate ? plainDate(p.dueDate, language) : null;
    lines.push(p.week !== null && due ? tf("pregnantWeek", { week: p.week, date: due })
      : due ? tf("pregnantDue", { date: due }) : tf("pregnant"));
  }
  const c = facts.child;
  if (c) {
    const age = childAge(c.born, today);
    if (c.born) lines.push(age ? tf("born", { date: plainDate(c.born, language), age: tf(`age.${age.unit}`, { n: age.n }) }) : tf("bornOnly", { date: plainDate(c.born, language) }));
    if (c.weightKg !== null && c.weightKg !== undefined) {
      const sameYear = !!c.weightOn && c.weightOn.slice(0, 4) === today.slice(0, 4);
      lines.push(c.weightOn ? tf("weight", { kg: kg(c.weightKg, language), date: plainDate(c.weightOn, language, !sameYear) }) : tf("weightNoDate", { kg: kg(c.weightKg, language) }));
    }
  }
  return lines;
}
