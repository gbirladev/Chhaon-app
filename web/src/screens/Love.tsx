import { useCallback, useEffect, useMemo, useState } from "react";
import { useLocale, useTranslations } from "use-intl";
import { AppHeader, Field, Icon, parentCall, useDialog, useToast } from "../App";
import { loadCheckin, loadTodayDoses, type Recipient, type TodayCheckin, type TodayDose } from "../lib/api";
import { useFeatures } from "../lib/escalation";
import { formatClock, type Language } from "../lib/format";
import {
  EMOJIS, MAX_NOTE, NO_LOVE, loadLoveStats, PRESET_KEYS, loadLoveSettings, loadMyCallName, loadParentMessages, loadReactions, myUserId, saveMyCallName, sendReaction, setLoveEnabled,
  type LoveSettings, type LoveStats, type ParentMessage, type Reaction, type SendFailure,
} from "../lib/love";
import "./love.css";

/** One thing the parent did that a caregiver can answer with a little love: a taken dose (or a time slot of them) or an answered check-in. */
type Moment = { key: string; kind: "dose" | "checkin"; at: string; names: string[] };

const FAILURES: Record<SendFailure, string> = { off: "errOff", optedOut: "errOptedOut", tooOld: "errTooOld", already: "errAlready", badNote: "errNote", failed: "errSend" };

function momentsOf(doses: TodayDose[], checkin: TodayCheckin | undefined): Moment[] {
  const slots = new Map<string, Moment>();
  for (const d of doses) {
    if (d.status !== "taken" && d.status !== "taken_late") continue;
    const slot = slots.get(d.scheduledAt);
    if (slot) slot.names.push(d.name);
    else slots.set(d.scheduledAt, { key: d.id, kind: "dose", at: d.scheduledAt, names: [d.name] });
  }
  const list = [...slots.values()];
  if (checkin?.status === "replied") list.push({ key: checkin.id, kind: "checkin", at: checkin.responded_at ?? checkin.sent_at ?? new Date().toISOString(), names: [] });
  return list.sort((a, b) => b.at.localeCompare(a.at));
}

/** What the caregiver sees for one reaction: what was sent and what happened to it, in plain words. */
function useReactionText(call: string) {
  const t = useTranslations("love");
  const what = (r: Reaction) => (r.type === "emoji" ? [r.emoji, r.text].filter(Boolean).join(" ") : r.type === "preset" ? t(`preset.${r.text}`) : r.text ?? "");
  const status = (r: Reaction) => {
    if (r.status === "skipped") return t(r.failReason === "parent_opted_out" ? "status.skippedOptedOut" : r.failReason === "love_off" ? "status.skippedOff" : "status.skippedOther", { call });
    if (r.status === "failed") return t("status.failed");
    if (r.status === "expired") return t("status.expired", { call });
    return t(`status.${r.status}`, { call });
  };
  return { what, status };
}

function Sheet({ call, onSend, onClose }: { call: string; onSend: (patch: { type: "preset" | "custom"; text: string }) => Promise<void>; onClose: () => void }) {
  const t = useTranslations("love");
  const dialog = useDialog(onClose);
  const [preset, setPreset] = useState<string | null>(null);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const text = note.trim();
  const ready = !!preset || text.length > 0;
  const send = async () => {
    if (!ready) return;
    setBusy(true);
    await onSend(text ? { type: "custom", text } : { type: "preset", text: preset as string });
    setBusy(false);
  };
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <section className="sheet love-sheet" ref={dialog} tabIndex={-1} role="dialog" aria-modal="true" aria-label={t("sheetTitle", { call })} onClick={(e) => e.stopPropagation()}>
        <div className="sheet-head"><h3>{t("sheetTitle", { call })}</h3><button className="sheet-x" aria-label={t("close")} onClick={onClose}>×</button></div>
        <div className="choice-chips wrap" role="group" aria-label={t("presetsLabel")}>
          {PRESET_KEYS.map((k) => (
            <button key={k} type="button" className={preset === k && !text ? "selected" : ""} aria-pressed={preset === k && !text} onClick={() => { setPreset(k); setNote(""); }}>{t(`preset.${k}`)}</button>
          ))}
        </div>
        <Field id="love-note" label={t("noteLabel")} hint={t("noteHint", { call })}>
          <textarea id="love-note" rows={3} maxLength={MAX_NOTE} value={note} placeholder={t("notePlaceholder")} onChange={(e) => { setNote(e.target.value); if (e.target.value) setPreset(null); }} aria-describedby="love-count" />
          <p className="love-count" id="love-count" aria-live="polite">{t("count", { n: note.length, max: MAX_NOTE })}</p>
        </Field>
        <button className="primary-button" disabled={!ready || busy} onClick={() => void send()}>{busy ? t("sending") : t("send")}</button>
      </section>
    </div>
  );
}

