import { supabase } from "./supabase";
import { authToken, authUserId, authExpired } from "./auth";
import type { Database } from "./database.types";
import { E164 } from "./countries";
import { resolveLocalUrl } from "./hosts";
import { localDate } from "./format";

export type Recipient = Database["public"]["Tables"]["care_recipients"]["Row"];

export type Medicine = {
  id: string;
  name: string;
  dose: string;
  times: string[];
  stock: number;
  threshold: number | null;
  instructions: string | null;
  active: boolean;
  photoUrl: string | null;
  critical: boolean;   // an unanswered reminder for this medicine alerts the family on the first miss (escalation ladder)
};

export const PRESET_TIMES = { Morning: "08:00", Afternoon: "13:00", Night: "20:00" } as const;
export type Preset = keyof typeof PRESET_TIMES;

/**
 * Accepts the ways people really type a number: "98100 12345", "+91 98100 12345", "09810012345", "919810012345",
 * "0091 98100 12345", "+1 415 555 0123". Returns E.164, or null when it cannot be right (letters such as "ext 4",
 * a wrong length, or an Indian mobile that does not start with 6-9) so we never message a wrong number.
 */
export function normalizePhone(input: string): string | null {
  // Devanagari (०-९), Arabic-Indic (٠-٩) and Persian (۰-۹) digits become plain 0-9 first.
  const raw = input.trim().replace(/[०-९٠-٩۰-۹０-９]/g, (c) => {
    const code = c.charCodeAt(0);
    return String(code - (code >= 0xff10 ? 0xff10 : code >= 0x0966 ? 0x0966 : code >= 0x06f0 ? 0x06f0 : 0x0660));
  });
  if (!raw || /[a-z]/i.test(raw)) return null;
  let digits = raw.replace(/[^\d+]/g, "");
  if (digits.startsWith("00")) digits = "+" + digits.slice(2);
  let candidate: string;
  if (digits.startsWith("+")) candidate = digits;
  else if (digits.length === 12 && digits.startsWith("91")) candidate = `+${digits}`;
  else if (digits.length === 11 && digits.startsWith("0")) candidate = `+91${digits.slice(1)}`;
  else candidate = `+91${digits}`;
  if (!E164.test(candidate)) return null;
  if (candidate.startsWith("+91") && !/^\+91[6-9]\d{9}$/.test(candidate)) return null;
  return candidate;
}

/** Every parent this caregiver looks after (oldest first), or [] if none yet. Throws if the lookup FAILS, so callers never
 *  mistake a network problem for "no parent" (which would send them to the add-parent form and risk a duplicate). */
export async function loadRecipients(): Promise<Recipient[]> {
  if (!authToken()) return [];
  const { data, error } = await supabase.from("care_recipients").select("*").order("created_at", { ascending: true });
  if (error) throw error;
  return data;
}

/** Medicine names by id for every parent (notifications about a parent that is not the one on screen still name the medicine). */
export async function loadMedicineNames(): Promise<Record<string, string>> {
  const { data } = await supabase.from("medicines").select("id, name");
  return Object.fromEntries((data ?? []).map((m) => [m.id, m.name]));
}

export async function loadMedicines(recipientId: string): Promise<Medicine[]> {
  const { data, error } = await supabase
    .from("medicines")
    .select("id, name, dosage, instructions, stock_count, refill_threshold, active, photo_url, critical, created_at, medicine_schedules(time_of_day)")
    .eq("recipient_id", recipientId)
    .order("created_at", { ascending: true });
  if (error) throw error;
  return data.map((m) => ({
    id: m.id,
    name: m.name,
    dose: m.dosage ?? "",
    times: m.medicine_schedules.map((s) => s.time_of_day),
    stock: m.stock_count ?? 0,
    threshold: m.refill_threshold,
    instructions: m.instructions,
    active: m.active,
    photoUrl: m.photo_url,
    critical: m.critical,
  }));
}

export const isLowStock = (m: Medicine) => m.threshold !== null && m.stock <= m.threshold;

export type ConsentResult = "sent" | "already_consented" | "opted_out" | "too_soon" | "no_phone" | "error";

/** Asks the backend to send the parent a WhatsApp message requesting their agreement. Never throws. */
export async function requestConsent(recipientId: string): Promise<{ result: ConsentResult; retryAfter?: number }> {
  try {
    const token = authToken();
    if (!token) return { result: "error" };
    const response = await fetch(`${resolveLocalUrl(import.meta.env.VITE_API_URL)}/recipients/${recipientId}/consent-request`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!response.ok) return { result: "error" };
    const body = (await response.json()) as { status: ConsentResult; retry_after?: number };
    return { result: body.status, retryAfter: body.retry_after };
  } catch {
    return { result: "error" };
  }
}

