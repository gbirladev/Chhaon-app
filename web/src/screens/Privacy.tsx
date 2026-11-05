import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "use-intl";
import { AppHeader, Icon, useDialog, useToast } from "../App";
import { LEGAL } from "../content/legal";
import type { CircleMember, Recipient } from "../lib/api";
import type { Language } from "../lib/format";
import { acceptPrivacy, deleteMyAccount, deleteParentData, downloadMyData, loadPrivacyState } from "../lib/privacy";
import "./privacy.css";
import { Icon3D } from "./Icon3D";

type Go = (screen: "policy" | "terms" | "privacyData" | "welcome" | "family" | "goodbye", options?: { replace?: boolean }) => void;

/** Back goes to where the reader came from; if the page was opened directly, to a sensible home. */
const backTo = (go: Go, fallback: "welcome" | "family") => () => (window.history.length > 1 ? window.history.back() : go(fallback, { replace: true }));

/** Privacy policy or Terms: plain text from content/legal.ts, in the app's language. Works signed out. */
/** Whichever of Privacy / Terms is opened first leads to the other; only the second (final) page has "I agree". */
let nextTarget: "policy" | "terms" | null = null;

export function LegalPage({ kind, go, signedIn }: { kind: "policy" | "terms"; go: Go; signedIn: boolean }) {
  const t = useTranslations("privacy");
  const [final] = useState(() => {
    const isFinal = nextTarget === kind;
    if (isFinal) setTimeout(() => { nextTarget = null; }, 0);
    return isFinal;
  });
  const language = useLocale() as Language;
  const doc = LEGAL[kind === "policy" ? "privacy" : "terms"][language];
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const agree = async () => {
    if (signedIn) {
      setBusy(true);
      const ok = await acceptPrivacy();
      setBusy(false);
      if (!ok) return toast(t("consentFailed"));
      window.dispatchEvent(new Event("carelink-privacy-accepted"));  // the first-login sheet closes itself
    }
    toast(t("agreed"));
    backTo(go, signedIn ? "family" : "welcome")();
  };
  return (
    <main className="scroll-page no-nav legal-page">
      <AppHeader title={doc.title} subtitle={doc.updated} back={backTo(go, signedIn ? "family" : "welcome")} />
      {doc.draft && <p className="draft-banner" role="note">{doc.draft}</p>}
      {doc.sections.map((section) => (
        <section key={section.h} className="legal-section">
          <h2>{section.h}</h2>
          {section.p?.map((text) => <p key={text}>{text}</p>)}
          {section.ul && <ul>{section.ul.map((text) => <li key={text}>{text}</li>)}</ul>}
        </section>
      ))}
      {final ? (
        <button className="primary-button agree-button" onClick={agree} disabled={busy}>{busy ? t("working") : t("agree")}</button>
      ) : (
        <button className="primary-button agree-button" onClick={() => { nextTarget = kind === "policy" ? "terms" : "policy"; go(nextTarget, { replace: true }); }}>{t(kind === "policy" ? "next" : "nextPolicy")}</button>
      )}
    </main>
  );
}

/** One line under the login button: agreeing is by continuing, and both documents are one tap away. */
export function LegalLinks({ go }: { go: Go }) {
  const t = useTranslations("privacy");
  return (
    <p className="legal-line">
      {t("loginNote")}{" "}
      <button type="button" className="inline-link" onClick={() => go("terms")}>{t("terms")}</button>{" "}{t("and")}{" "}
      <button type="button" className="inline-link" onClick={() => go("policy")}>{t("policy")}</button>.
    </p>
  );
}

