// Presets the caregiver picks; the scheduler uses the stored clock time in the parent's timezone.
export const PRESET_TIMES = {
  morning: "08:00",
  afternoon: "13:00",
  night: "20:00",
} as const;

export type PresetKey = keyof typeof PRESET_TIMES;

/** "08:00:00" -> "morning" label key, or a formatted clock time for custom times. */
export function describeTime(time: string, t: (key: PresetKey) => string): string {
  const hhmm = time.slice(0, 5);
  const preset = (Object.keys(PRESET_TIMES) as PresetKey[]).find((k) => PRESET_TIMES[k] === hhmm);
  if (preset) return t(preset);
  const [h, m] = hhmm.split(":").map(Number);
  const suffix = h >= 12 ? "PM" : "AM";
  return `${h % 12 || 12}:${String(m).padStart(2, "0")} ${suffix}`;
}