export type AlertsState = { userId: string; phone: string | null; loginPhone: string | null; on: boolean; waiting: boolean };
export type AlertsResult = "sent" | "opted_out" | "too_soon" | "too_many" | "error";

/** The caregiver's own WhatsApp-alert settings. on = confirmed by that number and not switched off. */
export async function loadMyAlerts(): Promise<AlertsState | null> {
  const uid = authUserId();
  if (!uid) return null;
  const { data } = await supabase.from("profiles").select("phone, alert_phone, alert_requested_at, alert_confirmed_at, alert_consent_at").eq("id", uid).maybeSingle();
  if (!data) return null;
  const on = !!data.alert_consent_at;
  return { userId: uid, phone: data.alert_phone, loginPhone: data.phone, on, waiting: !on && !!data.alert_phone && !data.alert_confirmed_at && !!data.alert_requested_at };
}

export async function requestAlerts(phone: string): Promise<AlertsResult> {
  try {
    const token = authToken();
    if (!token) return "error";
    const response = await fetch(`${resolveLocalUrl(import.meta.env.VITE_API_URL)}/alerts/whatsapp/request`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ phone }),
    });
    if (!response.ok) return "error";
    return ((await response.json()) as { status: AlertsResult }).status;
  } catch {
    return "error";
  }
}

export async function turnOffAlerts(): Promise<boolean> {
  const uid = authUserId();
  if (!uid) return false;
  const { error } = await supabase.from("profiles").update({ alert_consent_at: null }).eq("id", uid);
  return !error;
}

// ---------------------------------------------------------------- reports
export type ReportFlag = "normal" | "low" | "high" | "critical";
export type ReportStatus = "pending" | "processing" | "done" | "failed";
export type ReportSummary = { id: string; type: string | null; date: string | null; status: ReportStatus; fileUrl: string; createdAt: string; attention: number; values: number };
export type ReportValue = { id: string; name: string; value: number; unit: string | null; range: string | null; flag: ReportFlag | null; verified: boolean };
export type ReportDetailData = ReportSummary & { summary: string | null; items: ReportValue[] };
export const REPORT_MAX_BYTES = 15 * 1024 * 1024;

const attentionFlag = (flag: string | null) => flag === "high" || flag === "low" || flag === "critical";

export async function loadReports(recipientId: string): Promise<ReportSummary[]> {
  const { data, error } = await supabase
    .from("reports")
    .select("id, type, report_date, ai_status, file_url, created_at, report_values(flag)")
    .eq("recipient_id", recipientId)
    .order("report_date", { ascending: false, nullsFirst: false })
    .order("created_at", { ascending: false });
  if (error) throw error;
  return data.map((r) => ({
    id: r.id, type: r.type, date: r.report_date, status: r.ai_status, fileUrl: r.file_url, createdAt: r.created_at,
    values: r.report_values.length, attention: r.report_values.filter((v) => attentionFlag(v.flag)).length,
  }));
}

export async function loadReport(id: string): Promise<ReportDetailData | null> {
  const { data, error } = await supabase
    .from("reports")
    .select("id, type, report_date, ai_status, ai_summary, file_url, created_at, report_values(id, test_name, value, unit, reference_range, flag, user_verified)")
    .eq("id", id).maybeSingle();
  if (error) throw error;
  if (!data) return null;
  const items = data.report_values.map((v) => ({ id: v.id, name: v.test_name, value: Number(v.value), unit: v.unit, range: v.reference_range, flag: v.flag, verified: v.user_verified }));
  return { id: data.id, type: data.type, date: data.report_date, status: data.ai_status, fileUrl: data.file_url, createdAt: data.created_at, summary: data.ai_summary, items, values: items.length, attention: items.filter((v) => attentionFlag(v.flag)).length };
}

export type UploadResult = { ok: true; id: string; status: ReportStatus } | { ok: false; reason: "type" | "size" | "upload" | "save" | "ai_off" | "failed" | "auth" };

/** Calls the backend to read a stored report. Never throws. */
export async function analyzeReport(reportId: string, language: string): Promise<"done" | "processing" | "failed" | "ai_off" | "auth"> {
  try {
    const token = authToken();
    if (!token) return "auth";
    const response = await fetch(`${resolveLocalUrl(import.meta.env.VITE_API_URL)}/reports/${reportId}/analyze`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ language }),
    });
    if (response.status === 503) return "ai_off";
    if (response.status === 401) return "auth";
    if (!response.ok) return "failed";
    const body = (await response.json()) as { status: string };
    return body.status === "done" ? "done" : body.status === "processing" ? "processing" : "failed";
  } catch {
    return "failed";
  }
}

const REPORT_TYPES: Record<string, string> = { "image/jpeg": "jpg", "image/png": "png", "image/webp": "webp", "application/pdf": "pdf" };

