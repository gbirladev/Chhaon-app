import { apiFetch } from "./http";

/**
 * The owner's Monitor (docs/admin-monitor.md): typed calls to /api/v1/admin/*. Every call needs an account with
 * profiles.is_admin (the backend answers 403 otherwise). Keys stay snake_case, exactly as the API sends them, and every time is
 * UTC ISO text: the screen shows it in India time and in the viewer's own zone.
 */

export type Level = "green" | "amber" | "red";

export type HealthCheck = {
  key: string;
  status: Level;
  reason: string;
  detail: string;
  since: string | null;
  facts: Record<string, unknown>;
};

export type ServerInfo = { git_sha: string; version: string | null; build_time: string | null; started_at: string; uptime_seconds: number; java: string };
export type VolumeDay = { date: string; count: number };
export type Volume = { today: number; last_7_days: number; daily_average_7d: number; amber_per_day: number; daily: VolumeDay[] };
export type Rejection = { reason: string; http_status: number; n: number; last_at: string };

export type Health = {
  generated_at: string;
  overall: Level;
  checks: HealthCheck[];
  server: ServerInfo;
  volume: Volume;
  webhook_rejections_24h: Rejection[];
};

export type AdminAlert = {
  id: number; check_key: string; status: "amber" | "red"; worst_status: "amber" | "red"; detail: string;
  opened_at: string; last_seen_at: string; resolved_at: string | null;
};
export type Alerts = { open: AdminAlert[]; recent: AdminAlert[] };

export type Overview = {
  date: string;
  doses: { scheduled: number; not_sent_yet: number; sent: number; waiting: number; taken: number; taken_late: number; missed: number; skipped: number; sent_late: number };
  checkins: { sent: number; answered: number; waiting: number; missed: number; call_me: number };
  alerts: { total: number; by_type: Record<string, number>; whatsapp_sent: number };
  volume: Volume;
  pilot: null | {
    caregivers: { total: number; active_7d: number; new_7d: number };
    parents: { total: number; agreed: number; waiting: number; stopped: number };
    reminders_7d: { sent: number; taken: number; missed: number };
    checkins_7d: { sent: number; replied: number; missed: number };
  };
};

export type Caregiver = { user_id: string; name: string | null; email: string | null; phone: string | null; role: string; plan: string };

export type FamilyRow = {
  recipient_id: string; circle_id: string; name: string; call_name: string | null; relation: string; care_type: "older" | "pregnant" | "child";
  is_self: boolean; whatsapp_phone: string | null; timezone: string; language: string;
  reminder_to: "person" | "caregiver" | "helper"; reminder_name: string | null; reminder_relation: string | null;
  reminder_user_name: string | null; reminder_user_phone: string | null;
  consent_at: string | null; consent_requested_at: string | null; opted_out_at: string | null; phone_opted_out: boolean;
  checkin_enabled: boolean; created_at: string; caregivers: Caregiver[]; plan: string | null; paused_by_plan: boolean;
  active_medicines: number; low_stock: number; today_total: number; today_confirmed: number; today_missed: number; today_waiting: number;
  checkin_today: string | null; last_reply_at: string | null; sent_48h: number; replied_48h: number;
  median_reply_minutes_7d: number | null; messages_today: number; messages_7d: number; failed_24h: number;
  last_caregiver_activity_at: string | null; badges: string[];
};

export type FamilyDetail = {
  recipient: Record<string, unknown> & { name: string; whatsapp_phone: string | null; timezone: string; conditions?: string[]; allergies?: string[] };
  members: { user_id: string; name: string | null; email: string | null; phone: string | null; role: string; plan: string; alert_phone: string | null; alert_consent_at: string | null; signed_up_at: string }[];
  medicines: { id: string; name: string; dosage: string | null; active: boolean; critical: boolean; stock_count: number | null; refill_threshold: number | null; has_photo: boolean; paused_by_plan: boolean; schedules: { time_of_day: string; days_of_week: number[]; start_date: string; end_date: string | null }[] }[];
  doses: { id: string; medicine: string; dosage: string | null; scheduled_at: string; status: string; sent_at: string | null; responded_at: string | null; confirm_source: string | null; send_delay_minutes: number | null }[];
  checkins: { id: string; date: string; status: string; sent_at: string | null; responded_at: string | null }[];
  alerts: { type: string; created_at: string; whatsapp_sent_at: string | null; recipients: number }[];
  consent_requests: { requested_at: string; requested_by_name: string | null }[];
  escalations: { kind: string; base_at: string; nudged_at: string | null; alerted_at: string | null; resolved_at: string | null; resolved_by: string | null }[];
  appointments: { doctor_name: string; datetime: string; location: string | null }[];
};

export type ActivityItem = { kind: string; at_time: string; recipient_id: string | null; recipient_name: string | null; detail: string | null };
export type UpcomingItem = { kind: "dose" | "checkin"; due_at: string; recipient_id: string; recipient_name: string; timezone: string; reminder_to: string; reminder_name: string | null; whatsapp_phone: string | null; will_send: boolean; medicines: string[] };
export type FeedbackItem = { id: string; created_at: string; kind: string; message: string; rating: number | null; screen: string | null; app_language: string; images: number; user_id: string | null; name: string | null; email: string | null; phone: string | null };
export type GatewayStatus = { configured: boolean; reachable: boolean; session_name: string; session_status: string | null; number: string | null; webhooks: number | null; webhook_targets: string[]; error: string | null; checked_at: string };

export const loadHealth = () => apiFetch<Health>("/api/v1/admin/health");
export const loadAlerts = () => apiFetch<Alerts>("/api/v1/admin/alerts?limit=30");
export const loadOverview = (date?: string) => apiFetch<Overview>(`/api/v1/admin/overview${date ? `?date=${encodeURIComponent(date)}` : ""}`);
export const loadFamilies = () => apiFetch<FamilyRow[]>("/api/v1/admin/families");
/** Every call is recorded in the admin audit table (who looked at which family, when). */
export const loadFamily = (recipientId: string) => apiFetch<FamilyDetail>(`/api/v1/admin/families/${encodeURIComponent(recipientId)}`);
export const loadActivity = (limit = 50) => apiFetch<ActivityItem[]>(`/api/v1/admin/activity?limit=${limit}`);
export const loadUpcoming = (hours = 2) => apiFetch<UpcomingItem[]>(`/api/v1/admin/upcoming?hours=${hours}`);
export const loadFeedbackInbox = (limit = 50) => apiFetch<FeedbackItem[]>(`/api/v1/admin/feedback-inbox?limit=${limit}`);
export const loadGateway = (fresh = false) => apiFetch<GatewayStatus>(`/api/v1/admin/gateway?fresh=${fresh}`);

/** Badge codes the backend sends, in the order the filter shows them. */
export const BADGES = ["no_reply_2d", "consent_pending", "checkin_missed", "low_stock", "send_failed", "paused_by_plan", "opted_out"] as const;
