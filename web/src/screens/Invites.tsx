import { useEffect, useRef, useState } from "react";
import "./invites.css";
import { useLocale, useTranslations } from "use-intl";
import { Icon, shareContent, useDialog, useToast } from "../App";
import { formatDate, type Language } from "../lib/format";
import {
  acceptInvite, clearPendingInvite, createInvite, inviteLink, loadPendingInvites, previewInvite, revokeInvite,
  type InviteFailure, type InvitePreview, type PendingInvite,
} from "../lib/invites";

/** A small confirmation sheet (remove a member, leave a circle). */
export function ConfirmSheet({ title, body, confirm, busy, onConfirm, onCancel }: { title: string; body: string; confirm: string; busy: boolean; onConfirm: () => void; onCancel: () => void }) {
  const t = useTranslations("invites");
  const dialog = useDialog(onCancel);
  return (
    <div className="sheet-backdrop" onClick={onCancel}>
      <section className="sheet" ref={dialog} tabIndex={-1} role="alertdialog" aria-modal="true" aria-label={title} onClick={(event) => event.stopPropagation()}>
        <h3>{title}</h3>
        <p className="sheet-empty">{body}</p>
        <div className="stack">
          <button className="danger-button" onClick={onConfirm} disabled={busy}>{confirm}</button>
          <button className="secondary-button" onClick={onCancel}>{t("cancel")}</button>
        </div>
      </section>
    </div>
  );
}

