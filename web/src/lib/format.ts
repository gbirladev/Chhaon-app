import type { useTranslations } from "use-intl";
import { PRESET_TIMES, type Preset } from "./api";

export type T = ReturnType<typeof useTranslations>;
export type Language = "en" | "hi";

const intlLocale = (language: string) => (language === "hi" ? "hi-IN" : "en-IN");

/** Hour/minute -> {time:"8:00", suffix:"AM"} in English, {time:"8:00", suffix:"सुबह"} in Hindi (people say "सुबह 8 बजे"). */
export function hmParts(hour: number, minute: number, language: string): { time: string; suffix: string } {
  const h = ((hour % 24) + 24) % 24;
  const time = `${h % 12 || 12}:${String(minute).padStart(2, "0")}`;
  if (language === "hi") return { time, suffix: h >= 4 && h < 12 ? "सुबह" : h >= 12 && h < 16 ? "दोपहर" : h >= 16 && h < 20 ? "शाम" : "रात" };
  return { time, suffix: h < 12 ? "AM" : "PM" };
}

const joinClock = ({ time, suffix }: { time: string; suffix: string }, language: string) => (language === "hi" ? `${suffix} ${time}` : `${time} ${suffix}`);

/** "07:30:00" (a schedule time) -> "7:30 AM" / "सुबह 7:30". */
export function clockLabel(time: string, language: string) {
  const [h, m] = time.slice(0, 5).split(":").map(Number);
  return joinClock(hmParts(h, m, language), language);
}

/** The wall-clock time of an instant in a given timezone (the PARENT's), split for display. */
export function clockParts(date: Date, timeZone: string, language: string) {
  const parts = new Intl.DateTimeFormat("en-GB", { timeZone, hour: "numeric", minute: "2-digit", hour12: false }).formatToParts(date);
  const get = (type: string) => Number(parts.find((p) => p.type === type)?.value ?? 0);
  return hmParts(get("hour"), get("minute"), language);
}

export const formatClock = (date: Date, timeZone: string, language: string) => joinClock(clockParts(date, timeZone, language), language);

/** YYYY-MM-DD of an instant in a timezone. */
export const localDate = (date: Date, timeZone: string) =>
  new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).format(date);

/** ["08:00:00","20:00:00"] -> "Morning & Night" / "सुबह और रात" */
export function describeTimes(times: string[], t: T, language: string) {
  const presets = Object.entries(PRESET_TIMES) as [Preset, string][];
  return [...times]
    .sort()
    .map((time) => {
      const preset = presets.find(([, value]) => value === time.slice(0, 5))?.[0];
      return preset ? t(`add.${preset.toLowerCase()}`) : clockLabel(time, language);
    })
    .join(t("common.and"));
}

/** Stored dose text is canonical English ("500 mg", "1.5 tablets"); show it in the UI language. */
export function localizeDose(dose: string, t: T) {
  const match = /^([\d.]+)\s+(\w+?)s?$/.exec(dose.trim());
  const unit = match?.[2];
  if (!match || !unit || !["mg", "mcg", "g", "ml", "tablet", "capsule", "drop", "unit", "puff", "tsp"].includes(unit)) return dose;
  const key = unit === "drop" ? "drops" : unit === "unit" ? "units" : unit === "puff" ? "puffs" : unit;
  return `${match[1]} ${t(`units.${key}`, { count: Number(match[1]) })}`;
}

export function formatDate(date: Date, language: string, options: Intl.DateTimeFormatOptions) {
  return new Intl.DateTimeFormat(intlLocale(language), options).format(date);
}

/** Minutes since midnight on the given time zone's wall clock. */
export function localMinutes(date: Date, timeZone: string): number {
  const parts = Object.fromEntries(new Intl.DateTimeFormat("en-US", { timeZone, hourCycle: "h23", hour: "2-digit", minute: "2-digit" }).formatToParts(date).map((p) => [p.type, p.value]));
  return Number(parts.hour) * 60 + Number(parts.minute);
}
