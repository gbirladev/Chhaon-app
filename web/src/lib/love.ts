import { supabase } from "./supabase";
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
  const { data, error } = await supabase.from("love_settings").select("enabled, parent_opted_out_at, pilot_group").eq("recipient_id", recipientId).maybeSingle();
  if (error) throw error;
  return data ? { enabled: data.enabled, parentOptedOut: !!data.parent_opted_out_at, control: data.pilot_group === "control" } : NO_LOVE;
}

/** false with a reason when the family cannot turn it on (a control family of the pilot). */
export async function setLoveEnabled(recipientId: string, enabled: boolean): Promise<{ ok: true } | { ok: false; reason: "unavailable" | "failed" }> {
  const { error } = await supabase.rpc("set_love_enabled", { p_recipient: recipientId, p_enabled: enabled });
  if (!error) return { ok: true };
  return { ok: false, reason: /love_not_available/.test(error.message) ? "unavailable" : "failed" };
}

export async function loadMyCallName(circleId: string): Promise<string> {
  const uid = authUserId();
  if (!uid) return "";
  const { data, error } = await supabase.from("care_circle_members").select("parent_calls_me").eq("circle_id", circleId).eq("user_id", uid).maybeSingle();
  if (error) throw error;
  return data?.parent_calls_me ?? "";
}

export async function saveMyCallName(circleId: string, name: string): Promise<boolean> {
  const { error } = await supabase.rpc("set_parent_calls_me", { p_circle: circleId, p_name: name });
  return !error;
}

export async function myUserId(): Promise<string | null> {
  return authUserId();
}

/** Reactions of the last 3 days for this parent (the whole family's, newest first). */
export async function loadReactions(recipientId: string): Promise<Reaction[]> {
  const since = new Date(Date.now() - 3 * 24 * 3600_000).toISOString();
  const { data, error } = await supabase.from("reactions")
    .select("id, sender_id, source_type, source_id, type, emoji, text, status, fail_reason, queued_at, sent_at")
    .eq("recipient_id", recipientId).gte("queued_at", since).order("queued_at", { ascending: false }).limit(100);
  if (error) throw error;
  return data.map((r) => ({
    id: r.id, senderId: r.sender_id, sourceType: r.source_type as Reaction["sourceType"], sourceId: r.source_id, type: r.type as Reaction["type"],
    emoji: r.emoji, text: r.text, status: r.status as ReactionStatus, failReason: r.fail_reason, queuedAt: r.queued_at, sentAt: r.sent_at,
  }));
}

export async function loadParentMessages(recipientId: string, limit = 3): Promise<ParentMessage[]> {
  const { data, error } = await supabase.from("parent_messages").select("id, kind, body, reaction_id, created_at")
    .eq("recipient_id", recipientId).in("kind", ["text", "emoji"]).order("created_at", { ascending: false }).limit(limit);
  if (error) throw error;
  return data.map((m) => ({ id: m.id, kind: m.kind as ParentMessage["kind"], body: m.body, reactionId: m.reaction_id, createdAt: m.created_at }));
}

export type SendFailure = "off" | "optedOut" | "tooOld" | "already" | "badNote" | "failed";
export async function sendReaction(args: {
  recipientId: string; sourceType: "dose" | "checkin"; sourceId: string;
  type: "emoji" | "preset" | "custom"; emoji?: string; text?: string;
}): Promise<{ ok: true } | { ok: false; reason: SendFailure }> {
  const { error } = await supabase.rpc("send_reaction", {
    p_recipient: args.recipientId, p_source_type: args.sourceType, p_source_id: args.sourceId, p_type: args.type,
    p_emoji: args.emoji, p_text: args.text,
  });
  if (!error) return { ok: true };
  const m = error.message ?? "";
  if (error.code === "23505") return { ok: false, reason: "already" };
  if (/love_off/.test(m)) return { ok: false, reason: "off" };
  if (/love_parent_opted_out/.test(m)) return { ok: false, reason: "optedOut" };
  if (/love_source_invalid/.test(m)) return { ok: false, reason: "tooOld" };
  if (/love_text_invalid/.test(m) || error.code === "23514") return { ok: false, reason: "badNote" };
  return { ok: false, reason: "failed" };
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
  const { data, error } = await supabase.rpc("love_stats", { p_days: days });
  if (error || !data || typeof data !== "object" || Array.isArray(data)) throw error ?? new Error("bad stats");
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
