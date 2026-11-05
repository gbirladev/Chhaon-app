import { useEffect, useRef, useState, type ChangeEvent } from "react";
import { useLocale, useTranslations } from "use-intl";
import { AppHeader, Field, Icon, useDialog, useToast } from "../App";
import { FEEDBACK_BUCKET, FEEDBACK_KINDS, FEEDBACK_MAX, FEEDBACK_MAX_IMAGES, loadAdminFeedback, prepareImage, sendFeedback, uploadFeedbackImage, type FeedbackItem, type FeedbackKind, type PreparedImage } from "../lib/feedback";
import { signedUrls } from "../lib/wallet";
import "./feedback.css";
import { formatDateTime } from "../lib/format";

type Picture = { id: string; image: PreparedImage; preview: string; path: string | null };

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
    if (!message.trim()) { setError(t("errEmpty")); return; }
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
    const result = await sendFeedback({ kind, message, rating, screen: from, language: locale, images: paths });
    setBusy(false);
    if (result === "ok") { setSent(true); toast(t("sentToast")); }
    else setError(t(result === "limit" ? "errLimit" : "errGeneric"));
  };
  const again = () => {
    pictures.forEach((p) => URL.revokeObjectURL(p.preview));
    setPictures([]); setMessage(""); setRating(null); setKind("idea"); setSent(false);
  };

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
          {error && <p role="alert" className="form-error">{error}</p>}
          <button className="primary-button" type="submit" disabled={busy || preparing || !message.trim()}><Icon name="send" size={18} /> {busy ? t("sending") : t("send")}</button>
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
