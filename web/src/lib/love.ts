import { apiFetch, ApiError } from "./http";
import { authUserId } from "./auth";

/** The love loop (docs/love-loop.md): small warm reactions that reach the parent on WhatsApp. The server sends them; this creates and reads them. */
export const EMOJIS = ["❤️", "🙏", "🤗"] as const;
export const PRESET_KEYS = ["love_you", "thank_you", "talk_tonight"] as const;
export type PresetKey = (typeof PRESET_KEYS)[number];
export const MAX_NOTE = 150;

export type LoveSettings = { enabled: boolean; parentOptedOut: boolean; control: boolean };
export const NO_LOVE: LoveSettings = { enabled: false, parentOptedOut: false, control: false };

export type ReactionStatus = "queued" | "sent" | "delivered" | "read" | "failed" | "expired" | "skipped";
export type Reaction = {
  id: string; senderId: string | null; sourceType: "dose" | "checkin"; sourceId: string;
  type: "emoji" | "preset" | "custom"; emoji: string | null; text: string | null;
  status: ReactionStatus; failReason: string | null; queuedAt: string; sentAt: string | null;
};
export type ParentMessage = { id: string; kind: "text" | "emoji" | "button" | "other"; body: string | null; reactionId: string | null; createdAt: string };

export async function loadLoveSettings(recipientId: string): Promise<LoveSettings> {
  return apiFetch<LoveSettings>(`/api/v1/recipients/${recipientId}/love`);
}

/** false with a reason when the family cannot turn it on (a control family of the pilot). */
export async function setLoveEnabled(recipientId: string, enabled: boolean): Promise<{ ok: true } | { ok: false; reason: "unavailable" | "failed" }> {
  try {
    await apiFetch(`/api/v1/recipients/${recipientId}/love`, { method: "POST", body: { enabled } });
    return { ok: true };
  } catch (e) {
    return { ok: false, reason: /love_not_available/.test((e as ApiError).message ?? "") ? "unavailable" : "failed" };
  }
}

export async function loadMyCallName(circleId: string): Promise<string> {
  if (!authUserId()) return "";
  return (await apiFetch<{ name: string }>(`/api/v1/circles/${circleId}/call-name`)).name ?? "";
}

export async function saveMyCallName(circleId: string, name: string): Promise<boolean> {
  try { await apiFetch(`/api/v1/circles/${circleId}/call-name`, { method: "POST", body: { name } }); return true; } catch { return false; }
}

export async function myUserId(): Promise<string | null> {
  return authUserId();
}

/** Reactions of the last 3 days for this parent (the whole family's, newest first). */
export async function loadReactions(recipientId: string): Promise<Reaction[]> {
  return apiFetch<Reaction[]>(`/api/v1/recipients/${recipientId}/reactions`);
}

export async function loadParentMessages(recipientId: string, limit = 3): Promise<ParentMessage[]> {
  return apiFetch<ParentMessage[]>(`/api/v1/recipients/${recipientId}/parent-messages?limit=${limit}`);
}

export type SendFailure = "off" | "optedOut" | "tooOld" | "already" | "badNote" | "failed";
export async function sendReaction(args: {
  recipientId: string; sourceType: "dose" | "checkin"; sourceId: string;
  type: "emoji" | "preset" | "custom"; emoji?: string; text?: string;
}): Promise<{ ok: true } | { ok: false; reason: SendFailure }> {
  try {
    await apiFetch(`/api/v1/recipients/${args.recipientId}/reactions`, { method: "POST", body: {
      sourceType: args.sourceType, sourceId: args.sourceId, type: args.type, emoji: args.emoji ?? null, text: args.text ?? null,
    } });
    return { ok: true };
  } catch (e) {
    const err = e as ApiError;
    const m = err.message ?? "";
    if (err.status === 409 || /already/.test(m)) return { ok: false, reason: "already" };
    if (/love_off/.test(m)) return { ok: false, reason: "off" };
    if (/love_parent_opted_out/.test(m)) return { ok: false, reason: "optedOut" };
    if (/love_source_invalid/.test(m)) return { ok: false, reason: "tooOld" };
    if (/love_text_invalid/.test(m) || /check constraint/i.test(m)) return { ok: false, reason: "badNote" };
    return { ok: false, reason: "failed" };
  }
}

// ---------------------------------------------------------------- the owner's pilot numbers
export type LoveStats = {
  days: number;
  families: { loveOn: number; loveGroup: number; control: number; parentOptedOut: number };
  reactions: { created: number; sent: number; delivered: number; read: number; queued: number; expired: number; failed: number; skipped: number };
  parentReplies: number;
  confirmation: Record<string, { beforeSent: number; beforeConfirmed: number; afterSent: number; afterConfirmed: number }>;
};

export async function loadLoveStats(days = 14): Promise<LoveStats> {
  const data = await apiFetch<unknown>(`/api/v1/admin/love-stats?days=${days}`);
  if (!data || typeof data !== "object" || Array.isArray(data)) throw new Error("bad stats");
  const r = data as unknown as {
    days: number; families: { love_on: number; love_group: number; control: number; parent_opted_out: number };
    reactions: LoveStats["reactions"]; parent_replies_to_reactions: number;
    confirmation: Record<string, { before_sent: number; before_confirmed: number; after_sent: number; after_confirmed: number }>;
  };
  return {
    days: r.days,
    families: { loveOn: r.families.love_on, loveGroup: r.families.love_group, control: r.families.control, parentOptedOut: r.families.parent_opted_out },
    reactions: r.reactions, parentReplies: r.parent_replies_to_reactions,
    confirmation: Object.fromEntries(Object.entries(r.confirmation).map(([g, c]) => [g, { beforeSent: c.before_sent, beforeConfirmed: c.before_confirmed, afterSent: c.after_sent, afterConfirmed: c.after_confirmed }])),
  };
}
