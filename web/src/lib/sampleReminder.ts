import { ApiError, apiFetch } from "./http";

/** "Send a sample reminder now" (docs/sample-reminder.md; SampleReminderController in the Java API). */
export type SampleReason = "ok" | "no_number" | "not_consented" | "opted_out" | "no_medicines" | "limited";
export type SampleStatus = { reason: SampleReason; sentToday: boolean; realReminderSent: boolean };
export type SampleResult = "sent" | "limited" | "busy" | "error";

export const loadSampleStatus = (recipientId: string) =>
  apiFetch<SampleStatus>(`/api/v1/recipients/${recipientId}/sample-reminder`);

/** Sends the one sample reminder of the day. Never throws: sent | limited (already today) | busy (global guard) | error. */
export async function sendSampleReminder(recipientId: string): Promise<SampleResult> {
  try {
    await apiFetch<{ status: string }>(`/api/v1/recipients/${recipientId}/sample-reminder`, { method: "POST" });
    return "sent";
  } catch (e) {
    const status = e instanceof ApiError ? (e.body as { status?: unknown } | undefined)?.status : undefined;
    return status === "limited" || status === "busy" ? status : "error";
  }
}