/** On Today: answer a taken dose or an answered check-in with a heart, and see what happened to it and what the parent wrote back. */
export function LoveCard({ recipient, call }: { recipient: Recipient; call: string }) {
  const t = useTranslations("love");
  const language = useLocale() as Language;
  const toast = useToast();
  const features = useFeatures();
  const [data, setData] = useState<{ id: string; settings: LoveSettings; moments: Moment[]; reactions: Reaction[]; messages: ParentMessage[]; me: string | null } | null>(null);
  const [writing, setWriting] = useState<Moment | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [all, setAll] = useState(false);
  const { what, status } = useReactionText(call);
  const recipientId = recipient.id;
  const consented = !!recipient.consent_at && !recipient.opted_out_at;

  const load = useCallback(async () => {
    try {
      const settings = await loadLoveSettings(recipientId);
      if (!settings.enabled) { setData({ id: recipientId, settings, moments: [], reactions: [], messages: [], me: null }); return; }
      const [doses, checkin, reactions, messages, me] = await Promise.all([loadTodayDoses(recipient), loadCheckin(recipient), loadReactions(recipientId), loadParentMessages(recipientId), myUserId()]);
      setData({ id: recipientId, settings, moments: momentsOf(doses, checkin), reactions, messages, me });
    } catch { /* keep what is on screen */ }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recipientId, recipient.timezone]);

  useEffect(() => {
    if (!features.love || !consented || recipient.is_self) return;
    void load();
    const id = window.setInterval(() => void load(), 30000);
    const onReturn = () => { if (document.visibilityState === "visible") void load(); };
    document.addEventListener("visibilitychange", onReturn);
    return () => { window.clearInterval(id); document.removeEventListener("visibilitychange", onReturn); };
  }, [features.love, consented, load]);

  const mine = data && data.id === recipientId ? data : null;
  const byMoment = useMemo(() => {
    const map = new Map<string, Reaction[]>();
    for (const r of mine?.reactions ?? []) map.set(r.sourceId, [...(map.get(r.sourceId) ?? []), r]);
    return map;
  }, [mine]);
  if (!features.love || !consented || recipient.is_self || !mine || !mine.settings.enabled) return null;

  const time = (iso: string) => formatClock(new Date(iso), recipient.timezone, language);
  const send = async (moment: Moment, patch: { type: "emoji"; emoji: string } | { type: "preset" | "custom"; text: string }) => {
    setBusy(moment.key);
    const result = await sendReaction({ recipientId, sourceType: moment.kind, sourceId: moment.key, ...patch });
    setBusy(null);
    if (!result.ok) { toast(t(FAILURES[result.reason], { call })); if (result.reason === "already") void load(); return false; }
    toast(t("queuedToast", { call }));
    void load();
    return true;
  };
  const shown = all ? mine.moments : mine.moments.slice(0, 3);
  const replyOf = (r: Reaction) => mine.messages.filter((m) => m.reactionId === r.id);
  const optedOut = mine.settings.parentOptedOut;
  if (mine.moments.length === 0 && mine.messages.length === 0 && !optedOut) return null;

  return (
    <section className="love-card" aria-label={t("cardTitle", { call })}>
      <strong>{t("cardTitle", { call })}</strong>
      {optedOut && <p className="love-note" role="note">{t("optedOutNote", { call })}</p>}
      {shown.map((m) => {
        const reactions = byMoment.get(m.key) ?? [];
        const mineAlready = reactions.some((r) => r.senderId === mine.me);
        return (
          <div key={m.key} className="love-moment">
            <p className="love-what">
              <span aria-hidden="true">✓</span>
              {m.kind === "dose" ? t("tookDose", { names: m.names.join(", "), time: time(m.at) }) : t("checkinOkay", { call, time: time(m.at) })}
            </p>
            {reactions.map((r) => (
              <div key={r.id} className={`love-sent ${r.status}`}>
                <p><span className="love-who">{r.senderId === mine.me ? t("you") : t("family")}</span> {what(r)}</p>
                <small>{status(r)}</small>
                {replyOf(r).map((reply) => <p key={reply.id} className="love-reply">{t("replied", { call })} <span>{reply.body}</span></p>)}
              </div>
            ))}
            {!mineAlready && !optedOut && (
              <div className="love-buttons" role="group" aria-label={t("react")}>
                {EMOJIS.map((e) => <button key={e} type="button" className="love-emoji" disabled={busy === m.key} aria-label={t("sendEmoji", { emoji: e })} onClick={() => void send(m, { type: "emoji", emoji: e })}>{e}</button>)}
                <button type="button" className="love-write" disabled={busy === m.key} onClick={() => setWriting(m)}>{t("write")}</button>
              </div>
            )}
          </div>
        );
      })}
      {mine.moments.length > 3 && <button type="button" className="love-more" onClick={() => setAll((v) => !v)}>{t(all ? "less" : "more", { count: mine.moments.length - 3 })}</button>}
      {mine.messages.length > 0 && (
        <div className="love-inbox">
          <small>{t("latestFrom", { call })}</small>
          {mine.messages.map((m) => <p key={m.id}><span>{m.body}</span> <small>{time(m.createdAt)}</small></p>)}
        </div>
      )}
      {writing && <Sheet call={call} onClose={() => setWriting(null)} onSend={async (patch) => { if (await send(writing, patch)) setWriting(null); }} />}
    </section>
  );
}

