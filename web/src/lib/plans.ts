import { supabase } from "./supabase";

/** Pricing plans and the free trial (migration 0027). The limits are enforced in the database; this only reads them to show them. */
/** A null limit means unlimited. */
export type PlanInfo = { key: string; name: string; maxParents: number | null; maxMembers: number | null; maxMedicines: number | null; maxDocuments: number | null };
export type PlanOverview = Omit<PlanInfo, "maxDocuments"> & {
  plan: string;          // what the account is on (free / plus / family)
  effective: string;     // what applies right now: a Free account on trial counts as Plus
  onTrial: boolean;
  trialEndsAt: string;
  parentsUsed: number;
  wouldPauseParents: number;     // what the Free plan would pause if the trial ended today (0 when not on trial)
  wouldPauseMedicines: number;
};

export async function loadPlans(): Promise<PlanInfo[]> {
  const { data, error } = await supabase.from("plans").select("key, name, max_parents, max_members, max_medicines, max_documents").order("sort_order");
  if (error) throw error;
  return data.map((p) => ({ key: p.key, name: p.name, maxParents: p.max_parents, maxMembers: p.max_members, maxMedicines: p.max_medicines, maxDocuments: p.max_documents }));
}

export async function loadPlanOverview(): Promise<PlanOverview> {
  const { data, error } = await supabase.rpc("plan_overview");
  if (error || !data || typeof data !== "object" || Array.isArray(data)) throw error ?? new Error("bad plan overview");
  const d = data as { plan: string; effective: string; name: string; max_parents: number | null; max_members: number | null; max_medicines: number | null; on_trial: boolean; trial_ends_at: string; parents_used: number; would_pause_parents: number; would_pause_medicines: number };
  return { key: d.effective, plan: d.plan, effective: d.effective, name: d.name, maxParents: d.max_parents, maxMembers: d.max_members, maxMedicines: d.max_medicines, onTrial: d.on_trial, trialEndsAt: d.trial_ends_at, parentsUsed: d.parents_used, wouldPauseParents: d.would_pause_parents ?? 0, wouldPauseMedicines: d.would_pause_medicines ?? 0 };
}

/** Whole days left, rounded up, and never negative. */
export const daysLeft = (iso: string, now = Date.now()) => Math.max(0, Math.ceil((new Date(iso).getTime() - now) / 86_400_000));

/** Reminders paused because they are above the plan's limits (visible to everyone in the family circle). */
export type PlanPauses = { parentIds: string[]; medicines: { id: string; name: string; recipientId: string }[] };

export async function loadPlanPauses(): Promise<PlanPauses> {
  const { data, error } = await supabase.rpc("plan_pauses");
  if (error || !data || typeof data !== "object" || Array.isArray(data)) throw error ?? new Error("bad plan pauses");
  const d = data as { parents?: { id: string }[]; medicines?: { id: string; name: string; recipient_id: string }[] };
  return { parentIds: (d.parents ?? []).map((p) => p.id), medicines: (d.medicines ?? []).map((m) => ({ id: m.id, name: m.name, recipientId: m.recipient_id })) };
}
