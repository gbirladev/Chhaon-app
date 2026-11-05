import { compressImage } from "./image";
import { supabase } from "./supabase";
import { kindOf, randomName } from "./wallet";
import { authUserId } from "./auth";
import { apiFetch, ApiError } from "./http";

export const FEEDBACK_KINDS = ["idea", "problem", "praise", "other"] as const;
export type FeedbackKind = (typeof FEEDBACK_KINDS)[number];
export const FEEDBACK_MAX = 1000;

export type FeedbackResult = "ok" | "limit" | "error";

/** Pictures on a feedback message (migration 0035): up to 3, kept in the private `feedback` bucket at <user id>/<name>. */
export const FEEDBACK_BUCKET = "feedback";
export const FEEDBACK_MAX_IMAGES = 3;
export const FEEDBACK_IMAGE_BYTES = 5 * 1024 * 1024;
const IMAGE_TYPES = ["image/jpeg", "image/png", "image/webp"];
export type PreparedImage = { blob: Blob; mime: string };

/** Shrinks a chosen photo / screenshot the same way the reports wallet does, and checks it against what the bucket accepts. */
export async function prepareImage(file: File): Promise<{ ok: true; image: PreparedImage } | { ok: false; reason: "type" | "heic" | "size" }> {
  const kind = kindOf(file);
  if (file.size === 0 || !kind.startsWith("image/")) return { ok: false, reason: "type" };
  // compressImage only touches files whose own type says "image/*": give it the type we worked out (some phones leave it empty)
  const typed = file.type === kind ? file : new File([file], file.name, { type: kind });
  let blob: Blob;
  try { blob = await compressImage(typed, 1600, 0.82); } catch { return { ok: false, reason: "type" }; }
  if (!IMAGE_TYPES.includes(blob.type)) return { ok: false, reason: /hei[cf]/i.test(`${blob.type} ${kind} ${file.name}`) ? "heic" : "type" };   // a format this browser cannot shrink (HEIC from some phones)
  if (blob.size > FEEDBACK_IMAGE_BYTES) return { ok: false, reason: "size" };
  return { ok: true, image: { blob, mime: blob.type } };
}

/** Uploads one prepared picture into the caregiver's own folder. Returns its storage path, or null if it could not be uploaded. */
export async function uploadFeedbackImage(image: PreparedImage): Promise<string | null> {
  const uid = authUserId();
  if (!uid) return null;
  const ext = image.mime === "image/png" ? "png" : image.mime === "image/webp" ? "webp" : "jpg";
  const path = `${uid}/${randomName()}.${ext}`;
  const { error } = await supabase.storage.from(FEEDBACK_BUCKET).upload(path, image.blob, { contentType: image.mime });
  return error ? null : path;
}

/** Sends one feedback message as the logged-in caregiver. The database allows 10 a day ("limit" if that is used up). */
export async function sendFeedback(input: { kind: FeedbackKind; message: string; rating: number | null; screen: string; language: string; images?: string[] }): Promise<FeedbackResult> {
  if (!authUserId()) return "error";
  try {
    await apiFetch("/api/v1/feedback", { method: "POST", body: {
      kind: input.kind, message: input.message.trim(), rating: input.rating,
      screen: input.screen.slice(0, 40), appLanguage: input.language === "hi" ? "hi" : "en",
      images: input.images ?? [],
    } });
    return "ok";
  } catch (e) {
    return (e as ApiError).status === 429 ? "limit" : "error";
  }
}

/** What the owner sees (migrations 0034 and 0035). Includes who wrote it; only the admin account can load it. */
export type FeedbackItem = { id: string; createdAt: string; kind: string; message: string; rating: number | null; screen: string | null; language: string; name: string | null; email: string | null; images: string[] };

type AdminFeedbackRow = { id: string; created_at: string; kind: string; message: string; rating: number | null; screen: string | null; app_language: string; sender_name: string | null; sender_email: string | null; images: string[] | null };
export async function loadAdminFeedback(limit = 100): Promise<FeedbackItem[]> {
  const data = await apiFetch<AdminFeedbackRow[]>(`/api/v1/admin/feedback?limit=${limit}`);
  return data.map((r) => ({ id: r.id, createdAt: r.created_at, kind: r.kind, message: r.message, rating: r.rating, screen: r.screen, language: r.app_language, name: r.sender_name, email: r.sender_email, images: r.images ?? [] }));
}