/** A confirm sheet for something that cannot be undone: the person must type DELETE. */
function DangerSheet({ title, body, action, busy, error, onConfirm, onCancel }: { title: string; body: string; action: string; busy: boolean; error: boolean; onConfirm: () => void; onCancel: () => void }) {
  const t = useTranslations("privacy");
  const dialog = useDialog(busy ? () => {} : onCancel);
  const [typed, setTyped] = useState("");
  const ready = typed.trim().toUpperCase() === "DELETE";
  return (
    <div className="sheet-backdrop" onClick={busy ? undefined : onCancel}>
      <section className="sheet" ref={dialog} tabIndex={-1} role="alertdialog" aria-modal="true" aria-label={title} onClick={(event) => event.stopPropagation()}>
        <h3>{title}</h3>
        <p className="sheet-empty">{body}</p>
        <label htmlFor="confirm-word" className="confirm-label">{t("typeDelete")}</label>
        <input id="confirm-word" className="confirm-input" value={typed} onChange={(event) => setTyped(event.target.value)} autoComplete="off" autoCapitalize="characters" spellCheck={false} />
        {error && <p role="alert" className="form-error">{t("deleteFailed")}</p>}
        <div className="stack">
          <button className="danger-button" onClick={onConfirm} disabled={!ready || busy}>{busy ? t("working") : action}</button>
          <button className="secondary-button" onClick={onCancel} disabled={busy}>{t("cancel")}</button>
        </div>
      </section>
    </div>
  );
}

