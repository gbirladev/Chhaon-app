import { supabase } from "./supabase";

export const FEEDBACK_KINDS = ["idea", "problem", "praise", "other"] as const;
export type FeedbackKind = (typeof FEEDBACK_KINDS)[number];
export const FEEDBACK_MAX = 1000;

export type FeedbackResult = "ok" | "limit" | "error";

/** Sends one feedback message as the logged-in caregiver. The database allows 10 a day ("limit" if that is used up). */
export async function sendFeedback(input: { kind: FeedbackKind; message: string; rating: number | null; screen: string; language: string }): Promise<FeedbackResult> {
  const { data: auth } = await supabase.auth.getSession();
  const uid = auth.session?.user.id;
  if (!uid) return "error";
  const { error } = await supabase.from("feedback").insert({
    user_id: uid,
    kind: input.kind,
    message: input.message.trim(),
    rating: input.rating,
    screen: input.screen.slice(0, 40),
    app_language: input.language === "hi" ? "hi" : "en",
  });
  if (!error) return "ok";
  return error.code === "53400" ? "limit" : "error";
}

/** What the owner sees (migration 0034). Includes who wrote it; only the admin account can load it. */
export type FeedbackItem = { id: string; createdAt: string; kind: string; message: string; rating: number | null; screen: string | null; language: string; name: string | null; email: string | null };

export async function loadAdminFeedback(limit = 100): Promise<FeedbackItem[]> {
  const { data, error } = await supabase.rpc("admin_feedback", { p_limit: limit });
  if (error || !data) throw error ?? new Error("bad feedback");
  return data.map((r) => ({ id: r.id, createdAt: r.created_at, kind: r.kind, message: r.message, rating: r.rating, screen: r.screen, language: r.app_language, name: r.sender_name, email: r.sender_email }));
}