/** Stores the file privately, creates the report row, then asks the backend to read it. */
export async function uploadReport(recipientId: string, file: File, language: string, shrink: (f: File) => Promise<Blob>): Promise<UploadResult> {
  const kind = file.type || (file.name.toLowerCase().endsWith(".pdf") ? "application/pdf" : "");
  const ext = REPORT_TYPES[kind] ?? (kind.startsWith("image/") ? "jpg" : undefined);
  if (!ext) return { ok: false, reason: "type" };
  const body = kind.startsWith("image/") ? await shrink(file) : file;
  if (body.size > REPORT_MAX_BYTES) return { ok: false, reason: "size" };
  const uid = authUserId();
  if (!uid) return { ok: false, reason: "auth" };
  const name = Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, "0")).join("");
  const path = `${recipientId}/${name}.${ext}`;
  const { error: uploadError } = await supabase.storage.from("reports").upload(path, body, { contentType: ext === "pdf" ? "application/pdf" : body.type || "image/jpeg" });
  if (uploadError) return { ok: false, reason: "upload" };
  const { data: row, error } = await supabase.from("reports").insert({ recipient_id: recipientId, uploaded_by: uid, file_url: path }).select("id").single();
  if (error || !row) { await supabase.storage.from("reports").remove([path]); return { ok: false, reason: "save" }; }
  const status = await analyzeReport(row.id, language);
  if (status === "auth") return { ok: false, reason: "auth" };
  if (status === "ai_off") return { ok: false, reason: "ai_off" };
  return status === "failed" ? { ok: false, reason: "failed" } : { ok: true, id: row.id, status };
}

export async function deleteReport(report: { id: string; fileUrl: string }): Promise<boolean> {
  const { data, error } = await supabase.from("reports").delete().eq("id", report.id).select("id");
  if (error || !data?.length) return false;
  await supabase.storage.from("reports").remove([report.fileUrl]);
  return true;
}

/** The caregiver corrects what the AI read. The value is then marked as checked by a person. */
export async function correctReportValue(id: string, value: number, unit: string | null): Promise<boolean> {
  const { data, error } = await supabase.from("report_values").update({ value, unit, user_verified: true }).eq("id", id).select("id");
  return !error && !!data?.length;
}

export type TrendPoint = { date: string; value: number };
export async function loadTrend(recipientId: string, testName: string): Promise<TrendPoint[]> {
  const { data, error } = await supabase
    .from("report_values")
    .select("value, reports!inner(report_date, created_at, recipient_id)")
    .eq("test_name", testName).eq("reports.recipient_id", recipientId);
  if (error) throw error;
  return data
    .map((row) => ({ date: row.reports.report_date ?? row.reports.created_at.slice(0, 10), value: Number(row.value) }))
    .sort((x, y) => x.date.localeCompare(y.date));
}

export async function signedReportUrl(path: string): Promise<string | null> {
  const { data } = await supabase.storage.from("reports").createSignedUrl(path, 300);
  return data?.signedUrl ?? null;
}

/** Which way the caregiver wants today's medicines drawn (null = the default). */
export async function loadTodayLayout(): Promise<string | null> {
  const uid = authUserId();
  if (!uid) return null;
  const { data } = await supabase.from("profiles").select("today_layout").eq("id", uid).maybeSingle();
  return data?.today_layout ?? null;
}

export async function saveTodayLayout(layout: string): Promise<boolean> {
  const uid = authUserId();
  if (!uid) return false;
  const { error } = await supabase.from("profiles").update({ today_layout: layout }).eq("id", uid);
  return !error;
}

export type AppNotification = Database["public"]["Tables"]["notifications"]["Row"];

/** The newest alerts, or null when the load failed (callers keep what they already show). */
export async function loadNotifications(): Promise<AppNotification[] | null> {
  try {
    const { data, error } = await supabase.from("notifications").select("*").order("created_at", { ascending: false }).limit(30);
    return error ? null : data ?? [];
  } catch { return null; }
}

export async function markNotificationsRead(ids: string[]): Promise<void> {
  if (!ids.length) return;
  await supabase.from("notifications").update({ read_at: new Date().toISOString() }).in("id", ids).is("read_at", null);
}

export async function deleteNotificationRow(id: string): Promise<void> {
  await supabase.from("notifications").delete().eq("id", id);
}

/** The account this browser is logged in as no longer exists or the login has ended (e.g. the database was reset). */
export function isStaleSession(error: { code?: string; message?: string } | null | undefined): boolean {
  if (!error) return false;
  return ["23503", "PGRST301", "PGRST303"].includes(error.code ?? "") || /jwt|sub claim/i.test(error.message ?? "");
}

