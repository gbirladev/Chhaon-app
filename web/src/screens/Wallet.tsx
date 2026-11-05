import { useEffect, useMemo, useRef, useState, type ChangeEvent, type FormEvent } from "react";
import { useLocale, useTranslations } from "use-intl";
import { AppHeader, Field, Icon, parentCall, useDialog, useSessionExpired, useToast } from "../App";
import type { Recipient } from "../lib/api";
import type { Language } from "../lib/format";
import {
  CATEGORIES, MAX_PAGES, addPages, createDocument, deleteDocument, loadDocument, loadDocuments, loadWalletUsage, removePage, updateDocument, uploadFiles, walletLinks,
  type Category, type DocFields, type WalletDoc,
} from "../lib/wallet";
import { ConfirmSheet } from "./Invites";
import "./wallet.css";

const today = () => new Date().toISOString().slice(0, 10);

function useDateLabel() {
  const language = useLocale() as Language;
  return (iso: string | null, withYear = true) =>
    iso ? new Intl.DateTimeFormat(language === "hi" ? "hi-IN" : "en-IN", { day: "numeric", month: "short", ...(withYear ? { year: "numeric" } : {}) }).format(new Date(`${iso.slice(0, 10)}T00:00:00`)) : "";
}

const bytesLabel = (n: number) => (n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(n / 1024))} KB`);

/** Which of "Take a photo" / "Choose files" produced the picked files. */
function FilePickers({ onPick, disabled }: { onPick: (files: File[]) => void; disabled?: boolean }) {
  const t = useTranslations("wallet");
  const camera = useRef<HTMLInputElement>(null);
  const gallery = useRef<HTMLInputElement>(null);
  const handle = (event: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files ?? []);
    event.target.value = "";
    if (files.length) onPick(files);
  };
  return (
    <div className="picker-row">
      <input ref={camera} type="file" accept="image/*" capture="environment" hidden onChange={handle} />
      <input ref={gallery} type="file" accept="image/*,.pdf,application/pdf" multiple hidden onChange={handle} />
      <button type="button" className="secondary-button" disabled={disabled} onClick={() => camera.current?.click()}><Icon name="camera" size={18} /> {t("takePhoto")}</button>
      <button type="button" className="secondary-button" disabled={disabled} onClick={() => gallery.current?.click()}><Icon name="upload" size={18} /> {t("chooseFiles")}</button>
    </div>
  );
}

type SheetProps =
  | { mode: "add"; recipientId: string; initialFiles: File[]; onClose: () => void; onSaved: (id: string) => void }
  | { mode: "edit"; doc: WalletDoc; onClose: () => void; onSaved: () => void };

/** Add a report (pick pages, then name it) or edit one's details. */
function DocumentSheet(props: SheetProps) {
  const t = useTranslations("wallet");
  const toast = useToast();
  const expireSession = useSessionExpired();
  const dialog = useDialog(props.onClose);
  const editing = props.mode === "edit";
  const doc = props.mode === "edit" ? props.doc : null;
  const [files, setFiles] = useState<File[]>(props.mode === "add" ? props.initialFiles : []);
  const [category, setCategory] = useState<Category>(doc?.category ?? "lab");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState<number | null>(null);
  // Preview links are made and released together with the chosen files (made inside the effect so a re-run never reuses a released link).
  const [previews, setPreviews] = useState<(string | null)[]>([]);
  useEffect(() => {
    const made = files.map((f) => (f.type.startsWith("image/") ? URL.createObjectURL(f) : null));
    setPreviews(made);
    return () => made.forEach((u) => u && URL.revokeObjectURL(u));
  }, [files]);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const title = String(data.get("title") ?? "").replace(/\s+/g, " ").trim();
    const found: Record<string, string> = {};
    if (!title) found.title = t("errTitle");
    if (!editing && files.length === 0) found.files = t("errFiles");
    if (Object.keys(found).length) return setErrors(found);
    const fields: DocFields = {
      title: title.slice(0, 120), category, docDate: String(data.get("date") ?? "") || null,
      doctor: String(data.get("doctor") ?? "").replace(/\s+/g, " ").trim().slice(0, 120) || null,
      notes: String(data.get("notes") ?? "").trim().slice(0, 1000) || null,
    };
    setBusy(true);
    setErrors({});
    if (props.mode === "edit") {
      const ok = await updateDocument(props.doc.id, fields);
      setBusy(false);
      if (!ok) return setErrors({ form: t("errSave") });
      toast(t("saved"));
      return props.onSaved();
    }
    setProgress(0);
    const up = await uploadFiles(props.recipientId, files, setProgress);
    if (!up.ok) {
      setBusy(false); setProgress(null);
      if (up.reason === "auth") return expireSession();
      return setErrors({ form: t(up.reason === "type" ? "errType" : up.reason === "heic" ? "errHeic" : up.reason === "size" ? "errSize" : "errUpload") });
    }
    const saved = await createDocument(props.recipientId, fields, up.items);
    setBusy(false); setProgress(null);
    if (!saved.ok) return setErrors({ form: t(saved.reason === "plan" ? "errPlan" : saved.reason === "limit" ? "errLimit" : saved.reason === "storage" ? "errStorage" : "errSave") });
    toast(t("added"));
    props.onSaved(saved.id);
  };

  return (
    <div className="sheet-backdrop" onClick={busy ? undefined : props.onClose}>
      <section className="sheet wallet-sheet" ref={dialog} tabIndex={-1} role="dialog" aria-modal="true" aria-label={editing ? t("editTitle") : t("addTitle")} onClick={(event) => event.stopPropagation()}>
        <h3>{editing ? t("editTitle") : t("addTitle")}</h3>
        <form onSubmit={submit} noValidate>
          {!editing && (
            <div className="field">
              <p className="field-label">{t("pages")}</p>
              {files.length > 0 && (
                <ul className="picked-files">
                  {files.map((f, i) => (
                    <li key={`${f.name}-${i}`}>
                      {previews[i] ? <img src={previews[i] as string} alt="" /> : files[i].type.startsWith("image/") ? <span className="file-icon image">IMG</span> : <span className="file-icon">PDF</span>}
                      <span>{f.name.length > 28 ? `${f.name.slice(0, 25)}…` : f.name}<small>{bytesLabel(f.size)}</small></span>
                      <button type="button" className="icon-button" aria-label={t("removeFile")} disabled={busy} onClick={() => setFiles(files.filter((_, k) => k !== i))}><Icon name="trash" size={18} /></button>
                    </li>
                  ))}
                </ul>
              )}
              {files.length < MAX_PAGES && <FilePickers disabled={busy} onPick={(picked) => setFiles([...files, ...picked].slice(0, MAX_PAGES))} />}
              {errors.files && <p className="field-error" role="alert">{errors.files}</p>}
            </div>
          )}
          <Field id="w-title" label={t("fieldTitle")} error={errors.title}>
            <input id="w-title" name="title" maxLength={120} autoComplete="off" placeholder={t("titlePlaceholder")} defaultValue={doc?.title ?? ""} aria-invalid={!!errors.title} />
          </Field>
          <Field id="w-category" label={t("fieldCategory")}>
            <select id="w-category" value={category} onChange={(event) => setCategory(event.target.value as Category)}>
              {CATEGORIES.map((c) => <option key={c} value={c}>{t(`cat.${c}`)}</option>)}
            </select>
          </Field>
          <Field id="w-date" label={t("fieldDate")}>
            <input id="w-date" name="date" type="date" max={today()} defaultValue={doc ? (doc.docDate ?? "") : today()} />
          </Field>
          <Field id="w-doctor" label={t("fieldDoctor")}>
            <input id="w-doctor" name="doctor" maxLength={120} autoComplete="off" defaultValue={doc?.doctor ?? ""} />
          </Field>
          <Field id="w-notes" label={t("fieldNotes")}>
            <textarea id="w-notes" name="notes" rows={3} maxLength={1000} defaultValue={doc?.notes ?? ""} />
          </Field>
          {errors.form && <p className="form-error" role="alert">{errors.form}</p>}
          <div className="stack">
            <button className="primary-button" type="submit" disabled={busy}>{progress !== null ? t("uploading", { done: progress, total: files.length }) : busy ? t("saving") : t("save")}</button>
            <button className="secondary-button" type="button" disabled={busy} onClick={props.onClose}>{t("cancel")}</button>
          </div>
        </form>
      </section>
    </div>
  );
}

const Thumb = ({ doc, url }: { doc: WalletDoc; url?: string }) => {
  const first = doc.files[0];
  if (first?.mime === "application/pdf" || !first) return <div className="file-icon">PDF</div>;
  return url ? <img className="wallet-thumb" src={url} alt="" loading="lazy" /> : <div className="file-icon image">IMG</div>;
};

/** The Reports tab: a wallet of the parent's papers. Search, filter by type, open, add. */
export function WalletScreen({ recipient, onOpen, onUpgrade }: { recipient: Recipient | null; onOpen: (id: string) => void; onUpgrade: () => void }) {
  const t = useTranslations("wallet");
  const language = useLocale() as Language;
  const dateLabel = useDateLabel();
  const [docs, setDocs] = useState<WalletDoc[] | null>(null);
  const [error, setError] = useState(false);
  const [thumbs, setThumbs] = useState<Record<string, string>>({});
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<Category | "all">("all");
  const [adding, setAdding] = useState<File[] | null>(null);
  const [usage, setUsage] = useState<{ used: number; max: number | null } | null>(null);
  const recipientId = recipient?.id;
  const call = parentCall(recipient, language);

  const refresh = async () => {
    if (!recipientId) return;
    try {
      const list = await loadDocuments(recipientId);
      setDocs(list); setError(false);
      setThumbs(await walletLinks(recipientId, list.map((d) => d.files[0]?.thumbPath ?? "").filter(Boolean)).catch(() => ({})));  // no previews is fine: icons show
      setUsage(await loadWalletUsage(recipientId));
    } catch { setError(true); }
  };
  useEffect(() => { setDocs(null); setThumbs({}); setUsage(null); void refresh(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [recipientId]);

  const counts = useMemo(() => {
    const c: Record<string, number> = {};
    for (const d of docs ?? []) c[d.category] = (c[d.category] ?? 0) + 1;
    return c;
  }, [docs]);
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (docs ?? []).filter((d) => (filter === "all" || d.category === filter) && (!q || `${d.title} ${d.doctor ?? ""} ${t(`cat.${d.category}`)}`.toLowerCase().includes(q)));
  }, [docs, filter, query, t]);

  return (
    <main className="scroll-page wallet-page">
      <AppHeader title={t("title")} subtitle={t("subtitle", { call })} />
      {usage && usage.max !== null && usage.used >= usage.max ? (
        <section className="wallet-full" role="status">
          <strong>{usage.used > usage.max ? t("overTitle") : t("fullTitle")}</strong>
          <p>{usage.used > usage.max ? t("overBody", { max: usage.max, used: usage.used }) : t("fullBody", { max: usage.max })}</p>
          <button className="primary-button" onClick={onUpgrade}>{t("seePlans")}</button>
        </section>
      ) : (
        <section className="upload-card wallet-add">
          <div><strong>{t("add")}</strong><p>{t("addHint")}</p></div>
          <FilePickers onPick={(files) => setAdding(files)} />
          {usage && usage.max !== null && <p className={`wallet-count${usage.max - usage.used <= 1 ? " near" : ""}`}>{t("usage", { used: usage.used, max: usage.max })}</p>}
        </section>
      )}

      {docs && docs.length > 0 && (
        <>
          <input className="wallet-search" type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder={t("searchPlaceholder")} aria-label={t("searchPlaceholder")} />
          <div className="choice-chips wrap wallet-chips" role="group" aria-label={t("filterLabel")}>
            <button type="button" className={filter === "all" ? "selected" : ""} aria-pressed={filter === "all"} onClick={() => setFilter("all")}>{t("all")} · {docs.length}</button>
            {CATEGORIES.filter((c) => counts[c]).map((c) => (
              <button type="button" key={c} className={filter === c ? "selected" : ""} aria-pressed={filter === c} onClick={() => setFilter(c)}>{t(`cat.${c}`)} · {counts[c]}</button>
            ))}
          </div>
        </>
      )}

      {error && <p role="alert" className="form-error">{t("loadFailed")} <button className="inline-link" onClick={() => void refresh()}>{t("retry")}</button></p>}
      {docs === null && !error && <div className="skeleton card" aria-busy="true" />}
      {docs !== null && docs.length === 0 && <div className="empty-note"><strong>{t("emptyTitle")}</strong><p>{t("emptyBody", { call })}</p></div>}
      {docs !== null && docs.length > 0 && shown.length === 0 && <p className="note-quiet">{t("noMatch")}</p>}
      <section className="reports-list">
        {shown.map((d) => (
          <button className="report-card" key={d.id} onClick={() => onOpen(d.id)}>
            <Thumb doc={d} url={d.files[0]?.thumbPath ? thumbs[d.files[0].thumbPath] : undefined} />
            <div>
              <h3>{d.title}</h3>
              <p>{[t(`cat.${d.category}`), dateLabel(d.docDate ?? d.createdAt), d.files.length > 1 ? t("pageCount", { count: d.files.length }) : ""].filter(Boolean).join(" • ")}</p>
              {d.doctor && <small className="wallet-doctor">{d.doctor}</small>}
            </div>
            <Icon name="chevron" size={19} />
          </button>
        ))}
      </section>
      <p className="note-quiet wallet-private">{t("private")}</p>

      {adding && recipientId && (
        <DocumentSheet mode="add" recipientId={recipientId} initialFiles={adding} onClose={() => setAdding(null)} onSaved={(id) => { setAdding(null); void refresh(); onOpen(id); }} />
      )}
    </main>
  );
}

/** One report: its pages, details, and what you can do with it. */
export function WalletDetail({ go, documentId }: { go: (screen: "reports", options?: { replace?: boolean }) => void; documentId: string | null }) {
  const t = useTranslations("wallet");
  const toast = useToast();
  const expireSession = useSessionExpired();
  const dateLabel = useDateLabel();
  const [doc, setDoc] = useState<WalletDoc | null | undefined>(undefined);
  const [urls, setUrls] = useState<Record<string, string>>({});
  const [error, setError] = useState(false);
  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const more = useRef<HTMLInputElement>(null);

  const load = async () => {
    if (!documentId) return setDoc(null);
    try {
      const d = await loadDocument(documentId);
      setDoc(d); setError(false);
      setUrls(d ? await walletLinks(d.recipientId, d.files.map((f) => f.path)) : {});
    } catch { setError(true); }
  };
  useEffect(() => { void load(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [documentId]);

  const back = () => go("reports");
  const remove = async () => {
    if (!doc) return;
    setBusy(true);
    const ok = await deleteDocument(doc);
    setBusy(false);
    if (!ok) return toast(t("errSave"));
    toast(t("deleted"));
    go("reports", { replace: true });
  };
  const addMore = async (event: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files ?? []);
    event.target.value = "";
    if (!doc || !files.length) return;
    if (doc.files.length + files.length > MAX_PAGES) return toast(t("errPages"));
    setBusy(true);
    const up = await uploadFiles(doc.recipientId, files);
    if (!up.ok) {
      setBusy(false);
      if (up.reason === "auth") return expireSession();
      return toast(t(up.reason === "type" ? "errType" : up.reason === "heic" ? "errHeic" : up.reason === "size" ? "errSize" : "errUpload"));
    }
    const saved = await addPages(doc.id, doc.files.length, up.items, doc.recipientId);
    setBusy(false);
    if (!saved.ok) return toast(t(saved.reason === "pages" ? "errPages" : saved.reason === "storage" ? "errStorage" : "errSave"));
    toast(t("added"));
    void load();
  };
  const dropPage = async (fileId: string) => {
    const file = doc?.files.find((f) => f.id === fileId);
    if (!doc || !file || busy) return;
    setBusy(true);
    const ok = await removePage(doc.id, file);
    setBusy(false);
    if (!ok) return toast(t("errSave"));
    void load();
  };

  if (doc === undefined && !error) return <main className="scroll-page no-nav"><AppHeader title={t("title")} back={back} /><div className="skeleton card" aria-busy="true" /></main>;
  if (error) return <main className="scroll-page no-nav"><AppHeader title={t("title")} back={back} /><p role="alert" className="form-error">{t("loadFailed")} <button className="inline-link" onClick={() => { setError(false); void load(); }}>{t("retry")}</button></p></main>;
  if (!doc) return <main className="scroll-page no-nav"><AppHeader title={t("title")} back={back} /><div className="empty-note"><strong>{t("notFound")}</strong></div></main>;

  return (
    <main className="scroll-page no-nav wallet-page">
      <AppHeader title={doc.title} subtitle={[t(`cat.${doc.category}`), dateLabel(doc.docDate ?? doc.createdAt)].filter(Boolean).join(" • ")} back={back} />
      <section className="wallet-pages" aria-label={t("pages")}>
        {doc.files.map((f, i) => (
          <figure key={f.id} className="wallet-page-card">
            {f.mime === "application/pdf" ? (
              <div className="pdf-card"><span className="file-icon">PDF</span><span>{t("pdfFile", { size: bytesLabel(f.sizeBytes) })}</span></div>
            ) : urls[f.path] ? (
              <a href={urls[f.path]} target="_blank" rel="noopener noreferrer"><img src={urls[f.path]} alt={t("pageOf", { n: i + 1 })} loading="lazy" /></a>
            ) : <div className="skeleton card" />}
            <figcaption>
              <span>{doc.files.length > 1 ? t("pageOf", { n: i + 1 }) : t("onePage")}</span>
              {urls[f.path] && <a className="inline-link" href={urls[f.path]} target="_blank" rel="noopener noreferrer">{t("open")}</a>}
              {urls[f.path] && <a className="inline-link" href={urls[f.path]} download>{t("download")}</a>}
              {doc.files.length > 1 && <button className="inline-link" disabled={busy} onClick={() => void dropPage(f.id)}>{t("removePage")}</button>}
            </figcaption>
          </figure>
        ))}
      </section>

      <section className="wallet-meta">
        {doc.doctor && <div><small>{t("fieldDoctor")}</small><strong>{doc.doctor}</strong></div>}
        {doc.notes && <div><small>{t("fieldNotes")}</small><p>{doc.notes}</p></div>}
        <div><small>{t("addedOn", { date: dateLabel(doc.createdAt) })}</small></div>
      </section>

      <div className="stack wallet-actions">
        {doc.files.length < MAX_PAGES && (
          <>
            <input ref={more} type="file" accept="image/*,.pdf,application/pdf" multiple hidden onChange={addMore} />
            <button className="secondary-button" disabled={busy} onClick={() => more.current?.click()}><Icon name="plus" size={18} /> {t("addPages")}</button>
          </>
        )}
        <button className="secondary-button" disabled={busy} onClick={() => setEditing(true)}><Icon name="edit" size={18} /> {t("edit")}</button>
        <button className="danger-button" disabled={busy} onClick={() => setConfirming(true)}>{t("delete")}</button>
      </div>

      {editing && <DocumentSheet mode="edit" doc={doc} onClose={() => setEditing(false)} onSaved={() => { setEditing(false); void load(); }} />}
      {confirming && <ConfirmSheet title={t("deleteTitle")} body={t("deleteBody")} confirm={t("delete")} busy={busy} onConfirm={() => void remove()} onCancel={() => setConfirming(false)} />}
    </main>
  );
}
