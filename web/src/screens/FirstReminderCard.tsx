import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "use-intl";
import { loadRecentActivity, type Recipient } from "../lib/api";
import { firstReminderCardState, reminderWhen, setFirstReminderCardState } from "../lib/firstReminder";
import { formatClock, type Language } from "../lib/format";
import { loadWhatsAppPreview, type WhatsAppPreview } from "../lib/whatsappPreview";
import { Bubble } from "./WhatsAppPreview";
import "./whatsappPreview.css";
import "./firstReminderCard.css";

/**
 * On Today after the first medicine: "Papa's first reminder: tomorrow 8:00 AM" and the exact WhatsApp bubble Papa will get. The time
 * and the text come from the backend preview (the same code as the real send). It stays until a real dose reminder has been sent
 * (the activity of the last 14 days has one) or it is closed. When the person has not agreed yet it says so in one line; the
 * ConsentBanner above carries the details and the "send again" button. Stopped / no number: the banner alone speaks.
 */
export function FirstReminderCard({ recipient, call, go }: { recipient: Recipient; call: string; go: (screen: "whatsapp") => void }) {
  const t = useTranslations();
  const language = useLocale() as Language;
  const [state, setState] = useState(() => firstReminderCardState(recipient.id));   // App keys this card by recipient id
  const [preview, setPreview] = useState<{ id: string; data: WhatsAppPreview } | null>(null);

  useEffect(() => {
    if (firstReminderCardState(recipient.id) !== "pending") return;
    let cancelled = false;
    const load = async () => {
      try {
        const [activity, data] = await Promise.all([loadRecentActivity(recipient, 14), loadWhatsAppPreview(recipient.id)]);
        if (cancelled) return;
        if (activity.some((a) => a.kind === "dose")) { setFirstReminderCardState(recipient.id, null); setState(null); return; }   // reminders are going out
        setPreview({ id: recipient.id, data });
      } catch { /* offline or server down: show nothing rather than a wrong time */ }
    };
    void load();
    const onReturn = () => { if (document.visibilityState === "visible") void load(); };
    document.addEventListener("visibilitychange", onReturn);
    return () => { cancelled = true; document.removeEventListener("visibilitychange", onReturn); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recipient.id]);

  const data = preview && preview.id === recipient.id ? preview.data : null;
  const dose = data?.messages.find((m) => m.kind === "dose" && m.to === "receiver");
  if (state !== "pending" || !data || !dose || data.consent === "stopped" || data.consent === "no_number") return null;

  const zone = recipient.timezone || "Asia/Kolkata";
  const when = dose.at
    ? reminderWhen(new Date(dose.at), zone, language, (k, values) => t(`firstReminderCard.when.${k}`, values))
    : "";
  const title = recipient.is_self
    ? (when ? t("firstReminderCard.titleSelf", { when }) : t("firstReminderCard.titleSelfNoTime"))
    : (when ? t("firstReminderCard.title", { call, when }) : t("firstReminderCard.titleNoTime", { call }));
  const waiting = data.consent === "waiting";
  const close = () => { setFirstReminderCardState(recipient.id, "dismissed"); setState("dismissed"); };

  return (
    <section className="first-reminder-card" aria-label={title}>
      <div className="frc-head">
        <strong className="frc-title">{title}</strong>
        <button type="button" className="frc-close" aria-label={t("firstReminderCard.close")} onClick={close}>×</button>
      </div>
      <p className="frc-note">
        {waiting
          ? data.receiverIsYou ? t("firstReminderCard.waitingYou") : t("firstReminderCard.waiting", { name: data.receiverName })
          : data.receiverIsYou ? t("firstReminderCard.previewYou") : t("firstReminderCard.preview", { name: data.receiverName })}
      </p>
      <div className="frc-chat">
        <Bubble text={dose.text} time={dose.at ? formatClock(new Date(dose.at), zone, data.language) : undefined} note={dose.photo ? t("whatsapp.withPhoto") : undefined} />
      </div>
      <button type="button" className="frc-link" onClick={() => go("whatsapp")}>{t("firstReminderCard.seeAll")}</button>
    </section>
  );
}