export function PrivacyData({ go, recipient, members, userId, onParentDeleted, onAccountDeleted }: { go: Go; recipient: Recipient | null; members: CircleMember[]; userId: string | null; onParentDeleted: () => Promise<void>; onAccountDeleted: () => Promise<void> }) {
  const t = useTranslations("privacy");
  const toast = useToast();
  const [exporting, setExporting] = useState(false);
  const [sheet, setSheet] = useState<null | "parent" | "account">(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const isOwner = !!recipient && members.some((m) => m.userId === userId && m.role === "owner");
  const call = recipient?.call_name?.trim() || recipient?.name || "";

  const exportData = async () => {
    setExporting(true);
    const ok = await downloadMyData();
    setExporting(false);
    toast(t(ok ? "exported" : "exportFailed"));
  };
  const confirm = async () => {
    setBusy(true);
    setFailed(false);
    const ok = sheet === "account" ? await deleteMyAccount() : recipient ? await deleteParentData(recipient.id) : false;
    if (!ok) { setBusy(false); setFailed(true); return; }
    setSheet(null);
    setBusy(false);
    if (sheet === "account") await onAccountDeleted();
    else { toast(t("parentDeleted")); await onParentDeleted(); }
  };
  return (
    <main className="scroll-page no-nav">
      <AppHeader title={t("title")} subtitle={t("subtitle")} back={backTo(go, "family")} />
      <button className="feature-link" onClick={() => go("policy")}><span className="privacy-icon"><Icon name="shield" /></span><div><strong>{t("policy")}</strong><p>{t("policySub")}</p></div><Icon name="chevron" /></button>
      <button className="feature-link" onClick={() => go("terms")}><span className="privacy-icon"><Icon name="report" /></span><div><strong>{t("terms")}</strong><p>{t("termsSub")}</p></div><Icon name="chevron" /></button>

      <section className="form-card privacy-card">
        <h2>{t("yourData")}</h2>
        <p>{t("yourDataBody")}</p>
        <button className="secondary-button" onClick={exportData} disabled={exporting}>{exporting ? t("working") : t("download")}</button>
      </section>

      {recipient && (
        <section className="form-card privacy-card">
          <h2>{t("parentTitle", { call })}</h2>
          <p>{t(isOwner ? "parentBody" : "parentOwnerOnly", { call })}</p>
          {isOwner && <button className="danger-button" onClick={() => { setFailed(false); setSheet("parent"); }}>{t("parentAction", { call })}</button>}
        </section>
      )}

      <section className="form-card privacy-card">
        <h2>{t("accountTitle")}</h2>
        <p>{t("accountBody")}</p>
        <button className="danger-button" onClick={() => { setFailed(false); setSheet("account"); }}>{t("accountAction")}</button>
      </section>
      <p className="form-note">{t("whatsappNote")}</p>

      {sheet === "account" && <DangerSheet title={t("accountConfirmTitle")} body={t("accountConfirmBody")} action={t("accountAction")} busy={busy} error={failed} onConfirm={confirm} onCancel={() => setSheet(null)} />}
      {sheet === "parent" && <DangerSheet title={t("parentConfirmTitle", { call })} body={t("parentConfirmBody", { call })} action={t("parentAction", { call })} busy={busy} error={failed} onConfirm={confirm} onCancel={() => setSheet(null)} />}
    </main>
  );
}

export function Goodbye({ go }: { go: Go }) {
  const t = useTranslations("privacy");
  return (
    <main className="onboarding">
      <div className="empty-state goodbye"><Icon3D name="heart" size={64} className="empty-3d" eager /><strong>{t("goodbyeTitle")}</strong><p>{t("goodbyeBody")}</p></div>
      <button className="primary-button" onClick={() => go("welcome", { replace: true })}>{t("goodbyeAction")}</button>
    </main>
  );
}

/** Asks a signed-in caregiver to agree to the current policy, once. Existing users can say "Not now" (asked again next open);
 *  a brand-new account cannot skip, but can log out. Hidden while the policy itself is open. */
export function ConsentGate({ signedIn, screen, go, onSignOut }: { signedIn: boolean; screen: string; go: Go; onSignOut: () => void }) {
  const [state, setState] = useState<{ accepted: boolean; isNew: boolean } | null>(null);
  const [snoozed, setSnoozed] = useState(false);
  useEffect(() => {
    if (!signedIn) { setState(null); return; }
    let live = true;
    void loadPrivacyState().then((s) => { if (live) setState(s); });
    const done = () => setState((s) => (s ? { ...s, accepted: true } : s));
    window.addEventListener("carelink-privacy-accepted", done);
    return () => { live = false; window.removeEventListener("carelink-privacy-accepted", done); };
  }, [signedIn]);
  if (!signedIn || !state || state.accepted || snoozed || screen === "policy" || screen === "terms" || screen === "goodbye") return null;
  return <ConsentSheet required={state.isNew} go={go} onAccepted={() => setState({ ...state, accepted: true })} onLater={() => setSnoozed(true)} onSignOut={onSignOut} />;
}

function ConsentSheet({ required, go, onAccepted, onLater, onSignOut }: { required: boolean; go: Go; onAccepted: () => void; onLater: () => void; onSignOut: () => void }) {
  const t = useTranslations("privacy");
  const dialog = useDialog(required ? () => {} : onLater);
  const [checked, setChecked] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(false);
  const accept = async () => {
    setBusy(true);
    setError(false);
    const ok = await acceptPrivacy();
    setBusy(false);
    if (ok) onAccepted(); else setError(true);
  };
  return (
    <div className="sheet-backdrop">
      <section className="sheet" ref={dialog} tabIndex={-1} role="dialog" aria-modal="true" aria-label={t("consentTitle")}>
        <h3>{t("consentTitle")}</h3>
        <p className="sheet-empty">{t("consentBody")}</p>
        <label className="consent-check">
          <input type="checkbox" checked={checked} onChange={(event) => setChecked(event.target.checked)} />
          <span>
            {t("consentCheck")}{" "}
            <button type="button" className="inline-link" onClick={() => go("policy")}>{t("policy")}</button>{" "}{t("and")}{" "}
            <button type="button" className="inline-link" onClick={() => go("terms")}>{t("terms")}</button>.
          </span>
        </label>
        {error && <p role="alert" className="form-error">{t("consentFailed")}</p>}
        <div className="stack">
          <button className="primary-button" onClick={accept} disabled={!checked || busy}>{busy ? t("working") : t("consentAction")}</button>
          {required ? <button className="secondary-button" onClick={onSignOut}>{t("consentLogout")}</button> : <button className="secondary-button" onClick={onLater}>{t("notNow")}</button>}
        </div>
      </section>
    </div>
  );
}

/** A calm notice while the phone has no connection. Nothing is queued: actions simply say they could not be saved. */
export function OfflineBanner() {
  const t = useTranslations("privacy");
  const [online, setOnline] = useState(() => navigator.onLine);
  useEffect(() => {
    const on = () => setOnline(true);
    const off = () => setOnline(false);
    window.addEventListener("online", on);
    window.addEventListener("offline", off);
    return () => { window.removeEventListener("online", on); window.removeEventListener("offline", off); };
  }, []);
  if (online) return null;
  return <div className="offline-banner" role="status"><strong>{t("offlineTitle")}</strong> {t("offlineBody")}</div>;
}
