import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "use-intl";
import { AppHeader, Icon, parentCall, useToast } from "../App";
import type { Medicine, Recipient } from "../lib/api";
import {
  DEFAULT_POLICY, PRESETS, handleEscalation, loadOpenEscalations, loadPolicy, presetOf, savePolicy, setMedicineCritical, useFeatures,
  type Escalation, type Policy, type PresetKey,
} from "../lib/escalation";
import { formatClock, type Language } from "../lib/format";
import "./safetynet.css";

/** The Safety net: what happens when a parent does not answer. Nudge first, tell the family only when it matters. */
export function SafetyNetScreen({ go, recipient, medicines, onMedicinesChanged }: {
  go: (screen: "family") => void; recipient: Recipient | null; medicines: Medicine[]; onMedicinesChanged: () => void;
}) {
  const t = useTranslations("safety");
  const language = useLocale() as Language;
  const toast = useToast();
  const [policy, setPolicy] = useState<Policy | null>(null);
  const [error, setError] = useState(false);
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);
  const call = parentCall(recipient, language);
  const recipientId = recipient?.id;

  useEffect(() => {
    if (!recipientId) return;
    let cancelled = false;
    setPolicy(null);
    loadPolicy(recipientId).then((p) => { if (!cancelled) { setPolicy(p); setError(false); } }).catch(() => { if (!cancelled) setError(true); });
    return () => { cancelled = true; };
  }, [recipientId]);

  const change = (patch: Partial<Policy>) => { setPolicy((p) => ({ ...(p ?? DEFAULT_POLICY), ...patch })); setDirty(true); };
  const save = async () => {
    if (!recipientId || !policy) return;
    setBusy(true);
    const ok = await savePolicy(recipientId, policy);
    setBusy(false);
    if (!ok) return toast(t("errSave"));
    setDirty(false);
    toast(t("saved"));
  };
  const toggleCritical = async (m: Medicine) => {
    const ok = await setMedicineCritical(m.id, !m.critical);
    if (!ok) return toast(t("errSave"));
    onMedicinesChanged();
  };

  const preset = policy ? presetOf(policy) : null;
  const active = medicines.filter((m) => m.active);
  return (
    <main className="scroll-page no-nav safety-page">
      <AppHeader title={t("title")} subtitle={t("subtitle", { call })} back={() => go("family")} />
      {error && <p role="alert" className="form-error">{t("loadFailed")}</p>}
      {!policy && !error && <div className="skeleton card" aria-busy="true" />}
      {policy && (
        <>
          <p className="safety-intro">{t("intro", { call })}</p>
          <label className="toggle-row">
            <span><strong>{t("toggleLabel", { call })}</strong><small>{policy.enabled ? t("toggleOn") : t("toggleOff")}</small></span>
            <input type="checkbox" role="switch" checked={policy.enabled} onChange={(event) => change({ enabled: event.target.checked })} /><i className={policy.enabled ? "" : "off"} />
          </label>

          <section className="safety-section" aria-labelledby="safety-speed">
            <h2 id="safety-speed">{t("speedTitle")}</h2>
            <div className="choice-chips wrap" role="group" aria-label={t("speedTitle")}>
              {(Object.keys(PRESETS) as PresetKey[]).map((k) => (
                <button key={k} type="button" className={preset === k ? "selected" : ""} aria-pressed={preset === k} onClick={() => change({ ...PRESETS[k] })}>{t(`preset.${k}`)}</button>
              ))}
            </div>
            <p className="safety-note">{t("speedLine", { a: policy.doseNudgeMin, b: policy.doseAlertMin, c: policy.checkinNudgeMin, d: policy.checkinAlertMin })}</p>
          </section>

          <section className="safety-section" aria-labelledby="safety-alert">
            <h2 id="safety-alert">{t("alertTitle")}</h2>
            <div className="choice-chips wrap" role="group" aria-label={t("alertTitle")}>
              {[1, 2, 3].map((n) => (
                <button key={n} type="button" className={policy.alertAfterUnanswered === n ? "selected" : ""} aria-pressed={policy.alertAfterUnanswered === n} onClick={() => change({ alertAfterUnanswered: n })}>
                  {t(`alertAfter.${n}`)}
                </button>
              ))}
            </div>
            <p className="safety-note">{t("alertHint")}</p>
          </section>

          <section className="safety-section" aria-labelledby="safety-critical">
            <h2 id="safety-critical">{t("criticalTitle")}</h2>
            <p className="safety-note">{t("criticalHint")}</p>
            {active.length === 0 && <p className="safety-note">{t("criticalEmpty")}</p>}
            {active.map((m) => (
              <label key={m.id} className="toggle-row">
                <span><strong>{m.name}</strong>{m.critical && <small>{t("criticalOn")}</small>}</span>
                <input type="checkbox" role="switch" checked={m.critical} onChange={() => void toggleCritical(m)} /><i className={m.critical ? "" : "off"} />
              </label>
            ))}
          </section>

          <section className="safety-section" aria-labelledby="safety-silent">
            <h2 id="safety-silent">{t("silentTitle", { call })}</h2>
            <div className="choice-chips wrap" role="group" aria-label={t("silentTitle", { call })}>
              {[2, 3, 5, 7].map((n) => (
                <button key={n} type="button" className={policy.silentStopDays === n ? "selected" : ""} aria-pressed={policy.silentStopDays === n} onClick={() => change({ silentStopDays: n })}>{t("days", { count: n })}</button>
              ))}
            </div>
            <p className="safety-note">{t("silentHint", { days: policy.silentStopDays })}</p>
          </section>

          <aside className="safety-warning" role="note">
            <strong>{t("emergencyTitle")}</strong>
            <p>{t("emergencyBody", { call })}</p>
          </aside>

          <div className="stack">
            <button className="primary-button" onClick={() => void save()} disabled={busy || !dirty}>{busy ? t("saving") : t("save")}</button>
          </div>
        </>
      )}
    </main>
  );
}

