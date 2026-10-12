import { useEffect, useState, type ReactNode } from "react";
import { useLocale, useTranslations } from "use-intl";
import { Icon, useDialog } from "../App";
import { loadTodayLayout, saveTodayLayout } from "../lib/api";
import { DoseRoute, type RouteStop } from "./DoseRoute";
import "./layouts.css";

/** Preview of several ways to show Today's medicines, so one can be chosen. Same data for all of them. */
export type DoseLayout = "route" | "curvy" | "timeline" | "arc" | "focus" | "ring" | "pillbox" | "ladder" | "clock" | "chat";
export const LAYOUTS: { id: DoseLayout }[] = ["route", "curvy", "timeline", "arc", "focus", "ring", "pillbox", "ladder", "clock", "chat"].map((id) => ({ id: id as DoseLayout }));
const KEY = "carelink-dose-layout";
const valid = (v: unknown): v is DoseLayout => LAYOUTS.some((l) => l.id === v);

/** The caregiver's chosen style: instant from this device, then confirmed from (and saved to) their profile so it follows them. */
export function useTodayLayout(): [DoseLayout, (next: DoseLayout) => Promise<boolean>] {
  const [layout, setLayoutState] = useState<DoseLayout>(() => { try { const v = localStorage.getItem(KEY); return valid(v) ? v : "focus"; } catch { return "focus"; } });
  useEffect(() => {
    let cancelled = false;
    void loadTodayLayout().then((v) => { if (!cancelled && valid(v)) { setLayoutState(v); try { localStorage.setItem(KEY, v); } catch { /* ignore */ } } }).catch(() => {});
    return () => { cancelled = true; };
  }, []);
  const set = async (next: DoseLayout) => {
    setLayoutState(next);
    try { localStorage.setItem(KEY, next); } catch { /* ignore */ }
    return saveTodayLayout(next);
  };
  return [layout, set];
}

/** Made-up doses, so every style can be previewed before choosing it. */
function sampleStops(hi: boolean): RouteStop[] {
  const now = Date.now(), day = new Date(); day.setHours(0, 0, 0, 0);
  const at = (h: number, m = 0) => day.getTime() + (h * 60 + m) * 60000;
  const suffix = (h: number) => (hi ? (h < 12 ? "सुबह" : h < 16 ? "दोपहर" : h < 20 ? "शाम" : "रात") : h < 12 ? "AM" : "PM");
  const pill = (type: "taken" | "pending" | "missed", text: string) => <span className={`status ${type}`}>{text}</span>;
  const rows: { h: number; m: number; name: string; dose: string; state: RouteStop["state"] }[] = [
    { h: 6, m: 30, name: "Thyroxine", dose: "50 mcg", state: "done" }, { h: 8, m: 0, name: "Metformin", dose: "500 mg", state: "done" },
    { h: 13, m: 0, name: "Calcium", dose: "1 tablet", state: "done" }, { h: 14, m: 30, name: "Amlodipine", dose: "5 mg", state: "missed" },
    { h: 17, m: 30, name: "Vitamin D3", dose: "1 tablet", state: "todo" }, { h: 20, m: 0, name: "Aspirin", dose: "75 mg", state: "todo" },
    { h: 21, m: 30, name: "Omega-3", dose: "1 capsule", state: "todo" },
  ];
  return rows.map((r, i) => ({
    id: `sample-${i}`, name: r.name, dose: r.dose, state: r.state, tod: r.h * 60 + r.m,
    at: i === 4 ? now + 130 * 60000 : at(r.h, r.m),   // the next dose is always "in 2 h 10 min"
    time: `${r.h % 12 || 12}:${String(r.m).padStart(2, "0")}`, suffix: suffix(r.h),
    pill: pill(r.state === "done" ? "taken" : r.state === "missed" ? "missed" : "pending", r.state === "done" ? (hi ? "ली गई" : "Taken") : r.state === "missed" ? (hi ? "छूटी" : "Missed") : (hi ? "आने वाली" : "Upcoming")),
    icon: r.state === "done" ? "✓" : r.state === "missed" ? "!" : <Icon name="pill" size={15} />,
  }));
}

