import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "use-intl";
import { Icon } from "../App";
import { Logo } from "./Logo";
import { loadRecentActivity, type Activity, type Recipient } from "../lib/api";
import { formatClock, localDate, type Language } from "../lib/format";
import { loadWhatsAppPreview, waLines, type PreviewMessage, type WhatsAppPreview } from "../lib/whatsappPreview";
import "./whatsappPreview.css";

/** A chat bubble with the exact WhatsApp text: one paragraph per line, *bold* as bold, no buttons (the receiver types 1 or 👍). */
function Bubble({ text, time, note }: { text: string; time?: string; note?: string }) {
  return (
    <div className="wa-message wa-text">
      {note && <em className="wa-note">{note}</em>}
      {waLines(text).map((parts, i) => parts.length === 0
        ? <p key={i} className="wa-gap" aria-hidden="true" />
        : <p key={i}>{parts.map((p, j) => p.bold ? <strong key={j}>{p.text}</strong> : <span key={j}>{p.text}</span>)}</p>)}
      {time && <small>{time} ✓✓</small>}
    </div>
  );
}

/**
 * "Preview WhatsApp": what the person who RECEIVES the reminders gets (the parent, a helper such as Kamla (Nani), or a family member).
 * Every text comes from the backend, built by the same templates as the real send, so this screen never shows a message that is not
 * sent. The last 3 days of real reminders are shown when there are any; otherwise the next messages, in order.
 */
