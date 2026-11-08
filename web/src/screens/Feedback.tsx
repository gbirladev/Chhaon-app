import { useEffect, useRef, useState, type ChangeEvent } from "react";
import { useLocale, useTranslations } from "use-intl";
import { AppHeader, Field, Icon, parentCall, useDialog, useToast } from "../App";
import { APP_BUILD, browserFamily, FEEDBACK_BUCKET, FEEDBACK_KINDS, FEEDBACK_MAX, FEEDBACK_MAX_IMAGES, feedbackPreset, loadAdminFeedback, prepareImage, PROBLEM_CHIPS, sendFeedback, uploadFeedbackImage, type FeedbackItem, type FeedbackKind, type FeedbackPreset, type PreparedImage, type ProblemContext, type ProblemTopic } from "../lib/feedback";
import type { Recipient } from "../lib/api";
import type { Language } from "../lib/format";
import { receiverOf } from "../lib/reminderReceiver";
import { authUserId } from "../lib/auth";
import { signedUrls } from "../lib/wallet";
import "./feedback.css";
import { formatDateTime } from "../lib/format";

type Picture = { id: string; image: PreparedImage; preview: string; path: string | null };

/** The problem-report note, built only from what the app already has: no names, phone numbers or health details. */
function problemContext(recipient: Recipient | null, from: string, locale: string, lastReminder: FeedbackPreset["lastReminder"]): ProblemContext {
  const { browser, os } = browserFamily();
  const out: ProblemContext = { app_build: APP_BUILD, browser, os, screen: from.slice(0, 40), language: locale === "hi" ? "hi" : "en" };
  if (!recipient) return out;
  out.recipient_id = recipient.id;
  out.agreed = recipient.opted_out_at ? "stopped" : recipient.consent_at ? "yes" : "no";
  const r = receiverOf(recipient);
  out.reminders_to = recipient.is_self || r.reminder_to === "person" ? "person" : r.reminder_to === "helper" ? "helper" : r.reminder_user_id === authUserId() ? "me" : "family";
  if (lastReminder) { out.last_reminder_at = lastReminder.at; out.last_reminder_status = lastReminder.status; }
  return out;
}

const SHOWN_STATUSES = ["pending", "sent", "taken", "taken_late", "missed", "skipped"];
/** The "We'll also send" list, in plain words, one line per thing sent (polls on / off is added by the server). */
function contextLines(c: ProblemContext, tp: ReturnType<typeof useTranslations>, call: string): string[] {
  const lines = [tp("ctx_app", { build: c.app_build ?? "" }), tp("ctx_device", { browser: c.browser ?? "", os: c.os ?? "" }), tp("ctx_screen", { screen: c.screen ?? "" }), tp(`ctx_language_${c.language === "hi" ? "hi" : "en"}`)];
  if (c.agreed) lines.push(tp(`ctx_agreed_${c.agreed}`, { call }));
  if (c.reminders_to) lines.push(tp(`ctx_to_${c.reminders_to}`, { call }));
  if (c.last_reminder_at && c.last_reminder_status && SHOWN_STATUSES.includes(c.last_reminder_status)) {
    const time = new Date(c.last_reminder_at).toLocaleTimeString(c.language === "hi" ? "hi-IN" : "en-IN", { hour: "numeric", minute: "2-digit" });
    lines.push(tp("ctx_last", { time, status: tp(`status_${c.last_reminder_status}`) }));
  }
  lines.push(tp("ctx_polls"));
  if (c.recipient_id) lines.push(tp("ctx_id", { call }));
  return lines;
}

