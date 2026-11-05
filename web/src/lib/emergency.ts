import { apiFetch } from "./http";

export type EmergencyLink = { token: string; createdAt: string; lastViewedAt: string | null; viewCount: number };

/** The active link of this parent, or null when the link is off (the API answers 204 then). */
export async function loadEmergencyLink(recipientId: string): Promise<EmergencyLink | null> {
  return (await apiFetch<EmergencyLink | undefined>(`/api/v1/recipients/${recipientId}/emergency-link`)) ?? null;
}

/** Idempotent: returns the link that is already on, or makes one. */
export async function enableEmergencyLink(recipientId: string): Promise<EmergencyLink> {
  return apiFetch<EmergencyLink>(`/api/v1/recipients/${recipientId}/emergency-link/enable`, { method: "POST" });
}

export async function revokeEmergencyLink(recipientId: string): Promise<void> {
  await apiFetch(`/api/v1/recipients/${recipientId}/emergency-link/revoke`, { method: "POST" });
}

/** The old address stops working at once. */
export async function rotateEmergencyLink(recipientId: string): Promise<EmergencyLink> {
  return apiFetch<EmergencyLink>(`/api/v1/recipients/${recipientId}/emergency-link/rotate`, { method: "POST" });
}

/** The page lives in the same app (hash route) under /app/, so it works on any static host without server rewrites. */
export const emergencyLinkUrl = (token: string) => `${window.location.origin}/app/#/e/${token}`;
