import { apiFetch, ApiError } from "./http";

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
export const inviteLink = (token: string) => `${window.location.origin}/app/#/join/${token}`;

export type InviteFailure = "invalid" | "expired" | "revoked" | "used" | "member" | "circle_full" | "too_many_invites" | "no_parent" | "error";

/** The database reports why it refused in the error hint, which the Java API relays as the error detail. */
const failure = (err: unknown): InviteFailure => {
  const hint = err instanceof ApiError ? err.message : (err as { hint?: string } | null)?.hint;
  return hint === "invalid" || hint === "expired" || hint === "revoked" || hint === "used" || hint === "member" || hint === "circle_full" || hint === "too_many_invites" || hint === "no_parent" ? hint : "error";
};

export type InvitePreview =
  | { status: "ok"; inviter: string | null; parentFirstName: string; relation: string | null; callName: string | null }
  | { status: Exclude<InviteFailure, "circle_full" | "too_many_invites" | "error"> };

export async function previewInvite(token: string): Promise<InvitePreview> {
  const data = await apiFetch<unknown>("/api/v1/invites/preview", { method: "POST", body: { token } });
  if (!data || typeof data !== "object" || Array.isArray(data)) throw new Error("bad preview");
  const d = data as { status: string; inviter?: string | null; parent_first_name?: string; relation?: string | null; call_name?: string | null };
  // A parent that was deleted leaves no name to show: say so clearly instead of "invited you to care for <empty>".
  if (d.status === "ok" && !(d.parent_first_name ?? "").trim() && !(d.call_name ?? "").trim()) return { status: "no_parent" };
  if (d.status === "ok") return { status: "ok", inviter: d.inviter ?? null, parentFirstName: d.parent_first_name ?? "", relation: d.relation ?? null, callName: d.call_name ?? null };
  // Unknown or new statuses (from a newer backend) fall back to the generic "this link does not work".
  return { status: (["expired", "revoked", "used", "member", "no_parent"].includes(d.status) ? d.status : "invalid") as "invalid" | "expired" | "revoked" | "used" | "member" | "no_parent" };
}

/** Returns the parent's id on success, or the reason it was refused. */
export async function acceptInvite(token: string): Promise<{ recipientId: string } | { failure: InviteFailure }> {
  try {
    const d = await apiFetch<{ recipientId: string }>("/api/v1/invites/accept", { method: "POST", body: { token } });
    return { recipientId: d.recipientId };
  } catch (e) { return { failure: failure(e) }; }
}

export async function createInvite(circleId: string): Promise<{ token: string } | { failure: InviteFailure }> {
  try {
    return await apiFetch<{ token: string }>(`/api/v1/circles/${circleId}/invites`, { method: "POST" });
  } catch (e) { return { failure: failure(e) }; }
}

export type PendingInvite = { id: string; createdAt: string; expiresAt: string; by: string | null };

export async function loadPendingInvites(circleId: string): Promise<PendingInvite[]> {
  const rows = await apiFetch<{ id: string; createdAt: string; expiresAt: string; by: string }[]>(`/api/v1/circles/${circleId}/invites`);
  return rows.map((i) => ({ id: i.id, createdAt: i.createdAt, expiresAt: i.expiresAt, by: i.by || null }));
}

export async function revokeInvite(id: string): Promise<boolean> {
  try { await apiFetch(`/api/v1/invites/${id}`, { method: "DELETE" }); return true; } catch { return false; }
}

export async function removeMember(circleId: string, userId: string): Promise<boolean> {
  try { await apiFetch(`/api/v1/circles/${circleId}/members/${userId}`, { method: "DELETE" }); return true; } catch { return false; }
}

export async function leaveCircle(circleId: string): Promise<boolean> {
  try { await apiFetch(`/api/v1/circles/${circleId}/leave`, { method: "POST" }); return true; } catch { return false; }
}