export function WhatsAppScreen({ go, recipient }: { go: (screen: "family") => void; recipient: Recipient | null }) {
  const t = useTranslations("whatsapp");
  const tProfile = useTranslations("profile");
  const uiLanguage = useLocale() as Language;
  const tz = recipient?.timezone ?? "Asia/Kolkata";
  const [preview, setPreview] = useState<WhatsAppPreview | null>(null);
  const [failed, setFailed] = useState(false);
  // The real history: what was sent in the last 3 days and how it was answered (null = still loading).
  const [activity, setActivity] = useState<Activity[] | null>(null);

  useEffect(() => {
    if (!recipient) return;
    let cancelled = false;
    const load = () => {
      loadWhatsAppPreview(recipient.id).then((p) => { if (!cancelled) { setPreview(p); setFailed(false); } }).catch(() => { if (!cancelled) setFailed(true); });
      loadRecentActivity(recipient).then((a) => { if (!cancelled) setActivity(a); }).catch(() => { if (!cancelled) setActivity([]); });
    };
    load();
    const onReturn = () => { if (document.visibilityState === "visible") load(); };
    document.addEventListener("visibilitychange", onReturn);
    return () => { cancelled = true; document.removeEventListener("visibilitychange", onReturn); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recipient?.id]);

  const receiverLanguage: Language = preview?.language ?? (recipient?.language === "en" ? "en" : "hi");
  const name = preview?.receiverName ?? "";
  const you = preview?.receiverIsYou ?? false;
  const time = (iso: string) => formatClock(new Date(iso), tz, receiverLanguage);
  const dayLabel = (iso: string) => {
    const day = localDate(new Date(iso), tz);
    if (day === localDate(new Date(), tz)) return t("today");
    if (day === localDate(new Date(Date.now() - 24 * 3600_000), tz)) return t("yesterday");
    if (day === localDate(new Date(Date.now() + 24 * 3600_000), tz)) return t("tomorrow");
    return new Intl.DateTimeFormat(uiLanguage === "hi" ? "hi-IN" : "en-IN", { day: "numeric", month: "short", timeZone: tz }).format(new Date(iso));
  };
  const real = activity && activity.length > 0 ? activity : null;
  const loading = recipient !== null && (preview === null || activity === null) && !failed;
  const byKind = (kind: PreviewMessage["kind"]) => preview?.messages.find((m) => m.kind === kind);
  const doseText = (medicine: string) => preview?.doses.find((d) => d.medicine === medicine)?.text;
  const checkinText = byKind("checkin")?.text;
  const status = (key: "noReply" | "waiting" | "replied" | "confirmed") => t(you ? `${key}You` : key, { name });

  const realItems = (items: Activity[]) => {
    let lastDay = "";
    return items.map((a) => {
      const day = localDate(new Date(a.at), tz);
      const heading = day !== lastDay ? <div key={`d-${a.id}`} className="wa-date next-day">{dayLabel(a.at)}</div> : null;
      lastDay = day;
      if (a.kind === "dose") {
        const text = doseText(a.name) ?? [a.name, a.dosage].filter(Boolean).join(" · ");
        return (
          <div key={a.id}>
            {heading}
            <Bubble text={text} time={time(a.at)} />
            {(a.status === "taken" || a.status === "taken_late") && <div className="wa-helper wa-status">{status("confirmed")}{a.respondedAt ? ` · ${time(a.respondedAt)}` : ""}</div>}
            {a.status === "missed" && <div className="wa-helper wa-status">{status("noReply")}</div>}
            {a.status === "sent" && <div className="wa-helper wa-status">{status("waiting")}</div>}
          </div>
        );
      }
      return (
        <div key={a.id}>
          {heading}
          {checkinText ? <Bubble text={checkinText} time={time(a.at)} /> : null}
          {a.status === "replied" && <div className="wa-helper wa-status">{status("replied")}{a.respondedAt ? ` · ${time(a.respondedAt)}` : ""}</div>}
          {a.status === "missed" && <div className="wa-helper wa-status">{status("noReply")}</div>}
          {a.status === "sent" && <div className="wa-helper wa-status">{status("waiting")}</div>}
        </div>
      );
    });
  };

  const upcoming = (p: WhatsAppPreview) => {
    const consent = byKind("consent");
    const dose = byKind("dose");
    const checkin = byKind("checkin");
    const vaccine = byKind("vaccine");
    const alert = byKind("alert");
    return (
      <>
        {consent && p.consent !== "agreed" && (
          <>
            <div className="wa-date">{t("firstMessage")}</div>
            <Bubble text={consent.text} />
          </>
        )}
        {dose ? (
          <>
            <div className="wa-date next-day">{dose.at ? `${t("nextReminder")} · ${dayLabel(dose.at)}` : t("nextReminder")}</div>
            <Bubble text={dose.text} time={dose.at ? time(dose.at) : undefined} note={dose.photo ? t("withPhoto") : undefined} />
          </>
        ) : <div className="wa-helper wa-status">{t("noMedicine")}</div>}
        {checkin && (
          <>
            <div className="wa-date next-day">{checkin.at ? `${t("checkinLabel")} · ${dayLabel(checkin.at)}` : t("checkinLabel")}</div>
            <Bubble text={checkin.text} time={checkin.at ? time(checkin.at) : undefined} />
          </>
        )}
        {vaccine && (
          <>
            <div className="wa-date next-day">{t("vaccineLabel")}</div>
            <Bubble text={vaccine.text} />
          </>
        )}
        {alert && (
          <>
            <div className="wa-date next-day">{t("familyAlert")}</div>
            <Bubble text={alert.text} />
          </>
        )}
      </>
    );
  };

  const language = tProfile(receiverLanguage === "en" ? "langEnglish" : "langHindi");
  return (
    <main className="whatsapp-page">
      <h1 className="sr-only">{t("title")}</h1>
      <header className="wa-header"><button onClick={() => go("family")} aria-label={t("back")}><Icon name="back" /></button><Logo className="wa-logo" size={41} inverse /><div><strong>Chhaon</strong><small>{t("title")}</small></div></header>
      <div className="wa-chat">
        {failed && <div className="wa-helper preview-note wa-first">{t("loadError")}</div>}
        {preview && !loading && (
          <>
            <div className={`wa-helper wa-first${real ? "" : " preview-note"}`}>{you ? t("receivesYou", { language }) : t("receives", { name, language })}</div>
            <div className="wa-helper wa-status">{real ? t("realNote", { name }) : t("sampleNote", { name })}</div>
            {preview.consent === "waiting" && <div className="wa-helper wa-status">{t(you ? "waitingConsentYou" : "waitingConsent", { name })}</div>}
            {preview.consent === "stopped" && <div className="wa-helper wa-status">{t(you ? "stoppedYou" : "stopped", { name })}</div>}
            {preview.consent === "no_number" && <div className="wa-helper wa-status">{t("noNumber")}</div>}
            {real ? realItems(real) : upcoming(preview)}
            <div className="wa-helper">{t(you ? "helperYou" : "helper", { name })}</div>
          </>
        )}
      </div>
      <div className="wa-input" aria-hidden="true"><span>{t("inputPlaceholder")}</span><button type="button" tabIndex={-1} disabled aria-hidden="true" aria-label={t("inputPlaceholder")}><Icon name="send" /></button></div>
    </main>
  );
}