/** The Family tab entry, shown only when the server has the love loop switched on. */
export function LoveLink({ go, hidden = false }: { go: (screen: "love") => void; hidden?: boolean }) {
  const t = useTranslations("love");
  const features = useFeatures();
  if (!features.love || hidden) return null;
  return (
    <button className="feature-link" onClick={() => go("love")}>
      <span style={{ background: "#fdecea", color: "#b4443a" }} aria-hidden="true">❤️</span>
      <div><strong>{t("linkTitle")}</strong><p>{t("linkSub")}</p></div>
      <Icon name="chevron" />
    </button>
  );
}

/** Settings: switch the love messages on or off for one parent, say what the parent calls you, see what they wrote back. */
export function LoveScreen({ go, recipient }: { go: (screen: "family") => void; recipient: Recipient | null }) {
  const t = useTranslations("love");
  const language = useLocale() as Language;
  const toast = useToast();
  const [settings, setSettings] = useState<LoveSettings | null>(null);
  const [messages, setMessages] = useState<ParentMessage[]>([]);
  const [calledName, setCalledName] = useState("");
  const [savedName, setSavedName] = useState("");
  const [error, setError] = useState(false);
  const [busy, setBusy] = useState(false);
  const call = parentCall(recipient, language);
  const recipientId = recipient?.id;
  const circleId = recipient?.circle_id;

  useEffect(() => {
    if (!recipientId || !circleId) return;
    let cancelled = false;
    setSettings(null);
    Promise.all([loadLoveSettings(recipientId), loadMyCallName(circleId), loadParentMessages(recipientId, 5)])
      .then(([s, name, msgs]) => { if (!cancelled) { setSettings(s); setCalledName(name); setSavedName(name); setMessages(msgs); setError(false); } })
      .catch(() => { if (!cancelled) setError(true); });
    return () => { cancelled = true; };
  }, [recipientId, circleId]);

  const toggle = async (enabled: boolean) => {
    if (!recipientId) return;
    setBusy(true);
    const result = await setLoveEnabled(recipientId, enabled);
    setBusy(false);
    if (!result.ok) return toast(t(result.reason === "unavailable" ? "errUnavailable" : "errSave"));
    setSettings((s) => ({ ...(s ?? NO_LOVE), enabled }));
  };
  const saveName = async () => {
    if (!circleId) return;
    const ok = await saveMyCallName(circleId, calledName);
    if (!ok) return toast(t("errSave"));
    setSavedName(calledName.trim());
    toast(t("nameSaved"));
  };
  const consented = !!recipient?.consent_at && !recipient.opted_out_at;
  const time = (iso: string) => formatClock(new Date(iso), recipient?.timezone ?? "Asia/Kolkata", language);

  return (
    <main className="scroll-page no-nav love-page">
      <AppHeader title={t("title")} subtitle={t("subtitle", { call })} back={() => go("family")} />
      {error && <p role="alert" className="form-error">{t("loadFailed")}</p>}
      {!settings && !error && <div className="skeleton card" aria-busy="true" />}
      {settings && (
        <>
          <p className="love-intro">{t("intro", { call })}</p>
          {settings.control ? <p className="love-note" role="note">{t("controlNote")}</p> : (
            <label className="toggle-row">
              <span><strong>{t("toggleLabel", { call })}</strong><small>{settings.enabled ? t("toggleOn") : t("toggleOff")}</small></span>
              <input type="checkbox" role="switch" checked={settings.enabled} disabled={busy} onChange={(e) => void toggle(e.target.checked)} /><i className={settings.enabled ? "" : "off"} />
            </label>
          )}
          {settings.enabled && !consented && <p className="love-note" role="note">{t("needsConsent", { call })}</p>}
          {settings.parentOptedOut && <p className="love-note" role="note">{t("optedOutNote", { call })}</p>}

          <section className="love-section" aria-labelledby="love-name">
            <h2 id="love-name">{t("nameTitle", { call })}</h2>
            <Field id="love-called" label={t("nameLabel")} hint={t("nameHint")}>
              <input id="love-called" maxLength={40} value={calledName} placeholder={t("namePlaceholder")} onChange={(e) => setCalledName(e.target.value)} />
            </Field>
            <button className="secondary-button" disabled={calledName.trim() === savedName} onClick={() => void saveName()}>{t("saveName")}</button>
          </section>

          <section className="love-section" aria-labelledby="love-rules">
            <h2 id="love-rules">{t("rulesTitle")}</h2>
            <ul className="love-rules">
              <li>{t("rule1", { call })}</li>
              <li>{t("rule2", { call })}</li>
              <li>{t("rule3", { call })}</li>
              <li>{t("rule4", { call })}</li>
            </ul>
          </section>

          {messages.length > 0 && (
            <section className="love-section" aria-labelledby="love-from">
              <h2 id="love-from">{t("latestFrom", { call })}</h2>
              {messages.map((m) => <p key={m.id} className="love-inbox-row"><span>{m.body}</span> <small>{time(m.createdAt)}</small></p>)}
            </section>
          )}
        </>
      )}
    </main>
  );
}

