import { useEffect, useState } from "react";
import { useTranslations } from "use-intl";
import type { Recipient } from "../lib/api";
import { authUserId } from "../lib/auth";
import { receiverName, receiverOf } from "../lib/reminderReceiver";
import { loadSampleStatus, sendSampleReminder, type SampleResult, type SampleStatus } from "../lib/sampleReminder";
import "./sampleReminder.css";

type Shown = SampleResult | "idle" | "sending";

/**
 * On Today, right after the setup: "Send a sample reminder to Papa now", so the family sees a reminder arrive on day one instead of
 * at the next medicine time (docs/sample-reminder.md). Shown only while the receiver has agreed, there is an active medicine, no
 * sample went out today and no real reminder has been sent yet (the server says so). A tap on the sample never marks a medicine.
 */
export function SampleReminderCard({ recipient, call, medicineCount }: { recipient: Recipient; call: string; medicineCount: number }) {
  const t = useTranslations("sample");
  const consented = !!recipient.consent_at && !recipient.opted_out_at;
  const [status, setStatus] = useState<{ id: string; s: SampleStatus } | null>(null);
  const [shown, setShown] = useState<{ id: string; v: Shown } | null>(null);

  useEffect(() => {
    if (!consented || medicineCount === 0) return;
    let cancelled = false;
    loadSampleStatus(recipient.id).then((s) => { if (!cancelled) setStatus({ id: recipient.id, s }); }).catch(() => {});
    return () => { cancelled = true; };
  }, [recipient.id, consented, medicineCount]);

  const mine = status && status.id === recipient.id ? status.s : null;
  const v: Shown = shown && shown.id === recipient.id ? shown.v : "idle";
  if (!consented || medicineCount === 0 || !mine || mine.realReminderSent) return null;
  if (v === "idle" && mine.reason !== "ok") return null;

  const r = receiverOf(recipient);
  const me = recipient.is_self || (r.reminder_to === "caregiver" && !!r.reminder_user_id && r.reminder_user_id === authUserId());
  const name = r.reminder_to === "person" ? call : receiverName(r) || call;

  const send = async () => {
    setShown({ id: recipient.id, v: "sending" });
    const result = await sendSampleReminder(recipient.id);
    setShown({ id: recipient.id, v: result });
  };

  const note = v === "sent" ? (me ? t("sentMe") : t("sent", { name }))
    : v === "limited" ? t("limited") : v === "busy" ? t("busy") : v === "error" ? t("error") : null;
  const canSend = v === "idle" || v === "sending" || v === "error" || v === "busy";

  return (
    <section className="sample-reminder" aria-live="polite">
      <strong>{me ? t("titleMe") : t("title", { name })}</strong>
      {v !== "sent" && v !== "limited" && <p>{t("body")}</p>}
      {note && <p className={`sr-note ${v === "sent" ? "ok" : "warn"}`} role={v === "error" ? "alert" : undefined}>{note}</p>}
      {canSend && (
        <button type="button" className="sr-send" disabled={v === "sending"} onClick={() => void send()}>
          {v === "sending" ? t("sending") : me ? t("buttonMe") : t("button", { name })}
        </button>
      )}
    </section>
  );
}