/** Makes one single-use link and offers the ways to send it (the caregiver's own apps: no email or SMS service). */
export function InviteSheet({ circleId, call, onClose, onCreated }: { circleId: string; call: string; onClose: () => void; onCreated: () => void }) {
  const t = useTranslations("invites");
  const common = useTranslations();
  const toast = useToast();
  const dialog = useDialog(onClose);
  const [link, setLink] = useState("");
  const [error, setError] = useState<InviteFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const create = async () => {
    if (busy) return;
    setBusy(true); setError(null);
    try {
      const result = await createInvite(circleId);
      if ("token" in result) { setLink(inviteLink(result.token)); onCreated(); }
      else setError(result.failure);
    } catch { setError("error"); }
    setBusy(false);
  };
  const started = useRef(false);  // dev double-mount must not create two links
  useEffect(() => { if (started.current) return; started.current = true; void create(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, []);
  const message = t("shareText", { call, link });
  const copy = async () => {
    try { await navigator.clipboard.writeText(link); toast(t("copied")); }
    catch { window.prompt(common("common.copyPrompt"), link); }
  };
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <section className="sheet" ref={dialog} tabIndex={-1} role="dialog" aria-modal="true" aria-label={t("sheetTitle")} onClick={(event) => event.stopPropagation()}>
        <h3>{t("sheetTitle")}</h3>
        {busy && !link && <p className="sheet-empty" role="status">{t("creating")}</p>}
        {error && <p role="alert" className="form-error">{t(error === "circle_full" ? "errFull" : error === "too_many_invites" ? "errTooMany" : "errGeneric")}</p>}
        {link && (
          <>
            <p className="sheet-empty">{t("sheetBody", { call })}</p>
            <input className="invite-link" readOnly value={link} aria-label={t("linkLabel")} onFocus={(event) => event.currentTarget.select()} />
            <div className="stack">
              {typeof navigator.share === "function" && <button className="primary-button" onClick={() => void shareContent(t("sheetTitle"), message, common)}><Icon name="share" size={19} /> {t("share")}</button>}
              <a className={typeof navigator.share === "function" ? "secondary-button invite-wa" : "primary-button invite-wa"} href={`https://wa.me/?text=${encodeURIComponent(message)}`} target="_blank" rel="noopener noreferrer"><Icon name="send" size={19} /> {t("whatsapp")}</a>
              <button className="secondary-button" onClick={() => void copy()}>{t("copy")}</button>
            </div>
            <p className="sheet-hint invite-note">{t("sheetNote")}</p>
          </>
        )}
        {error && <button className="secondary-button" onClick={() => void create()} disabled={busy}>{t("retry")}</button>}
        <button className="text-button" onClick={onClose}>{t("close")}</button>
      </section>
    </div>
  );
}

/** Invites that were created but not used yet. Any member can cancel one. */
export function PendingInvites({ circleId, version, onChanged }: { circleId: string; version: number; onChanged: () => void }) {
  const t = useTranslations("invites");
  const language = useLocale() as Language;
  const toast = useToast();
  const [items, setItems] = useState<PendingInvite[]>([]);
  const [busyId, setBusyId] = useState<string | null>(null);
  useEffect(() => { void loadPendingInvites(circleId).then(setItems).catch(() => {}); }, [circleId, version]);
  if (items.length === 0) return null;
  const revoke = async (id: string) => {
    setBusyId(id);
    const ok = await revokeInvite(id);
    setBusyId(null);
    toast(t(ok ? "revoked" : "errGeneric"));
    onChanged();
  };
  return (
    <section className="pending-invites" aria-label={t("pendingTitle")}>
      <h2>{t("pendingTitle")}</h2>
      <ul>
        {items.map((i) => (
          <li key={i.id}>
            <div>
              <strong>{t("pendingItem", { by: i.by ?? t("someone") })}</strong>
              <small>{t("expires", { date: formatDate(new Date(i.expiresAt), language, { day: "numeric", month: "short" }) })}</small>
            </div>
            <button onClick={() => void revoke(i.id)} disabled={busyId === i.id} aria-label={t("revokeLabel")}>{t("revoke")}</button>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** What the invited person sees after opening the link and logging in. */
export function JoinScreen({ token, onDone }: { token: string; onDone: (recipientId?: string) => void }) {
  const t = useTranslations("invites");
  const toast = useToast();
  const [preview, setPreview] = useState<InvitePreview | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [refused, setRefused] = useState<InviteFailure | null>(null);
  const load = () => {
    setLoadFailed(false); setPreview(null);
    previewInvite(token).then(setPreview).catch(() => setLoadFailed(true));
  };
  useEffect(load, [token]);  // eslint-disable-line react-hooks/exhaustive-deps
  const finish = (recipientId?: string) => { clearPendingInvite(); onDone(recipientId); };
  const accept = async () => {
    if (busy) return;
    setBusy(true);
    const result = await acceptInvite(token);
    setBusy(false);
    if ("recipientId" in result) { toast(t("joined")); return finish(result.recipientId); }
    if (result.failure === "error") return toast(t("errGeneric"));
    setRefused(result.failure);
  };
  const problem = refused ?? (preview && preview.status !== "ok" ? preview.status : null);
  return (
    <main className="onboarding join-screen">
      <div className="otp-icon"><Icon name="users" size={30} /></div>
      {!preview && !loadFailed && <p className="form-note" role="status">{t("checking")}</p>}
      {loadFailed && (
        <>
          <h2>{t("loadFailedTitle")}</h2>
          <p role="alert" className="form-error">{t("errGeneric")}</p>
          <button className="primary-button" onClick={load}>{t("retry")}</button>
          <button className="text-button" onClick={() => finish()}>{t("notNow")}</button>
        </>
      )}
      {problem && (
        <>
          <h2>{t(`problem_${problem}Title`)}</h2>
          <p>{t(`problem_${problem}`)}</p>
          <button className="primary-button" onClick={() => finish()}>{t("continue")}</button>
        </>
      )}
      {!problem && preview?.status === "ok" && (
        <>
          <h2>{t("joinTitle", { inviter: preview.inviter ?? t("someone"), call: preview.callName || preview.parentFirstName || t("yourParent") })}</h2>
          <p>{t("joinBody")}</p>
          <button className="primary-button" onClick={() => void accept()} disabled={busy}>{busy ? t("joining") : t("accept")}</button>
          <button className="text-button" onClick={() => finish()} disabled={busy}>{t("decline")}</button>
        </>
      )}
    </main>
  );
}