/** The owner's love loop numbers on the Pilot stats page: how many reactions, how many replies, and whether the reminders are confirmed more often. */
export function LoveStatsSection() {
  const t = useTranslations("love");
  const [stats, setStats] = useState<LoveStats | "error" | null>(null);
  useEffect(() => {
    let cancelled = false;
    loadLoveStats().then((s) => { if (!cancelled) setStats(s); }).catch(() => { if (!cancelled) setStats("error"); });
    return () => { cancelled = true; };
  }, []);
  if (!stats || stats === "error") return null;
  const pct = (ok: number, all: number) => (all > 0 ? Math.round((ok / all) * 100) : "-");
  const r = stats.reactions;
  return (
    <section className="love-stats" aria-label={t("statsTitle")}>
      <h2>{t("statsTitle")}</h2>
      <p>{t("statsFamilies", { on: stats.families.loveOn, control: stats.families.control })}</p>
      <p>{t("statsReactions", { created: r.created, sent: r.sent, delivered: r.delivered, read: r.read })}</p>
      <p>{t("statsOther", { queued: r.queued, expired: r.expired, failed: r.failed, skipped: r.skipped, replies: stats.parentReplies })}</p>
      {Object.keys(stats.confirmation).length === 0 && <p>{t("statsNone")}</p>}
      {Object.entries(stats.confirmation).map(([group, c]) => (
        <p key={group}>{t("statsConfirm", { days: stats.days, group, before: pct(c.beforeConfirmed, c.beforeSent), after: pct(c.afterConfirmed, c.afterSent) })}</p>
      ))}
    </section>
  );
}
