import { supabase } from "./supabase";

/** If the reminder system last ran more than this long ago, the family is told that reminders have stopped. */
export const SCHEDULER_STALE_SECONDS = 15 * 60;

/**
 * Seconds since the reminder system (the scheduler) last ran, from its heartbeat (migration 0040).
 * null = no run was ever recorded (nothing to compare with, so no alarm) or the answer could not be read.
 */
export async function loadSchedulerAge(): Promise<number | null> {
  const { data, error } = await supabase.rpc("scheduler_health");
  if (error || !data || typeof data !== "object" || Array.isArray(data)) return null;
  const seconds = (data as { seconds_ago?: unknown }).seconds_ago;
  return typeof seconds === "number" && seconds >= 0 ? seconds : null;
}
