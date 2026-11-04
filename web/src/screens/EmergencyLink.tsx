import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "use-intl";
import { emergencyLinkUrl, enableEmergencyLink, loadEmergencyLink, revokeEmergencyLink, rotateEmergencyLink, type EmergencyLink as Link } from "../lib/emergency";
import { QrCode } from "./QrCode";

type Props = { recipientId: string; name: string; share: (title: string, text: string) => unknown; toast: (message: string) => void };

/** Caregiver controls for the login-free emergency link: create (idempotent), share, QR, turn off, make a new one. */
export function EmergencyLinkPanel({ recipientId, name, share, toast }: Props) {
  const t = useTranslations("emergencyLink");
  const locale = useLocale();
  const [link, setLink] = useState<Link | null | undefined>(undefined);  // undefined = loading, null = off
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState<"off" | "new" | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let alive = true;
    setLink(undefined); setConfirm(null);
    loadEmergencyLink(recipientId).then((l) => { if (alive) setLink(l); }).catch(() => { if (alive) { setLink(null); setFailed(true); } });
    return () => { alive = false; };
  }, [recipientId]);

  const run = async (action: () => Promise<Link | null>) => {
    if (busy) return;
    setBusy(true); setFailed(false);
    try { setLink(await action()); setConfirm(null); } catch { setFailed(true); } finally { setBusy(false); }
  };

  const url = link ? emergencyLinkUrl(link.token) : "";
  const copy = async () => {
    try { await navigator.clipboard.writeText(url); toast(t("copied")); } catch { window.prompt(t("copy"), url); }
  };
  const when = link?.lastViewedAt ? new Date(link.lastViewedAt).toLocaleString(locale === "hi" ? "hi-IN" : "en-IN", { day: "numeric", month: "short", hour: "numeric", minute: "2-digit" }) : null;

  return (
    <section className="card emergency-link" aria-labelledby="emergency-link-title" style={{ marginTop: 16, padding: 16 }}>
      <h2 id="emergency-link-title" style={{ margin: 0, fontSize: 17 }}>{t("title")}</h2>
      <p style={{ margin: "6px 0 10px", fontSize: 14, lineHeight: 1.45 }}>{t("intro")}</p>
      {link === undefined && <p role="status">{t("loading")}</p>}
      {link === null && (
        <>
          <p style={{ fontWeight: 700 }}>{t("off")}</p>
          <button className="primary-button" disabled={busy} onClick={() => void run(() => enableEmergencyLink(recipientId))}>{t("turnOn")}</button>
        </>
      )}
      {link && (
        <>
          <p style={{ fontWeight: 700, margin: "0 0 4px" }}><span aria-hidden="true">● </span>{t("on")}</p>
          <p style={{ margin: "0 0 12px", fontSize: 13 }}>{when ? `${t("lastViewed", { when })} · ${t("views", { count: link.viewCount })}` : t("neverViewed")}</p>
          <div className="emergency-actions">
            <button className="primary-button" onClick={() => void share(t("shareTitle", { name }), t("shareText", { name, url }))}>{t("share")}</button>
            <button className="secondary-button" onClick={() => void copy()}>{t("copy")}</button>
          </div>
          <div style={{ textAlign: "center", margin: "14px 0" }}>
            <QrCode value={url} label={t("qrLabel")} size={184} />
            <p style={{ margin: "6px 0 0", fontSize: 13 }}><strong>{t("qrTitle")}</strong> · {t("qrHint")}</p>
          </div>
          {confirm === null ? (
            <div className="emergency-actions">
              <button className="secondary-button" disabled={busy} onClick={() => setConfirm("new")}>{t("newLink")}</button>
              <button className="secondary-button" disabled={busy} onClick={() => setConfirm("off")}>{t("turnOff")}</button>
            </div>
          ) : (
            <div role="alertdialog" aria-label={t(confirm === "off" ? "turnOff" : "newLink")} style={{ background: "var(--s-fff7f6)", border: "1px solid var(--b-f0b8b4)", borderRadius: 14, padding: 12 }}>
              <p style={{ margin: "0 0 10px", fontWeight: 600 }}>{t(confirm === "off" ? "confirmOff" : "confirmNew")}</p>
              <div className="emergency-actions">
                <button className="primary-button" disabled={busy} onClick={() => void run(confirm === "off" ? async () => { await revokeEmergencyLink(recipientId); return null; } : () => rotateEmergencyLink(recipientId))}>{t(confirm === "off" ? "yesOff" : "yesNew")}</button>
                <button className="secondary-button" disabled={busy} onClick={() => setConfirm(null)}>{t("cancel")}</button>
              </div>
            </div>
          )}
        </>
      )}
      {failed && <p role="alert" className="form-error">{t("failed")}</p>}
      <p className="privacy-note" style={{ marginTop: 12 }}>{t("privacy")}</p>
    </section>
  );
}
