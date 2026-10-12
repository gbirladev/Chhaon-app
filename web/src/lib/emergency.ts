import { supabase } from "./supabase";

export type EmergencyLink = { token: string; createdAt: string; lastViewedAt: string | null; viewCount: number };

const toLink = (row: { token: string; created_at: string; last_viewed_at: string | null; view_count: number }): EmergencyLink =>
  ({ token: row.token, createdAt: row.created_at, lastViewedAt: row.last_viewed_at, viewCount: row.view_count });

/** The active link of this parent, or null when the link is off. */
export async function loadEmergencyLink(recipientId: string): Promise<EmergencyLink | null> {
  const { data, error } = await supabase.from("emergency_links").select("token, created_at, last_viewed_at, view_count").eq("recipient_id", recipientId).eq("active", true).maybeSingle();
  if (error) throw error;
  return data ? toLink(data) : null;
}

/** Idempotent: returns the link that is already on, or makes one. */
export async function enableEmergencyLink(recipientId: string): Promise<EmergencyLink> {
  const { data, error } = await supabase.rpc("enable_emergency_link", { p_recipient: recipientId });
  if (error || !data?.[0]) throw error ?? new Error("no link");
  return toLink(data[0]);
}

export async function revokeEmergencyLink(recipientId: string): Promise<void> {
  const { error } = await supabase.rpc("revoke_emergency_link", { p_recipient: recipientId });
  if (error) throw error;
}

/** The old address stops working at once. */
export async function rotateEmergencyLink(recipientId: string): Promise<EmergencyLink> {
  const { data, error } = await supabase.rpc("rotate_emergency_link", { p_recipient: recipientId });
  if (error || !data?.[0]) throw error ?? new Error("no link");
  return toLink(data[0]);
}

/** The page lives in the same app (hash route), so it works on any static host without server rewrites. */
export const emergencyLinkUrl = (token: string) => `${window.location.origin}/#/e/${token}`;
