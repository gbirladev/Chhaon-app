import { authUserId } from "./auth";
import { loadMe } from "./api";
import { apiFetch } from "./http";

/** Pilot stats for the owner of the app (migration 0028). Totals only: the database never returns names, numbers or emails here. */
export type DailyStat = { date: string; remindersSent: number; remindersTaken: number; newCaregivers: number };
export type PilotStats = {
  generatedAt: string;
  caregivers: { total: number; new7d: number; active7d: number; joined4wAgo: number; stillActiveOfThose: number };
  parents: { total: number; agreed: number; waiting: number; stopped: number; pausedByPlan: number };
  medicines: { active: number; pausedByPlan: number };
  reminders7d: { sent: number; taken: number; missed: number };
  checkins7d: { sent: number; replied: number; missed: number };
  whatsapp7d: { utility: number; marketing: number };
  plans: { byPlan: Record<string, number>; onTrial: number; trialEnding7d: number; trialEndedFree: number };
  family: { membersJoined: number; circlesWithMoreThanOne: number };
  daily: DailyStat[];
};

type Raw = {
  generated_at: string;
  caregivers: { total: number; new_7d: number; active_7d: number; joined_4w_ago: number; still_active_of_those: number };
  parents: { total: number; agreed: number; waiting: number; stopped: number; paused_by_plan: number };
  medicines: { active: number; paused_by_plan: number };
  reminders_7d: { sent: number; taken: number; missed: number };
  checkins_7d: { sent: number; replied: number; missed: number };
  whatsapp_7d: { utility: number; marketing: number };
  plans: { by_plan: Record<string, number>; on_trial: number; trial_ending_7d: number; trial_ended_free: number };
  family: { members_joined: number; circles_with_more_than_one: number };
  daily: { date: string; reminders_sent: number; reminders_taken: number; new_caregivers: number }[];
};

export async function loadPilotStats(days = 14): Promise<PilotStats> {
  const data = await apiFetch<unknown>(`/api/v1/admin/stats?days=${days}`);
  if (!data || typeof data !== "object" || Array.isArray(data)) throw new Error("bad stats");
  const r = data as unknown as Raw;
  return {
    generatedAt: r.generated_at,
    caregivers: { total: r.caregivers.total, new7d: r.caregivers.new_7d, active7d: r.caregivers.active_7d, joined4wAgo: r.caregivers.joined_4w_ago, stillActiveOfThose: r.caregivers.still_active_of_those },
    parents: { total: r.parents.total, agreed: r.parents.agreed, waiting: r.parents.waiting, stopped: r.parents.stopped, pausedByPlan: r.parents.paused_by_plan },
    medicines: { active: r.medicines.active, pausedByPlan: r.medicines.paused_by_plan },
    reminders7d: r.reminders_7d,
    checkins7d: r.checkins_7d,
    whatsapp7d: r.whatsapp_7d,
    plans: { byPlan: r.plans.by_plan, onTrial: r.plans.on_trial, trialEnding7d: r.plans.trial_ending_7d, trialEndedFree: r.plans.trial_ended_free },
    family: { membersJoined: r.family.members_joined, circlesWithMoreThanOne: r.family.circles_with_more_than_one },
    daily: r.daily.map((d) => ({ date: d.date, remindersSent: d.reminders_sent, remindersTaken: d.reminders_taken, newCaregivers: d.new_caregivers })),
  };
}

/** True for the owner's account only (the flag is set by hand; nobody can set it for themselves). */
export async function loadIsAdmin(): Promise<boolean> {
  if (!authUserId()) return false;
  try { return (await loadMe()).isAdmin === true; } catch { return false; }
}