/** "Change style": try every style with sample data, then save the one you like. */
export function LayoutSheet({ current, onSave, onClose }: { current: DoseLayout; onSave: (next: DoseLayout) => void; onClose: () => void }) {
  const t = useTranslations("layoutPicker");
  const tt = useTranslations("today");
  const hi = useLocale() === "hi";
  const dialog = useDialog(onClose);
  const [choice, setChoice] = useState<DoseLayout>(current);
  const stops = sampleStops(hi);
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <section className="sheet layout-sheet" ref={dialog} tabIndex={-1} role="dialog" aria-modal="true" aria-label={t("title")} onClick={(event) => event.stopPropagation()}>
        <h3>{t("title")}</h3>
        <p className="sheet-hint">{t("subtitle")}</p>
        <div className="layout-preview" aria-label={t("previewLabel")}>
          <DoseLayouts layout={choice} stops={stops} avatar="P" startLabel={tt("routeStart")} endLabel={tt("routeEnd")} nextLabel={tt("routeNext")} />
        </div>
        <ul className="layout-options" role="radiogroup" aria-label={t("title")}>
          {LAYOUTS.map((l) => (
            <li key={l.id}><button role="radio" aria-checked={l.id === choice} className={l.id === choice ? "on" : ""} onClick={() => setChoice(l.id)}>{t(`${l.id}.name`)}</button></li>
          ))}
        </ul>
        <p className="layout-desc">{t(`${choice}.desc`)}</p>
        <div className="layout-actions">
          <button className="primary-button" onClick={() => onSave(choice)}>{t("use")}</button>
          <button className="secondary-button" onClick={onClose}>{t("cancel")}</button>
        </div>
      </section>
    </div>
  );
}

type Props = { stops: RouteStop[]; avatar: string; startLabel: string; endLabel: string; nextLabel: string };
const nextOf = (stops: RouteStop[]) => stops.findIndex((s) => s.state === "current" || s.state === "todo");
const dot = (s: RouteStop, isNext: boolean, avatar: string): ReactNode => (isNext ? <b>{avatar}</b> : s.state === "done" ? "✓" : s.state === "missed" ? "!" : s.icon);

function Timeline({ stops, avatar, nextLabel }: Props) {
  const nx = nextOf(stops);
  return (
    <ol className="lay-timeline">
      {stops.map((s, i) => (
        <li key={s.id} className={`lt-row ${s.state}${i === nx ? " next" : ""}`}>
          <span className="lt-dot" aria-hidden="true">{dot(s, i === nx, avatar)}</span>
          <div className="lt-body">
            <div className="lt-top"><strong>{s.time}</strong><small>{s.suffix}</small>{i === nx && <em>{nextLabel}</em>}<span className="lt-pill">{s.pill}</span></div>
            <h3>{s.name}</h3>
            {s.dose && <p>{s.dose}</p>}
          </div>
        </li>
      ))}
    </ol>
  );
}