/** On Today: what has already been done about an unanswered reminder, with a button for the family to say "I've handled it". */
export function EscalationProgress({ recipient, call }: { recipient: Recipient; call: string }) {
  const t = useTranslations("safety");
  const language = useLocale() as Language;
  const toast = useToast();
  const features = useFeatures();
  const [items, setItems] = useState<Escalation[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const recipientId = recipient.id;

  const load = () => { void loadOpenEscalations(recipientId).then(setItems).catch(() => { /* keep what we had */ }); };
  useEffect(() => {
    if (!features.escalation) return;
    load();
    const id = window.setInterval(load, 30000);
    const onReturn = () => { if (document.visibilityState === "visible") load(); };
    document.addEventListener("visibilitychange", onReturn);
    return () => { window.clearInterval(id); document.removeEventListener("visibilitychange", onReturn); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recipientId, features.escalation]);

  // only prompts where something has actually happened (a nudge or a decision)
  const shown = items.filter((e) => e.nudgedAt || e.decidedAt);
  if (!features.escalation || shown.length === 0) return null;
  const time = (iso: string) => formatClock(new Date(iso), recipient.timezone, language);
  const handle = async (e: Escalation) => {
    setBusy(e.id);
    const ok = await handleEscalation(e.id);
    setBusy(null);
    toast(t(ok ? "handledToast" : "handledAlready"));
    load();
  };
  return (
    <section className="escalation-card" aria-label={t("progressTitle", { call })}>
      <strong>{t("progressTitle", { call })}</strong>
      {shown.map((e) => (
        <div key={e.id} className="escalation-row">
          <ul>
            <li>{t(e.kind === "dose" ? "sentDose" : "sentCheckin", { time: time(e.baseAt) })}</li>
            {e.nudgedAt && <li>{t("nudged", { call, time: time(e.nudgedAt) })}</li>}
            {e.decidedAt && (e.alertedAt ? <li className="alerted">{t("alerted", { time: time(e.alertedAt) })}</li> : <li>{t("notAlerted")}</li>)}
          </ul>
          <button type="button" className="secondary-button" disabled={busy === e.id} onClick={() => void handle(e)}><Icon name="check" size={16} /> {t("handled")}</button>
        </div>
      ))}
    </section>
  );
}

/** The Family tab entry, shown only when the server has the ladder switched on. */
export function SafetyNetLink({ go, hidden = false }: { go: (screen: "safety") => void; hidden?: boolean }) {
  const t = useTranslations("safety");
  const features = useFeatures();
  if (!features.escalation || hidden) return null;
  return (
    <button className="feature-link" onClick={() => go("safety")}>
      <span style={{ background: "#e3f3ef", color: "#247c72" }}><Icon name="shield" /></span>
      <div><strong>{t("linkTitle")}</strong><p>{t("linkSub")}</p></div>
      <Icon name="chevron" />
    </button>
  );
}
