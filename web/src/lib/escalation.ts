import { useEffect, useState } from "react";
import { resolveLocalUrl } from "./hosts";
import { supabase } from "./supabase";

/** The escalation ladder (docs/escalation.md): what happens when a parent does not answer. The server decides; this reads and edits the settings. */
export type Policy = {
  enabled: boolean;
  doseNudgeMin: number; doseAlertMin: number; checkinNudgeMin: number; checkinAlertMin: number;
  alertAfterUnanswered: number; silentStopDays: number;
};
export const DEFAULT_POLICY: Policy = { enabled: false, doseNudgeMin: 15, doseAlertMin: 45, checkinNudgeMin: 60, checkinAlertMin: 120, alertAfterUnanswered: 2, silentStopDays: 3 };

/** Three speeds. The numbers are minutes after the reminder was sent. */
export const PRESETS = {
  gentle: { doseNudgeMin: 20, doseAlertMin: 60, checkinNudgeMin: 90, checkinAlertMin: 180 },
  standard: { doseNudgeMin: 15, doseAlertMin: 45, checkinNudgeMin: 60, checkinAlertMin: 120 },
  fast: { doseNudgeMin: 10, doseAlertMin: 30, checkinNudgeMin: 45, checkinAlertMin: 90 },
} as const;
export type PresetKey = keyof typeof PRESETS;
export const presetOf = (p: Policy): PresetKey | null =>
  (Object.keys(PRESETS) as PresetKey[]).find((k) => PRESETS[k].doseNudgeMin === p.doseNudgeMin && PRESETS[k].doseAlertMin === p.doseAlertMin
    && PRESETS[k].checkinNudgeMin === p.checkinNudgeMin && PRESETS[k].checkinAlertMin === p.checkinAlertMin) ?? null;

type PolicyRow = {
  enabled: boolean; dose_nudge_min: number; dose_alert_min: number; checkin_nudge_min: number; checkin_alert_min: number;
  alert_after_unanswered: number; silent_stop_days: number;
};
const fromRow = (r: PolicyRow): Policy => ({
  enabled: r.enabled, doseNudgeMin: r.dose_nudge_min, doseAlertMin: r.dose_alert_min, checkinNudgeMin: r.checkin_nudge_min,
  checkinAlertMin: r.checkin_alert_min, alertAfterUnanswered: r.alert_after_unanswered, silentStopDays: r.silent_stop_days,
});

export async function loadPolicy(recipientId: string): Promise<Policy> {
  const { data, error } = await supabase.from("escalation_policies").select("*").eq("recipient_id", recipientId).maybeSingle();
  if (error) throw error;
  return data ? fromRow(data as unknown as PolicyRow) : DEFAULT_POLICY;
}

export async function savePolicy(recipientId: string, p: Policy): Promise<boolean> {
  const { error } = await supabase.from("escalation_policies").upsert({
    recipient_id: recipientId, enabled: p.enabled, dose_nudge_min: p.doseNudgeMin, dose_alert_min: p.doseAlertMin,
    checkin_nudge_min: p.checkinNudgeMin, checkin_alert_min: p.checkinAlertMin, alert_after_unanswered: p.alertAfterUnanswered, silent_stop_days: p.silentStopDays,
  }, { onConflict: "recipient_id" });
  return !error;
}

export async function setMedicineCritical(medicineId: string, critical: boolean): Promise<boolean> {
  const { error } = await supabase.rpc("set_medicine_critical", { p_medicine: medicineId, p_critical: critical });
  return !error;
}

/** One unanswered prompt (a medicine time slot or the daily check-in) and what has been done about it so far. */
export type Escalation = { id: string; kind: "dose" | "checkin"; baseAt: string; nudgedAt: string | null; decidedAt: string | null; alertedAt: string | null; resolvedAt: string | null };

export async function loadOpenEscalations(recipientId: string): Promise<Escalation[]> {
  const since = new Date(Date.now() - 24 * 3600_000).toISOString();
  const { data, error } = await supabase.from("escalations")
    .select("id, kind, base_at, nudged_at, decided_at, alerted_at, resolved_at").eq("recipient_id", recipientId).is("resolved_at", null).gte("base_at", since).order("base_at");
  if (error) throw error;
  return data.map((e) => ({ id: e.id, kind: e.kind as "dose" | "checkin", baseAt: e.base_at, nudgedAt: e.nudged_at, decidedAt: e.decided_at, alertedAt: e.alerted_at, resolvedAt: e.resolved_at }));
}

export async function handleEscalation(id: string): Promise<boolean> {
  const { error } = await supabase.rpc("handle_escalation", { p_escalation: id });
  return !error;
}

// ---------------------------------------------------------------- which features the server has switched on
export type Features = { escalation: boolean };
let known: Features | null = null;

export async function loadFeatures(): Promise<Features> {
  if (known) return known;
  try {
    const response = await fetch(`${resolveLocalUrl(import.meta.env.VITE_API_URL)}/features`);
    if (!response.ok) return { escalation: false };
    const body = (await response.json()) as Partial<Features>;
    known = { escalation: body.escalation === true };
    return known;
  } catch {
    return { escalation: false };   // server asleep or unreachable: show nothing rather than a screen that may not work
  }
}

export function useFeatures(): Features {
  const [features, setFeatures] = useState<Features>(known ?? { escalation: false });
  useEffect(() => {
    let cancelled = false;
    void loadFeatures().then((f) => { if (!cancelled) setFeatures(f); });
    return () => { cancelled = true; };
  }, []);
  return features;
}