function Arc({ stops, avatar, startLabel, endLabel, nextLabel }: Props) {
  const nx = nextOf(stops);
  const pos = (tod: number) => { const t = 0.02 + 0.96 * Math.min(1, Math.max(0, (tod / 60 - 5) / 18)); return { x: 160 - 140 * Math.cos(Math.PI * t), y: 170 - 140 * Math.sin(Math.PI * t) }; };
  return (
    <div className="lay-arc">
      <svg viewBox="0 0 320 198" aria-hidden="true">
        <path d="M 20 170 A 140 140 0 0 1 300 170" className="arc-ahead" />
        <g className="arc-sun" transform="translate(22 168)" aria-hidden="true">
          <circle r="16" className="arc-sun-glow" /><circle r="8" className="arc-sun-core" />
          {[0, 45, 90, 135, 180, 225, 270, 315].map((a) => <line key={a} x1="0" y1="-11.5" x2="0" y2="-15" transform={`rotate(${a})`} className="arc-sun-ray" />)}
        </g>
        <g className="arc-moon" transform="translate(298 168)" aria-hidden="true">
          <circle r="16" className="arc-moon-glow" />
          <path d="M 3 -10 A 10 10 0 1 0 8 6 A 8 8 0 0 1 3 -10 Z" className="arc-moon-core" />
          <circle cx="-13" cy="-12" r="1.2" className="arc-star" /><circle cx="14" cy="-8" r="1" className="arc-star" /><circle cx="-10" cy="13" r="1" className="arc-star" />
        </g>
        {stops.map((s, i) => { const p = pos(s.tod ?? 0); return <g key={s.id} className={`arc-dot ${s.state}${i === nx ? " next" : ""}`}><circle cx={p.x} cy={p.y} r={i === nx ? 13 : 9} />{i === nx && <text x={p.x} y={p.y + 4.5} textAnchor="middle">{avatar}</text>}{s.state === "done" && <path d={`M ${p.x - 4} ${p.y} l 3 3 l 5 -6`} className="arc-tick" />}</g>; })}
        <text x="4" y="195" textAnchor="start" className="arc-text">{startLabel}</text><text x="316" y="195" textAnchor="end" className="arc-text">{endLabel}</text>
      </svg>
      {nx >= 0 && <div className="arc-next"><small>{nextLabel}</small><strong>{stops[nx].time} {stops[nx].suffix}</strong><span>{stops[nx].name}</span></div>}
      <ul className="arc-chips">{stops.map((s, i) => <li key={s.id} className={`${s.state}${i === nx ? " next" : ""}`}><b>{s.time}</b> {s.suffix} · {s.name}</li>)}</ul>
    </div>
  );
}

