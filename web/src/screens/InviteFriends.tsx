import { useEffect, useState, type FormEvent } from "react";
import { useLocale, useTranslations } from "use-intl";
import { AppHeader, Icon, useToast } from "../App";
import { changeReferralCode, checkReferralCode, loadMyReferral, type CodeCheck, type MyReferral } from "../lib/referral";
import "./inviteFriends.css";

/**
 * "Invite friends" (Family > More, migration 0061): the person's own referral code (made from the first name the first time),
 * the link to share, sharing on the person's OWN WhatsApp (wa.me, never Chhaon's sending number), and how many families joined.
 * They can choose another code; a taken one gets 3 free suggestions. Old codes keep counting (docs/referral-codes.md).
 */
export function InviteFriendsScreen({ go }: { go: (screen: "family") => void }) {
  const t = useTranslations("invite");
  const locale = useLocale();
  const toast = useToast();
  const [mine, setMine] = useState<MyReferral | "error" | null>(null);
  const [editing, setEditing] = useState(false);
  const [wanted, setWanted] = useState("");
  const [check, setCheck] = useState<CodeCheck | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    loadMyReferral().then((m) => { if (!cancelled) setMine(m); }).catch(() => { if (!cancelled) setMine("error"); });
    return () => { cancelled = true; };
  }, []);

  const number = (n: number) => n.toLocaleString(locale === "hi" ? "hi-IN" : "en-IN");
  const ready = mine && mine !== "error" && mine.installed && mine.code && mine.link ? mine : null;
  const shareText = ready ? t("shareMessage", { link: ready.link as string }) : "";

  const copy = async () => {
    if (!ready) return;
    try { await navigator.clipboard.writeText(ready.link as string); toast(t("copied")); } catch { toast(t("copyFailed")); }
  };

  const tryCode = async (code: string) => {
    const clean = code.trim().toUpperCase();
    if (!clean) return;
    setBusy(true);
    setCheck(null);
    try {
      const result = await changeReferralCode(clean);
      if (result.ok) {
        setMine(result.mine);
        setEditing(false);
        setWanted("");
        toast(t("changed", { code: result.mine.code ?? clean }));
      } else {
        setCheck(result.check);
      }
    } catch {
      toast(t("failed"));
    } finally {
      setBusy(false);
    }
  };

  const submit = (event: FormEvent) => { event.preventDefault(); void tryCode(wanted); };

  // A quick availability hint while typing (no saving), after a short pause.
  useEffect(() => {
    if (!editing) return;
    const clean = wanted.trim().toUpperCase();
    if (clean.length < 4) return;
    const id = window.setTimeout(() => { checkReferralCode(clean).then(setCheck).catch(() => {}); }, 450);
    return () => window.clearTimeout(id);
  }, [wanted, editing]);

  const why = (c: CodeCheck) => t(`status.${c.status}`, { code: c.code });
  // Only the answer for what is typed now (an older answer for other letters is not shown).
  const shown = check && check.code === wanted.trim().toUpperCase() ? check : null;

  return (
    <main className="scroll-page no-nav invite-page">
      <AppHeader title={t("title")} subtitle={t("subtitle")} back={() => go("family")} />
      {mine === null && <p className="note" role="status">{t("loading")}</p>}
      {(mine === "error" || (mine !== null && !mine.installed)) && <p className="note" role="alert">{t("unavailable")}</p>}
      {ready && (
        <>
          <section className="invite-card">
            <span className="invite-label">{t("yourCode")}</span>
            <strong className="invite-code">{ready.code}</strong>
            <span className="ref-link">{(ready.link as string).replace(/^https:\/\//, "")}</span>
            <div className="invite-actions">
              <a className="primary-button invite-wa" href={`https://wa.me/?text=${encodeURIComponent(shareText)}`} target="_blank" rel="noopener noreferrer">
                <Icon name="send" size={18} /> {t("shareWhatsapp")}
              </a>
              <button type="button" className="secondary-button" onClick={() => void copy()}>{t("copyLink")}</button>
            </div>
            <p className="invite-count">{t("families", { count: ready.families, n: number(ready.families) })}</p>
          </section>

          {!editing ? (
            <button type="button" className="text-button invite-change" onClick={() => { setEditing(true); setCheck(null); }}
              disabled={ready.changesLeft === 0}>
              {ready.changesLeft === 0 ? t("noChangesLeft") : t("change")}
            </button>
          ) : (
            <form className="invite-edit" onSubmit={submit} noValidate>
              <label htmlFor="invite-code" className="field-label">{t("chooseLabel")}</label>
              <input id="invite-code" value={wanted} maxLength={15} autoCapitalize="characters" autoComplete="off" spellCheck={false}
                placeholder={t("choosePlaceholder")} onChange={(e) => setWanted(e.target.value.replace(/[^A-Za-z0-9]/g, "").toUpperCase())}
                aria-describedby="invite-code-hint" />
              <p id="invite-code-hint" className="hint">{t("chooseHint", { left: ready.changesLeft })}</p>
              {shown && (
                <div className={`invite-check ${shown.status === "available" || shown.status === "yours" ? "ok" : "bad"}`} role="status">
                  <p>{why(shown)}</p>
                  {shown.suggestions.length > 0 && (
                    <>
                      <p className="invite-suggest-title">{t("suggestTitle")}</p>
                      <div className="choice-chips wrap">
                        {shown.suggestions.map((s) => (
                          <button type="button" key={s} disabled={busy} onClick={() => { setWanted(s); void tryCode(s); }}>{s}</button>
                        ))}
                      </div>
                    </>
                  )}
                </div>
              )}
              <div className="stack">
                <button type="submit" className="primary-button" disabled={busy || wanted.trim().length < 4}>{t("save")}</button>
                <button type="button" className="secondary-button" onClick={() => { setEditing(false); setWanted(""); setCheck(null); }}>{t("cancel")}</button>
              </div>
            </form>
          )}
          <p className="note invite-note">{t("note")}</p>
        </>
      )}
    </main>
  );
}
