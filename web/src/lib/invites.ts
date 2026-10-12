import { supabase } from "./supabase";

/** Family invites (step 9). Every rule is enforced in the database (migration 0017); this file only calls it. */

const KEY = "carelink-invite";
const TOKEN = /^[0-9a-f]{64}$/;

/** An invite link looks like https://host/#/join/<token> (or ?invite=<token>). The token is kept for this browser tab only
 *  (it must survive the login screens) and is removed from the address bar straight away. */
function captureInviteFromUrl() {
  try {
    const fromHash = window.location.hash.match(/^#\/?join\/([^/?#]+)/)?.[1];
    const fromQuery = new URLSearchParams(window.location.search).get("invite");
    const found = fromHash ?? fromQuery;
    if (found === undefined || found === null) return;
    if (TOKEN.test(found)) sessionStorage.setItem(KEY, found);
    const url = new URL(window.location.href);
    url.searchParams.delete("invite");
    url.hash = "";
    history.replaceState(null, "", url.pathname + url.search);
  } catch { /* storage blocked: the link simply will not carry over */ }
}
captureInviteFromUrl();

export const pendingInvite = (): string | null => {
  try { const v = sessionStorage.getItem(KEY); return v && TOKEN.test(v) ? v : null; } catch { return null; }
};
export const clearPendingInvite = () => { try { sessionStorage.removeItem(KEY); } catch { /* ignore */ } };
export const inviteLink = (token: string) => `${window.location.origin}/#/join/${token}`;

export type InviteFailure = "invalid" | "expired" | "revoked" | "used" | "member" | "circle_full" | "too_many_invites" | "no_parent" | "error";

/** The database reports why it refused in the error hint. */
const failure = (err: { hint?: string; code?: string } | null): InviteFailure => {
  const hint = err?.hint;
  return hint === "invalid" || hint === "expired" || hint === "revoked" || hint === "used" || hint === "member" || hint === "circle_full" || hint === "too_many_invites" || hint === "no_parent" ? hint : "error";
};

export type InvitePreview =
  | { status: "ok"; inviter: string | null; parentFirstName: string; relation: string | null; callName: string | null }
  | { status: Exclude<InviteFailure, "circle_full" | "too_many_invites" | "error"> };

export async function previewInvite(token: string): Promise<InvitePreview> {
  const { data, error } = await supabase.rpc("preview_family_invite", { p_token: token });
  if (error || !data || typeof data !== "object" || Array.isArray(data)) throw error ?? new Error("bad preview");
  const d = data as { status: string; inviter?: string | null; parent_first_name?: string; relation?: string | null; call_name?: string | null };
  // A parent that was deleted leaves no name to show: say so clearly instead of "invited you to care for <empty>".
  if (d.status === "ok" && !(d.parent_first_name ?? "").trim() && !(d.call_name ?? "").trim()) return { status: "no_parent" };
  if (d.status === "ok") return { status: "ok", inviter: d.inviter ?? null, parentFirstName: d.parent_first_name ?? "", relation: d.relation ?? null, callName: d.call_name ?? null };
  // Unknown or new statuses (from a newer backend) fall back to the generic "this link does not work".
  return { status: (["expired", "revoked", "used", "member", "no_parent"].includes(d.status) ? d.status : "invalid") as "invalid" | "expired" | "revoked" | "used" | "member" | "no_parent" };
}

/** Returns the parent's id on success, or the reason it was refused. */
export async function acceptInvite(token: string): Promise<{ recipientId: string } | { failure: InviteFailure }> {
  const { data, error } = await supabase.rpc("accept_family_invite", { p_token: token });
  if (error) return { failure: failure(error) };
  return { recipientId: data as string };
}

export async function createInvite(circleId: string): Promise<{ token: string } | { failure: InviteFailure }> {
  const { data, error } = await supabase.rpc("create_family_invite", { p_circle: circleId });
  if (error) return { failure: failure(error) };
  return { token: data as string };
}

export type PendingInvite = { id: string; createdAt: string; expiresAt: string; by: string | null };

export async function loadPendingInvites(circleId: string): Promise<PendingInvite[]> {
  const { data, error } = await supabase
    .from("family_invites").select("id, created_at, expires_at, profiles!family_invites_created_by_fkey(name)")
    .eq("circle_id", circleId).is("revoked_at", null).is("accepted_at", null).gt("expires_at", new Date().toISOString())
    .order("created_at", { ascending: false });
  if (error) throw error;
  return data.map((i) => ({ id: i.id, createdAt: i.created_at, expiresAt: i.expires_at, by: i.profiles?.name ?? null }));
}

export async function revokeInvite(id: string): Promise<boolean> {
  const { error } = await supabase.rpc("revoke_family_invite", { p_invite: id });
  return !error;
}

export async function removeMember(circleId: string, userId: string): Promise<boolean> {
  const { error } = await supabase.rpc("remove_circle_member", { p_circle: circleId, p_user: userId });
  return !error;
}

export async function leaveCircle(circleId: string): Promise<boolean> {
  const { error } = await supabase.rpc("leave_circle", { p_circle: circleId });
  return !error;
}
