import { localDate } from "./format";
import type { Recipient } from "./api";
import type { UsualCheck } from "./pregnancyChecks";

/** Who the family cares for (migration 0043). Every profile made before it is "older". Child is not offered yet. */
export type CareType = "older" | "pregnant" | "child";

export const careTypeOf = (recipient: Pick<Recipient, "care_type" | "is_self"> | null | undefined): CareType =>
  recipient && !recipient.is_self && (recipient.care_type === "pregnant" || recipient.care_type === "child") ? recipient.care_type : "older";

export const isPregnancy = (recipient: Pick<Recipient, "care_type" | "is_self"> | null | undefined) => careTypeOf(recipient) === "pregnant";

/** Days in a pregnancy from the first day of the last period to the due date (the backend uses the same number). */
export const TERM_DAYS = 280;
/** The due date a family can type: up to about 10 months ahead, at most about 2 months past (the backend checks the same). */
export const DUE_MAX_AHEAD_DAYS = 300;
export const DUE_MAX_PAST_DAYS = 60;

const DAY_MS = 86_400_000;
const parseDay = (ymd: string) => {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(ymd);
  return m ? Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : NaN;
};

/** Whole days from `from` to `to` ("YYYY-MM-DD" each); NaN when either is not a date. */
export const daysBetween = (from: string, to: string) => Math.round((parseDay(to) - parseDay(from)) / DAY_MS);

/** "YYYY-MM-DD" that is `days` after `ymd`. */
export const addDays = (ymd: string, days: number) => new Date(parseDay(ymd) + days * DAY_MS).toISOString().slice(0, 10);

/** Today's date on the PERSON's clock (their time zone, not the phone's). */
export const todayIn = (timeZone: string) => localDate(new Date(), timeZone);

export type PregnancyStatus = {
  /** Completed weeks ("24 weeks + 3 days" is week 24), or null when it would not make sense (before week 1, after week 42). */
  week: number | null;
  trimester: 1 | 2 | 3 | null;
  /** Days until the due date; 0 on the day, negative after it. */
  daysToGo: number;
};

/**
 * Week, trimester and days to go from the due date, on `today` (the person's local date). Week = 40 - weeks until due,
 * counted in completed weeks, the same as the WhatsApp text (api-java recipient/Pregnancy.java).
 */
export function pregnancyStatus(dueDate: string, today: string): PregnancyStatus | null {
  const daysToGo = daysBetween(today, dueDate);
  if (Number.isNaN(daysToGo)) return null;
  const gestation = TERM_DAYS - daysToGo;
  const week = gestation < 7 || gestation >= 43 * 7 ? null : Math.floor(gestation / 7);
  const trimester = week === null ? null : week < 14 ? 1 : week < 28 ? 2 : 3;
  return { week, trimester, daysToGo };
}

/** The date on which the pregnancy reaches the start of `week`, for a due date. */
export const dateOfWeek = (dueDate: string, week: number) => addDays(dueDate, week * 7 - TERM_DAYS);

/** True when a typed due date is inside the window the backend accepts. */
export function dueDateOk(dueDate: string, today: string): boolean {
  const days = daysBetween(today, dueDate);
  return !Number.isNaN(days) && days <= DUE_MAX_AHEAD_DAYS && days >= -DUE_MAX_PAST_DAYS;
}

/** The suggested date: the middle of the window, but never before today (and never past the window's end). */
export function suggestDate(check: UsualCheck, dueDate: string, today: string): string | null {
  if (check.from === undefined || check.to === undefined) return null;
  const start = dateOfWeek(dueDate, check.from);
  const end = addDays(dateOfWeek(dueDate, check.to + 1), -1);
  const middle = dateOfWeek(dueDate, Math.floor((check.from + check.to) / 2));
  if (daysBetween(today, end) < 0) return null;   // the window has passed
  const earliest = daysBetween(today, start) > 0 ? start : addDays(today, 1);
  const pick = daysBetween(earliest, middle) > 0 ? middle : earliest;
  return daysBetween(pick, end) < 0 ? end : pick;
}

/** "पहला", "दूसरा", ... "24वाँ": the Hindi ordinal used in the WhatsApp preview (same as MessageTexts.hindiOrdinal). */
export function hindiOrdinal(n: number): string {
  return ({ 1: "पहला", 2: "दूसरा", 3: "तीसरा", 4: "चौथा", 6: "छठा" } as Record<number, string>)[n] ?? `${n}वाँ`;
}