/** A progress ring, one big card for the next dose, and the rest of the day as a short list. */
function Focus({ stops, avatar, nextLabel }: Props) {
  const language = useLocale();
  const hi = language === "hi";
  const nx = nextOf(stops);
  const done = stops.filter((s) => s.state === "done").length;
  const total = stops.length;
  const R = 40, C = 2 * Math.PI * R;
  const next = nx >= 0 ? stops[nx] : null;
  const minutes = next?.at ? Math.round((next.at - Date.now()) / 60000) : null;
  const when = minutes === null ? "" : minutes > 0
    ? (hi ? `${minutes >= 60 ? `${Math.floor(minutes / 60)} घंटे ` : ""}${minutes % 60} मिनट में` : `in ${minutes >= 60 ? `${Math.floor(minutes / 60)} h ` : ""}${minutes % 60} min`)
    : (hi ? "अभी" : "due now");
  const rest = stops.map((s, i) => ({ s, i })).filter(({ i }) => i !== nx);
  return (
    <div className="lay-focus">
      <div className="fc-hero">
        <div className="fc-ring" role="img" aria-label={hi ? `${total} में से ${done} ली गईं` : `${done} of ${total} taken`}>
          <svg viewBox="0 0 100 100" aria-hidden="true"><circle cx="50" cy="50" r={R} className="fc-track" /><circle cx="50" cy="50" r={R} className="fc-arc" strokeDasharray={`${(done / Math.max(total, 1)) * C} ${C}`} transform="rotate(-90 50 50)" /></svg>
          <span><strong>{done}/{total}</strong></span>
        </div>
        {next ? (
          <div className="fc-next">
            <em>{nextLabel}</em>
            <strong>{next.time} <small>{next.suffix}</small></strong>
            <h3>{next.name}</h3>
            <p>{next.dose}{next.dose && when ? " · " : ""}{when}</p>
          </div>
        ) : (
          <div className="fc-next fc-done"><strong>{hi ? "आज की सारी दवाइयाँ हो गईं 🎉" : "All done for today 🎉"}</strong></div>
        )}
      </div>
      {rest.length > 0 && (
        <ul className="fc-list">
          {rest.map(({ s }) => (
            <li key={s.id} className={s.state}>
              <span className="fc-dot" aria-hidden="true">{dot(s, false, avatar)}</span>
              <span className="fc-name"><strong>{s.name}</strong> <small>{s.dose}</small></span>
              <span className="fc-time">{s.time} <small>{s.suffix}</small></span>
              <span className="sr-only">{s.pill}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

const useHi = () => useLocale() === "hi";

/** 6: one big progress ring, the next dose under it and a dot for every dose. */
function Ring({ stops, avatar, nextLabel }: Props) {
  const hi = useHi();
  const nx = nextOf(stops);
  const done = stops.filter((s) => s.state === "done").length, total = stops.length;
  const R = 62, C = 2 * Math.PI * R;
  return (
    <div className="lay-ring">
      <div className="rg-ring" role="img" aria-label={hi ? `${total} में से ${done} ली गईं` : `${done} of ${total} taken`}>
        <svg viewBox="0 0 160 160" aria-hidden="true"><circle cx="80" cy="80" r={R} className="fc-track" /><circle cx="80" cy="80" r={R} className="fc-arc" strokeDasharray={`${(done / Math.max(total, 1)) * C} ${C}`} transform="rotate(-90 80 80)" /></svg>
        <span><strong>{done}/{total}</strong><small>{hi ? "ली गईं" : "taken"}</small></span>
      </div>
      {nx >= 0 && <p className="rg-next"><em>{nextLabel}</em> <strong>{stops[nx].time} {stops[nx].suffix}</strong> · {stops[nx].name}</p>}
      <ul className="rg-dots">{stops.map((s, i) => <li key={s.id} className={`${s.state}${i === nx ? " next" : ""}`} title={`${s.time} ${s.suffix} ${s.name}`}><span aria-hidden="true">{dot(s, i === nx, avatar)}</span><small>{s.time}</small><span className="sr-only">{s.name} {s.pill}</span></li>)}</ul>
    </div>
  );
}

/** 7: a pill organiser with one compartment per dose. */
function PillBox({ stops, avatar }: Props) {
  const nx = nextOf(stops);
  return (
    <ul className="lay-pillbox">
      {stops.map((s, i) => (
        <li key={s.id} className={`${s.state}${i === nx ? " next" : ""}`}>
          <span className="pb-lid" aria-hidden="true" />
          <span className="pb-pill" aria-hidden="true">{dot(s, i === nx, avatar)}</span>
          <strong>{s.time}<small>{s.suffix}</small></strong>
          <span className="pb-name">{s.name}</span>
          <span className="sr-only">{s.pill}</span>
        </li>
      ))}
    </ul>
  );
}

/** 8: big, simple rows. */
function Ladder({ stops, nextLabel }: Props) {
  const nx = nextOf(stops);
  return (
    <ul className="lay-ladder">
      {stops.map((s, i) => (
        <li key={s.id} className={`${s.state}${i === nx ? " next" : ""}`}>
          <span className="ld-time"><strong>{s.time}</strong><small>{s.suffix}</small></span>
          <span className="ld-main"><strong>{s.name}</strong><small>{s.dose}</small></span>
          <span className="ld-state">{i === nx ? <em>{nextLabel}</em> : s.pill}</span>
        </li>
      ))}
    </ul>
  );
}

/** 9: a 24-hour clock with the doses around it and a hand for now. */
function Clock({ stops, avatar, nextLabel }: Props) {
  const nx = nextOf(stops);
  const at = (tod: number, r: number) => { const a = (tod / 1440) * 2 * Math.PI - Math.PI / 2; return { x: 100 + r * Math.cos(a), y: 100 + r * Math.sin(a) }; };
  const now = new Date(); const nowTod = now.getHours() * 60 + now.getMinutes();
  const hand = at(nowTod, 44);
  return (
    <div className="lay-clock">
      <div className="ck-wrap">
      <svg viewBox="0 0 200 200" aria-hidden="true">
        <circle cx="100" cy="100" r="72" className="ck-face" />
        {[0, 6, 12, 18].map((h) => { const a = at(h * 60, 80), b = at(h * 60, 72); const t = at(h * 60, 90); return <g key={h}><line x1={a.x} y1={a.y} x2={b.x} y2={b.y} className="ck-tick" /><text x={t.x} y={t.y + 3} textAnchor="middle" className="ck-num">{h === 0 ? "12 AM" : h === 12 ? "12 PM" : h > 12 ? `${h - 12} PM` : `${h} AM`}</text></g>; })}
        <line x1="100" y1="100" x2={hand.x} y2={hand.y} className="ck-hand" /><circle cx="100" cy="100" r="3.5" className="ck-hub" />
        {stops.map((s, i) => { const p = at(s.tod ?? 0, 72); return <g key={s.id} className={`ck-dot ${s.state}${i === nx ? " next" : ""}`}><circle cx={p.x} cy={p.y} r={i === nx ? 11 : 8} />{i === nx && <text x={p.x} y={p.y + 4} textAnchor="middle">{avatar}</text>}{s.state === "done" && <path d={`M ${p.x - 3.5} ${p.y} l 2.5 2.5 l 4.5 -5`} className="arc-tick" />}</g>; })}
      </svg>
      {nx >= 0 && <div className="ck-next"><small>{nextLabel}</small><strong>{stops[nx].time} {stops[nx].suffix}</strong><span>{stops[nx].name}</span></div>}
      </div>
      <ul className="arc-chips">{stops.map((s, i) => <li key={s.id} className={`${s.state}${i === nx ? " next" : ""}`}><b>{s.time}</b> {s.suffix} · {s.name}</li>)}</ul>
    </div>
  );
}

/** 10: the day as a chat, like the WhatsApp reminders the parent gets. */
function Chat({ stops }: Props) {
  const hi = useHi();
  const text = { time: hi ? "दवा का समय" : "Time for", taken: hi ? "✅ ली गई" : "✅ Taken", missed: hi ? "जवाब नहीं आया" : "No reply", soon: hi ? "आने वाली" : "Upcoming", due: hi ? "इंतज़ार में" : "Waiting for reply" };
  return (
    <div className="lay-chat">
      {stops.map((s) => (
        <div key={s.id} className={`ch-pair ${s.state}`}>
          <div className="ch-in"><span>{text.time} <strong>{s.name}</strong>{s.dose && <small> ({s.dose})</small>}</span><time>{s.time} {s.suffix}</time></div>
          {s.state === "done" && <div className="ch-out ok">{text.taken}</div>}
          {s.state === "missed" && <div className="ch-out bad">{text.missed}</div>}
          {s.state === "current" && <div className="ch-out wait">{text.due}</div>}
        </div>
      ))}
    </div>
  );
}

export function DoseLayouts({ layout, ...props }: Props & { layout: DoseLayout }) {
  if (layout === "route") return <DoseRoute {...props} />;
  if (layout === "curvy") return <DoseRoute {...props} curvy />;
  if (layout === "timeline") return <Timeline {...props} />;
  if (layout === "arc") return <Arc {...props} />;
  if (layout === "focus") return <Focus {...props} />;
  if (layout === "ring") return <Ring {...props} />;
  if (layout === "pillbox") return <PillBox {...props} />;
  if (layout === "ladder") return <Ladder {...props} />;
  if (layout === "clock") return <Clock {...props} />;
  return <Chat {...props} />;
}