/** Like isStaleSession, but a 42501 (our own "not found" or a plain permission denial) only counts when the login really is invalid. */
export async function sessionIsGone(error: { code?: string; message?: string } | null | undefined): Promise<boolean> {
  if (!error) return false;
  if (isStaleSession(error)) return true;
  if (error.code !== "42501") return false;
  // A 42501 is only a login problem when our token is actually gone/expired; otherwise it is a plain
  // permission/"not found" from one of our own functions and must not sign the caregiver out.
  return authExpired();
}

/** Our own database functions raise 42501 with these messages when the record is gone (not an auth problem). */
export const isNotFound = (error: { code?: string; message?: string } | null | undefined) =>
  error?.code === "42501" && /not found/i.test(error.message ?? "");

type DoseStatus = Database["public"]["Enums"]["dose_status"];
export type TodayDose = { id: string; status: DoseStatus; scheduledAt: string; name: string; dosage: string | null; active: boolean };

/** The parent's doses for THEIR local "today" (the day boundary is the parent's timezone, not the caregiver's). */
export async function loadTodayDoses(recipient: Recipient): Promise<TodayDose[]> {
  const from = new Date(Date.now() - 36 * 3600_000).toISOString();
  const to = new Date(Date.now() + 36 * 3600_000).toISOString();
  const { data, error } = await supabase
    .from("dose_events")
    .select("id, status, scheduled_at, medicine_schedules(medicines(recipient_id, name, dosage, active))")
    .gte("scheduled_at", from).lte("scheduled_at", to).order("scheduled_at");
  if (error) throw error;
  const today = localDate(new Date(), recipient.timezone);
  return data
    .filter((d) => d.medicine_schedules.medicines.recipient_id === recipient.id && localDate(new Date(d.scheduled_at), recipient.timezone) === today)
    .map((d) => ({ id: d.id, status: d.status, scheduledAt: d.scheduled_at, name: d.medicine_schedules.medicines.name, dosage: d.medicine_schedules.medicines.dosage, active: d.medicine_schedules.medicines.active }));
}

export type TodayCheckin = { id: string; status: Database["public"]["Enums"]["checkin_status"]; sent_at: string | null; responded_at: string | null } | null;

export async function loadCheckin(recipient: Recipient): Promise<TodayCheckin> {
  const { data, error } = await supabase
    .from("checkins").select("id, status, sent_at, responded_at")
    .eq("recipient_id", recipient.id).eq("date", localDate(new Date(), recipient.timezone)).maybeSingle();
  if (error) throw error;
  return data;
}

export type Activity =
  | { kind: "dose"; id: string; at: string; status: DoseStatus; respondedAt: string | null; name: string; dosage: string | null }
  | { kind: "checkin"; id: string; at: string; status: Database["public"]["Enums"]["checkin_status"]; respondedAt: string | null };

/** What was really sent to the parent in the last few days, oldest first. Message text is not stored, only these records. */
export async function loadRecentActivity(recipient: Recipient, days = 3): Promise<Activity[]> {
  const from = new Date(Date.now() - days * 24 * 3600_000).toISOString();
  const [doses, checkins] = await Promise.all([
    supabase.from("dose_events")
      .select("id, status, sent_at, responded_at, medicine_schedules(medicines(recipient_id, name, dosage))")
      .not("sent_at", "is", null).gte("sent_at", from).order("sent_at", { ascending: false }).limit(300),
    supabase.from("checkins").select("id, status, sent_at, responded_at")
      .eq("recipient_id", recipient.id).not("sent_at", "is", null).gte("sent_at", from).order("sent_at", { ascending: false }).limit(50),
  ]);
  if (doses.error) throw doses.error;
  if (checkins.error) throw checkins.error;
  const items: Activity[] = [
    ...doses.data
      .filter((d) => d.medicine_schedules.medicines.recipient_id === recipient.id && d.sent_at)
      .map((d): Activity => ({ kind: "dose", id: d.id, at: d.sent_at as string, status: d.status, respondedAt: d.responded_at, name: d.medicine_schedules.medicines.name, dosage: d.medicine_schedules.medicines.dosage })),
    ...checkins.data.map((c): Activity => ({ kind: "checkin", id: c.id, at: c.sent_at as string, status: c.status, respondedAt: c.responded_at })),
  ];
  return items.sort((a, b) => a.at.localeCompare(b.at));
}

export type CircleMember = { userId: string; role: "owner" | "member"; name: string | null; email: string | null; phone: string | null };

export async function loadMembers(circleId: string): Promise<CircleMember[]> {
  const { data, error } = await supabase
    .from("care_circle_members").select("user_id, role, created_at, profiles(name, email, phone)")
    .eq("circle_id", circleId).order("created_at");
  if (error) throw error;
  return data.map((m) => ({ userId: m.user_id, role: m.role, name: m.profiles?.name ?? null, email: m.profiles?.email ?? null, phone: m.profiles?.phone ?? null }));
}
