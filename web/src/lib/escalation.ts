import { useEffect, useState } from "react";
import { resolveLocalUrl } from "./hosts";
import { apiFetch } from "./http";

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

export async function loadPolicy(recipientId: string): Promise<Policy> {
  const data = await apiFetch<Policy | undefined>(`/api/v1/recipients/${recipientId}/escalation-policy`);
  return data ?? DEFAULT_POLICY;   // 204 = no row yet
}

export async function savePolicy(recipientId: string, p: Policy): Promise<boolean> {
  try { await apiFetch(`/api/v1/recipients/${recipientId}/escalation-policy`, { method: "PUT", body: p }); return true; } catch { return false; }
}

export async function setMedicineCritical(medicineId: string, critical: boolean): Promise<boolean> {
  try { await apiFetch(`/api/v1/medicines/${medicineId}/critical`, { method: "POST", body: { critical } }); return true; } catch { return false; }
}

/** One unanswered prompt (a medicine time slot or the daily check-in) and what has been done about it so far. */
export type Escalation = { id: string; kind: "dose" | "checkin"; baseAt: string; nudgedAt: string | null; decidedAt: string | null; alertedAt: string | null; resolvedAt: string | null };

export async function loadOpenEscalations(recipientId: string): Promise<Escalation[]> {
  return apiFetch<Escalation[]>(`/api/v1/recipients/${recipientId}/escalations`);
}

export async function handleEscalation(id: string): Promise<boolean> {
  try { await apiFetch(`/api/v1/escalations/${id}/handle`, { method: "POST" }); return true; } catch { return false; }
}

// ---------------------------------------------------------------- which features the server has switched on
export type Features = { escalation: boolean; love: boolean };
const NONE: Features = { escalation: false, love: false };
let known: Features | null = null;

export async function loadFeatures(): Promise<Features> {
  if (known) return known;
  try {
    const response = await fetch(`${resolveLocalUrl(import.meta.env.VITE_API_URL)}/features`);
    if (!response.ok) return NONE;
    const body = (await response.json()) as Partial<Features>;
    known = { escalation: body.escalation === true, love: body.love === true };
    return known;
  } catch {
    return NONE;   // server asleep or unreachable: show nothing rather than a screen that may not work
  }
}

export function useFeatures(): Features {
  const [features, setFeatures] = useState<Features>(known ?? NONE);
  useEffect(() => {
    let cancelled = false;
    void loadFeatures().then((f) => { if (!cancelled) setFeatures(f); });
    return () => { cancelled = true; };
  }, []);
  return features;
}
