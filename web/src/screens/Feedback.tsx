import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "use-intl";
import { AppHeader, Field, Icon, useToast } from "../App";
import { FEEDBACK_KINDS, FEEDBACK_MAX, loadAdminFeedback, sendFeedback, type FeedbackItem, type FeedbackKind } from "../lib/feedback";
import "./feedback.css";

/** Send an idea, a problem or a thank-you to the people who make the app. No health details or phone numbers are asked for. */
export function FeedbackScreen({ go, from = "family" }: { go: (screen: "family") => void; from?: string }) {
  const t = useTranslations("feedback");
  const locale = useLocale();
  const toast = useToast();
  const [kind, setKind] = useState<FeedbackKind>("idea");
  const [rating, setRating] = useState<number | null>(null);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState("");

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (busy) return;
    if (!message.trim()) { setError(t("errEmpty")); return; }
    setBusy(true);
    setError("");
    const result = await sendFeedback({ kind, message, rating, screen: from, language: locale });
    setBusy(false);
    if (result === "ok") { setSent(true); toast(t("sentToast")); }
    else setError(t(result === "limit" ? "errLimit" : "errGeneric"));
  };
  const again = () => { setMessage(""); setRating(null); setKind("idea"); setSent(false); };

  return (
    <main className="scroll-page no-nav feedback-page">
      <AppHeader title={t("title")} subtitle={t("subtitle")} back={() => go("family")} />
      {sent ? (
        <section className="feedback-thanks" role="status">
          <span className="feedback-thanks-icon"><Icon name="heart" size={28} /></span>
          <h2>{t("thanksTitle")}</h2>
          <p>{t("thanksBody")}</p>
          <div className="stack">
            <button className="primary-button" type="button" onClick={() => go("family")}>{t("done")}</button>
            <button className="secondary-button" type="button" onClick={again}>{t("sendAnother")}</button>
          </div>
        </section>
      ) : (
        <form className="form-card" onSubmit={(event) => void submit(event)} noValidate>
          <div className="field">
            <span className="feedback-label" id="fb-kind-label">{t("kindLabel")}</span>
            <div className="choice-chips wrap" role="radiogroup" aria-labelledby="fb-kind-label">
              {FEEDBACK_KINDS.map((k) => (
                <button key={k} type="button" role="radio" aria-checked={kind === k} className={kind === k ? "selected" : ""} onClick={() => setKind(k)}>{t(`kind_${k}`)}</button>
              ))}
            </div>
          </div>
          <div className="field">
            <span className="feedback-label" id="fb-rating-label">{t("ratingLabel")}</span>
            <div className="feedback-stars" role="radiogroup" aria-labelledby="fb-rating-label">
              {[1, 2, 3, 4, 5].map((n) => (
                <button key={n} type="button" role="radio" aria-checked={rating === n} aria-label={t("ratingOption", { n })} className={rating !== null && n <= rating ? "on" : ""} onClick={() => setRating(rating === n ? null : n)}>
                  <svg width="28" height="28" viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9Z" strokeWidth="1.6" strokeLinejoin="round" /></svg>
                </button>
              ))}
            </div>
            <p className="feedback-hint">{t("ratingHint")}</p>
          </div>
          <Field id="fb-message" label={t("messageLabel")}>
            <textarea id="fb-message" rows={6} maxLength={FEEDBACK_MAX} value={message} placeholder={t("messagePlaceholder")} onChange={(event) => { setMessage(event.target.value); if (error) setError(""); }} aria-describedby="fb-count fb-privacy" />
          </Field>
          <div className="feedback-meta">
            <p id="fb-privacy" className="feedback-hint">{t("privacyNote")}</p>
            <span id="fb-count" className={`feedback-count${message.length >= FEEDBACK_MAX - 50 ? " near" : ""}`}>{message.length}/{FEEDBACK_MAX}</span>
          </div>
          {error && <p role="alert" className="form-error">{error}</p>}
          <button className="primary-button" type="submit" disabled={busy || !message.trim()}><Icon name="send" size={18} /> {busy ? t("sending") : t("send")}</button>
        </form>
      )}
    </main>
  );
}

/** For the owner only (shown on the Pilot stats screen): the newest feedback, with who wrote it. */
export function FeedbackInbox() {
  const t = useTranslations("feedback");
  const locale = useLocale();
  const [items, setItems] = useState<FeedbackItem[] | "error" | null>(null);
  useEffect(() => {
    let cancelled = false;
    loadAdminFeedback().then((list) => { if (!cancelled) setItems(list); }).catch(() => { if (!cancelled) setItems("error"); });
    return () => { cancelled = true; };
  }, []);
  const when = (iso: string) => new Intl.DateTimeFormat(locale === "hi" ? "hi-IN" : "en-IN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso));
  return (
    <section className="feedback-inbox" aria-label={t("inboxTitle")}>
      <h2>{t("inboxTitle")}</h2>
      {items === null && <p className="note" role="status">{t("inboxLoading")}</p>}
      {items === "error" && <p className="note" role="alert">{t("inboxError")}</p>}
      {Array.isArray(items) && items.length === 0 && <p className="note">{t("inboxEmpty")}</p>}
      {Array.isArray(items) && items.map((f) => (
        <article className="feedback-item" key={f.id}>
          <header>
            <strong>{(FEEDBACK_KINDS as readonly string[]).includes(f.kind) ? t(`kind_${f.kind as FeedbackKind}`) : f.kind}</strong>
            {f.rating !== null && <span className="feedback-rating" role="img" aria-label={t("ratingOutOf", { n: f.rating })}>{"★".repeat(f.rating)}{"☆".repeat(5 - f.rating)}</span>}
          </header>
          <p className="feedback-text">{f.message}</p>
          <small>{[f.name || t("noName"), f.email, when(f.createdAt), f.screen, f.language.toUpperCase()].filter(Boolean).join(" · ")}</small>
        </article>
      ))}
    </section>
  );
}