/** Send an idea, a problem or a thank-you to the people who make the app. No health details or phone numbers are asked for. */
export function FeedbackScreen({ go, recipient = null }: { go: (screen: "family" | "today") => void; recipient?: Recipient | null }) {
  const t = useTranslations("feedback");
  const tp = useTranslations("reportProblem");
  const locale = useLocale();
  const toast = useToast();
  // Opened from "Report a problem" / "Papa didn't get the message?" / ...: start on that kind and topic (lib/feedback presets).
  const [start] = useState(() => feedbackPreset());
  const from = start?.from ?? "family";
  const back = () => go(from === "today" ? "today" : "family");
  const call = parentCall(recipient, locale as Language);
  const [kind, setKind] = useState<FeedbackKind>(start?.kind ?? "idea");
  const [topic, setTopic] = useState<ProblemTopic | null>(start?.topic ?? null);
  const problem = kind === "problem";
  // "Something else" (and a problem with no chip) needs words; the other chips say enough on their own.
  const textRequired = !problem || topic === null || topic === "other";
  const context = problem ? problemContext(recipient, from, locale, start?.lastReminder ?? null) : null;
  const [rating, setRating] = useState<number | null>(null);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState("");
  // Chosen pictures: shrunk when picked; a picture keeps its storage path once uploaded, so a retry after a failed send does not upload it twice.
  const [pictures, setPictures] = useState<Picture[]>([]);
  const [preparing, setPreparing] = useState(false);
  const picker = useRef<HTMLInputElement>(null);
  const live = useRef<Picture[]>([]);
  useEffect(() => { live.current = pictures; }, [pictures]);
  useEffect(() => () => live.current.forEach((p) => URL.revokeObjectURL(p.preview)), []);

  const pick = async (event: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files ?? []);
    event.target.value = "";
    if (!files.length) return;
    setPreparing(true);
    setError("");
    const added: Picture[] = [];
    let problem = "";
    for (const file of files.slice(0, Math.max(0, FEEDBACK_MAX_IMAGES - pictures.length))) {
      const prepared = await prepareImage(file);
      if (prepared.ok) added.push({ id: crypto.randomUUID(), image: prepared.image, preview: URL.createObjectURL(prepared.image.blob), path: null });
      else problem = prepared.reason === "heic" ? "errImageHeic" : prepared.reason === "size" ? "errImageSize" : "errImageType";
    }
    if (!problem && files.length > added.length) problem = "errImageMax";
    setPictures((current) => [...current, ...added]);
    setPreparing(false);
    if (problem) setError(t(problem));
  };
  const drop = (id: string) => {
    const gone = pictures.find((p) => p.id === id);
    if (gone) URL.revokeObjectURL(gone.preview);
    setPictures((current) => current.filter((p) => p.id !== id));
    if (error) setError("");
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (busy) return;
    if (textRequired && !message.trim()) { setError(t("errEmpty")); return; }
    setBusy(true);
    setError("");
    // pictures first: if one cannot be uploaded the form stays as it is and nothing is sent
    const paths: string[] = [];
    for (const picture of pictures) {
      const path = picture.path ?? (await uploadFeedbackImage(picture.image));
      if (!path) { setBusy(false); setError(t("errImageUpload")); return; }
      if (!picture.path) setPictures((current) => current.map((p) => (p.id === picture.id ? { ...p, path } : p)));
      paths.push(path);
    }
    // A chip with no words: the message is the chip in plain words (no name), so it also reads well before the migration.
    const text = message.trim() || (problem && topic ? tp(`plain_${topic}`) : "");
    const result = await sendFeedback({ kind, message: text, rating, screen: from, language: locale, images: paths, topic: problem ? topic : null, context });
    setBusy(false);
    if (result === "ok") { setSent(true); toast(t("sentToast")); }
    else setError(t(result === "limit" ? "errLimit" : "errGeneric"));
  };
  const again = () => {
    pictures.forEach((p) => URL.revokeObjectURL(p.preview));
    setPictures([]); setMessage(""); setRating(null); setKind("idea"); setTopic(null); setSent(false);
  };
  const shown = context ? contextLines(context, tp, call) : [];

  return (
    <main className="scroll-page no-nav feedback-page">
      <AppHeader title={problem ? tp("title") : t("title")} subtitle={problem ? tp("subtitle") : t("subtitle")} back={back} />
      {sent ? (
        <section className="feedback-thanks" role="status">
          <span className="feedback-thanks-icon"><Icon name="heart" size={28} /></span>
          <h2>{t("thanksTitle")}</h2>
          <p>{problem ? tp("thanksBody") : t("thanksBody")}</p>
          <div className="stack">
            <button className="primary-button" type="button" onClick={back}>{t("done")}</button>
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
          {problem && (
            <div className="field">
              <span className="feedback-label" id="fb-topic-label">{tp("topicLabel")}</span>
              <div className="choice-chips wrap feedback-topics" role="radiogroup" aria-labelledby="fb-topic-label">
                {PROBLEM_CHIPS.map((k) => (
                  <button key={k} type="button" role="radio" aria-checked={topic === k} className={topic === k ? "selected" : ""} onClick={() => { setTopic(topic === k ? null : k); if (error) setError(""); }}>{tp(`chip_${k}`, { call })}</button>
                ))}
              </div>
            </div>
          )}
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
          <Field id="fb-message" label={textRequired ? t("messageLabel") : tp("messageOptional")}>
            <textarea id="fb-message" rows={6} maxLength={FEEDBACK_MAX} value={message} placeholder={problem ? tp("messagePlaceholder") : t("messagePlaceholder")} onChange={(event) => { setMessage(event.target.value); if (error) setError(""); }} aria-describedby="fb-count fb-privacy" />
          </Field>
          <div className="feedback-meta">
            <p id="fb-privacy" className="feedback-hint">{t("privacyNote")}</p>
            <span id="fb-count" className={`feedback-count${message.length >= FEEDBACK_MAX - 50 ? " near" : ""}`}>{message.length}/{FEEDBACK_MAX}</span>
          </div>
          <div className="field">
            <span className="feedback-label" id="fb-images-label">{t("imagesLabel")}</span>
            <input ref={picker} type="file" accept="image/*" multiple hidden data-testid="fb-file" onChange={(event) => void pick(event)} />
            {pictures.length > 0 && (
              <ul className="feedback-thumbs" aria-labelledby="fb-images-label">
                {pictures.map((p, i) => (
                  <li key={p.id}>
                    <img src={p.preview} alt={t("imageAlt", { n: i + 1 })} />
                    <button type="button" className="feedback-thumb-x" aria-label={t("removeImage", { n: i + 1 })} disabled={busy} onClick={() => drop(p.id)}>×</button>
                  </li>
                ))}
              </ul>
            )}
            {pictures.length < FEEDBACK_MAX_IMAGES && (
              <button type="button" className="secondary-button feedback-attach" disabled={busy || preparing} onClick={() => picker.current?.click()}>
                <Icon name="camera" size={18} /> {preparing ? t("preparing") : t("attach", { left: FEEDBACK_MAX_IMAGES - pictures.length })}
              </button>
            )}
            <p className="feedback-hint">{t("imagesPrivacy")}</p>
          </div>
          {shown.length > 0 && (
            <div className="feedback-context">
              <span className="feedback-label" id="fb-context-label">{tp("contextLabel")}</span>
              <ul aria-labelledby="fb-context-label">{shown.map((line) => <li key={line}>{line}</li>)}</ul>
              <p className="feedback-hint">{tp("contextNote")}</p>
            </div>
          )}
          {error && <p role="alert" className="form-error">{error}</p>}
          <button className="primary-button" type="submit" disabled={busy || preparing || (textRequired && !message.trim())}><Icon name="send" size={18} /> {busy ? t("sending") : problem ? tp("send") : t("send")}</button>
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
  const [links, setLinks] = useState<Record<string, string>>({});   // short-lived signed links, by storage path
  const [open, setOpen] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    loadAdminFeedback().then(async (list) => {
      const urls = await signedUrls(list.flatMap((f) => f.images), 300, FEEDBACK_BUCKET).catch(() => ({}));
      if (!cancelled) { setLinks(urls); setItems(list); }
    }).catch(() => { if (!cancelled) setItems("error"); });
    return () => { cancelled = true; };
  }, []);
  const when = (iso: string) => formatDateTime(new Date(iso), locale, undefined, { day: "numeric", month: "short", year: "numeric" });
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
          {f.images.length > 0 && (
            <div className="feedback-images">
              {f.images.map((path, i) => links[path]
                ? <button key={path} type="button" className="feedback-image" aria-label={t("openImage", { n: i + 1 })} onClick={() => setOpen(links[path])}><img src={links[path]} alt="" loading="lazy" /></button>
                : <span key={path} className="feedback-image missing">{t("imageMissing")}</span>)}
            </div>
          )}
          <small>{[f.name || t("noName"), f.email, when(f.createdAt), f.screen, f.language.toUpperCase()].filter(Boolean).join(" · ")}</small>
        </article>
      ))}
      {open && <ImageViewer url={open} onClose={() => setOpen(null)} />}
    </section>
  );
}

/** A picture shown large over the page; Escape, the backdrop or the close button closes it. */
function ImageViewer({ url, onClose }: { url: string; onClose: () => void }) {
  const t = useTranslations("feedback");
  const dialog = useDialog(onClose);
  return (
    <div className="feedback-viewer" role="dialog" aria-modal="true" aria-label={t("viewerLabel")} tabIndex={-1} ref={dialog as React.RefObject<HTMLDivElement>} onClick={onClose}>
      <button type="button" className="sheet-x feedback-viewer-x" aria-label={t("close")} onClick={onClose}>×</button>
      <img src={url} alt={t("viewerLabel")} onClick={(event) => event.stopPropagation()} />
    </div>
  );
}
