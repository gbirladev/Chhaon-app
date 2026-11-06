import { useState, type ReactNode } from "react";
import { useTranslations } from "use-intl";
import "./weekStyles.css";
import { Icon, useDialog } from "../App";
import type { Language } from "../lib/format";
import type { PregnancyStatus } from "../lib/pregnancy";
import { babySizeFor, babySizeIcon, roundCm, roundWeight, type BabySize } from "../lib/babySize";

/**
 * The pregnancy week card in several looks, chosen by the family like the Today medicine styles (DoseLayouts.tsx).
 * Every style shows the same facts (week, trimester, baby size, days to go, due date); only the drawing changes.
 * The choice is kept on this phone (no profile field yet). Wording rules as babySize.ts: size only, approximate, no advice.
 */
export type WeekStyle = "classic" | "hero" | "ring" | "grow" | "night" | "story" | "tiles";
export const WEEK_STYLES: WeekStyle[] = ["classic", "hero", "ring", "grow", "night", "story", "tiles"];
const KEY = "chhaon-week-style";
const valid = (v: unknown): v is WeekStyle => WEEK_STYLES.includes(v as WeekStyle);

export function useWeekStyle(): [WeekStyle, (next: WeekStyle) => void] {
  const [style, setStyle] = useState<WeekStyle>(() => { try { const v = localStorage.getItem(KEY); return valid(v) ? v : "classic"; } catch { return "classic"; } });
  const set = (next: WeekStyle) => { setStyle(next); try { localStorage.setItem(KEY, next); } catch { /* ignore */ } };
  return [style, set];
}

export type WeekCardProps = {
  week: number | null;
  status: PregnancyStatus;
  dueText: string;         // "Due 26 Feb 2027"
  daysText: string;        // "112 days to go" / "The due date is today" / ...
  name: string;            // what the family calls her, for the story card
  language: Language;
  onStyle?: () => void;    // the small "change style" button; absent in the preview
};

/** The item's 3D picture, or its emoji when the image cannot load. */
function SizeImage({ size, alt, px, className }: { size: BabySize; alt: string; px: number; className?: string }) {
  const [failed, setFailed] = useState<string | null>(null);
  return failed === size.icon
    ? <span className={`ws-emoji ${className ?? ""}`} style={{ fontSize: px * 0.7, width: px, height: px }} aria-hidden="true">{size.emoji}</span>
    : <img key={size.icon} className={className} src={babySizeIcon(size)} alt={alt} width={px} height={px} decoding="async" onError={() => setFailed(size.icon)} />;
}

function useFacts(p: WeekCardProps) {
  const t = useTranslations();
  const size = babySizeFor(p.week);
  const item = size ? (p.language === "hi" ? size.item.hi : size.item.en) : "";
  const w = size ? roundWeight(size.g) : null;
  const weight = w ? (w.unit === "g" ? t("pregnancyHome.babyGrams", { value: w.value }) : t("pregnancyHome.babyKg", { value: w.value })) : "";
  const cm = size ? t("weekStyle.cm", { value: roundCm(size.cm) }) : "";
  const tri = p.status.trimester ? t(`pregnancy.trimester${p.status.trimester}`) : "";
  return { t, size, item, weight, cm, tri };
}

const StyleButton = ({ onStyle }: { onStyle?: () => void }) => {
  const t = useTranslations("weekStyle");
  return onStyle ? <button type="button" className="ws-style-btn" onClick={onStyle} aria-label={t("change")}><Icon name="sliders" size={17} /></button> : null;
};

const Bar = ({ week, label, className = "ws-bar" }: { week: number; label: string; className?: string }) => (
  <span className={className} role="progressbar" aria-valuemin={0} aria-valuemax={40} aria-valuenow={Math.min(40, week)} aria-label={label}>
    <i style={{ width: `${Math.min(100, (week / 40) * 100)}%` }} />
  </span>
);

/**
 * The 40 weeks as three labelled parts (trimesters: weeks 1-13, 14-27, 28-40), filled up to this week, with a dot where she is
 * now and "Week 24 of 40 · 16 weeks to go" above it, so the bar reads on its own.
 */
function TrimesterBar({ week }: { week: number }) {
  const t = useTranslations();
  const w = Math.min(40, Math.max(0, week));
  const parts = [{ n: 1, from: 0, to: 13 }, { n: 2, from: 13, to: 27 }, { n: 3, from: 27, to: 40 }];
  const left = Math.max(0, 40 - w);
  return (
    <div className="ws-tri" role="img" aria-label={`${t("pregnancyHome.progress", { week: w })} · ${t("weekStyle.weeksLeft", { count: left })}`}>
      <div className="ws-tri-head"><b>{t("pregnancyHome.progress", { week: w })}</b><span>{t("weekStyle.weeksLeft", { count: left })}</span></div>
      <div className="ws-tri-track" aria-hidden="true">
        {parts.map((part) => {
          const fill = Math.min(1, Math.max(0, (w - part.from) / (part.to - part.from)));
          return <span key={part.n} className={`ws-tri-part${w > part.from && w <= part.to ? " now" : ""}`} style={{ flexGrow: part.to - part.from }}><i style={{ width: `${fill * 100}%` }} /></span>;
        })}
        <em className="ws-tri-dot" style={{ left: `${(w / 40) * 100}%` }} />
      </div>
      <div className="ws-tri-labels" aria-hidden="true">
        {parts.map((part) => <small key={part.n} className={w > part.from && w <= part.to ? "now" : ""} style={{ flexGrow: part.to - part.from }}>{t(`pregnancy.trimester${part.n}`)}</small>)}
      </div>
    </div>
  );
}

const Stat = ({ big, small }: { big: ReactNode; small: string }) => <span className="ws-stat"><b>{big}</b><small>{small}</small></span>;

// ---------------------------------------------------------------- the styles

function Classic(p: WeekCardProps) {
  const { t, size, item, weight, tri } = useFacts(p);
  return (
    <section className="ph-hero ws-classic" aria-label={t("pregnancy.cardTitle")}>
      <StyleButton onStyle={p.onStyle} />
      {p.week !== null ? <p className="ph-week"><b>{p.week}</b><span>{t("pregnancyHome.weeksWord", { count: p.week })}{tri ? ` · ${tri}` : ""}</span></p> : <p className="ph-week only">{p.dueText}</p>}
      {size && (
        <p className="ph-size">
          <SizeImage size={size} alt={item} px={52} className="ph-size-icon" />
          <span className="ph-size-text">{t("pregnancyHome.babySize", { item, cm: roundCm(size.cm), weight })}<small>{t("pregnancyHome.babySizeNote")}</small></span>
        </p>
      )}
      {p.week !== null && <Bar week={p.week} label={t("pregnancyHome.progress", { week: p.week })} className="ph-bar" />}
      <p className="ph-due"><span>{p.daysText}</span>{p.status.daysToGo > 0 && <span>{p.dueText}</span>}</p>
    </section>
  );
}

function Hero(p: WeekCardProps) {
  const { t, size, item, weight, cm, tri } = useFacts(p);
  return (
    <section className="ws-card ws-hero" aria-label={t("pregnancy.cardTitle")}>
      <StyleButton onStyle={p.onStyle} />
      <div className="ws-hero-top">
        <strong>{p.week !== null ? t("pregnancy.week", { week: p.week }) : p.dueText}</strong>
        <span>{[tri, p.daysText].filter(Boolean).join(" · ")}</span>
      </div>
      {size && <span className="ws-halo"><SizeImage size={size} alt={item} px={132} /></span>}
      {size && <p className="ws-hero-line">{t("weekStyle.sizeLine", { item })}</p>}
      {size && <p className="ws-chips"><span>{cm}</span><span>{weight}</span></p>}
      {p.week !== null && <Bar week={p.week} label={t("pregnancyHome.progress", { week: p.week })} />}
      <small className="ws-note">{t("pregnancyHome.babySizeNote")}</small>
    </section>
  );
}

function Ring(p: WeekCardProps) {
  const { t, size, item, weight, cm } = useFacts(p);
  const r = 88, c = 2 * Math.PI * r, part = p.week !== null ? Math.min(1, p.week / 40) : 0;
  return (
    <section className="ws-card ws-ring" aria-label={t("pregnancy.cardTitle")}>
      <StyleButton onStyle={p.onStyle} />
      <div className="ws-ring-box">
        <svg viewBox="0 0 200 200" aria-hidden="true">
          <defs><linearGradient id="ws-ring-g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stopColor="#8b6fd1" /><stop offset="1" stopColor="#e0789a" /></linearGradient></defs>
          <circle cx="100" cy="100" r={r} className="ws-ring-base" />
          <circle cx="100" cy="100" r={r} className="ws-ring-arc" stroke="url(#ws-ring-g)" strokeDasharray={`${c * part} ${c}`} transform="rotate(-90 100 100)" />
        </svg>
        <div className="ws-ring-in">
          {size && <SizeImage size={size} alt={item} px={78} />}
          <strong>{p.week !== null ? t("pregnancy.week", { week: p.week }) : ""}</strong>
          <small>{t("weekStyle.of40")}</small>
        </div>
      </div>
      {size && <p className="ws-center">{t("weekStyle.sizeLine", { item })}</p>}
      <div className="ws-stats">{size && <Stat big={cm} small={t("weekStyle.length")} />}{size && <Stat big={weight} small={t("weekStyle.weight")} />}<Stat big={p.status.daysToGo > 0 ? p.status.daysToGo : "0"} small={t("weekStyle.daysLeft")} /></div>
    </section>
  );
}

function Grow(p: WeekCardProps) {
  const { t, size, item, weight, cm } = useFacts(p);
  const hi = p.language === "hi";
  const near = p.week === null ? [] : [-2, -1, 0, 1, 2].map((d) => ({ d, s: babySizeFor(p.week! + d) })).filter((x) => x.s);
  const prev = p.week !== null ? babySizeFor(p.week - 1) : null, next = p.week !== null ? babySizeFor(p.week + 1) : null;
  const name = (s: BabySize) => (hi ? s.item.hi : s.item.en);
  return (
    <section className="ws-card ws-grow" aria-label={t("pregnancy.cardTitle")}>
      <StyleButton onStyle={p.onStyle} />
      <h3>{t("weekStyle.growing")}</h3>
      {near.length > 0 && (
        <ol className="ws-grow-row" aria-hidden="true">
          {near.map(({ d, s }) => <li key={d} className={d === 0 ? "now" : ""}><SizeImage size={s!} alt="" px={d === 0 ? 76 : 40} /><span>{p.week! + d}</span></li>)}
        </ol>
      )}
      {size && <div className="ws-box"><small>{t("weekStyle.thisWeek", { week: p.week ?? 0 })}</small><strong>{t("weekStyle.sizeLine", { item })}</strong><span>{cm} · {weight}</span></div>}
      {(prev || next) && <div className="ws-pair">
        {prev && <div className="ws-box soft"><small>{t("weekStyle.lastWeek")}</small><strong>{name(prev)}</strong></div>}
        {next && <div className="ws-box soft"><small>{t("weekStyle.nextWeek")}</small><strong>{name(next)}</strong></div>}
      </div>}
      <p className="ph-due"><span>{p.daysText}</span>{p.status.daysToGo > 0 && <span>{p.dueText}</span>}</p>
    </section>
  );
}

function Night(p: WeekCardProps) {
  const { t, size, item } = useFacts(p);
  return (
    <section className="ws-card ws-night" aria-label={t("pregnancy.cardTitle")}>
      <StyleButton onStyle={p.onStyle} />
      <span className="ws-stars" aria-hidden="true">✦ · ✧ · ✦</span>
      {p.week !== null ? <><b className="ws-big">{p.week}</b><span className="ws-night-sub">{t("pregnancyHome.weeksWord", { count: p.week })}</span></> : <b className="ws-night-sub">{p.dueText}</b>}
      {size && <span className="ws-glow"><SizeImage size={size} alt={item} px={104} /></span>}
      {size && <p className="ws-center">{t("weekStyle.sizeLine", { item })}</p>}
      <small className="ws-night-sub">{p.daysText}{p.status.daysToGo > 0 ? ` · ${p.dueText}` : ""}</small>
    </section>
  );
}

function Story(p: WeekCardProps) {
  const { t, size, item, weight, cm } = useFacts(p);
  const [shared, setShared] = useState(false);
  const weekText = p.week !== null ? t("pregnancy.week", { week: p.week }) : "";
  const text = [`${t("weekStyle.storyTitle")} · ${weekText}`, size ? t("weekStyle.storyLine", { item, cm, weight }) : "", p.daysText].filter(Boolean).join("\n");
  const share = async () => {
    try {
      if (navigator.share) await navigator.share({ text });
      else { await navigator.clipboard.writeText(text); setShared(true); }
    } catch { /* the person closed the share sheet */ }
  };
  return (
    <section className="ws-card ws-story" aria-label={t("pregnancy.cardTitle")}>
      <StyleButton onStyle={p.onStyle} />
      <h3>{t("weekStyle.storyTitle")}</h3>
      {p.week !== null && <TrimesterBar week={p.week} />}
      {size && <span className="ws-blob"><SizeImage size={size} alt={item} px={140} /></span>}
      {size && <p>{t("weekStyle.storyLine", { item, cm, weight })}</p>}
      <div className="ws-story-foot">
        <span><b>{p.daysText}</b>{p.status.daysToGo > 0 && <small>{p.dueText}</small>}</span>
        <button type="button" className="ws-share" onClick={() => void share()}>{shared ? t("weekStyle.copied") : t("weekStyle.share")}</button>
      </div>
    </section>
  );
}

function Tiles(p: WeekCardProps) {
  const { t, size, item, weight, cm } = useFacts(p);
  const weeks = p.week === null ? [] : [-3, -2, -1, 0, 1, 2, 3].map((d) => p.week! + d).filter((w) => w >= 1 && w <= 42);
  return (
    <section className="ws-tiles" aria-label={t("pregnancy.cardTitle")}>
      <StyleButton onStyle={p.onStyle} />
      {weeks.length > 0 && <ol className="ws-weeks" aria-hidden="true">{weeks.map((w) => <li key={w} className={w === p.week ? "now" : ""}><small>{t("weekStyle.wk")}</small><b>{w}</b></li>)}</ol>}
      <div className="ws-grid">
        {size && <div className="ws-tile big"><SizeImage size={size} alt={item} px={84} /><strong>{t("weekStyle.sizeLine", { item })}</strong></div>}
        {size && <div className="ws-tile mint"><b>{cm}</b><small>{t("weekStyle.length")}</small></div>}
        {size && <div className="ws-tile lilac"><b>{weight}</b><small>{t("weekStyle.weight")}</small></div>}
        <div className="ws-tile wide"><b>{p.daysText}</b>{p.status.daysToGo > 0 && <small>{p.dueText}</small>}</div>
      </div>
    </section>
  );
}

const DRAW: Record<WeekStyle, (p: WeekCardProps) => ReactNode> = { classic: Classic, hero: Hero, ring: Ring, grow: Grow, night: Night, story: Story, tiles: Tiles };

export function WeekCard({ style, ...p }: WeekCardProps & { style: WeekStyle }) {
  const Draw = DRAW[style] ?? Classic;
  return <Draw {...p} />;
}

/** "Change style" for the week card: preview each style with her real week, then keep one. */
export function WeekStyleSheet({ current, card, onSave, onClose }: { current: WeekStyle; card: WeekCardProps; onSave: (next: WeekStyle) => void; onClose: () => void }) {
  const t = useTranslations("weekStyle");
  const dialog = useDialog(onClose);
  const [choice, setChoice] = useState<WeekStyle>(current);
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <section className="sheet layout-sheet" ref={dialog} tabIndex={-1} role="dialog" aria-modal="true" aria-label={t("title")} onClick={(event) => event.stopPropagation()}>
        <h3>{t("title")}</h3>
        <p className="sheet-hint">{t("subtitle")}</p>
        <div className="layout-preview ws-preview"><WeekCard style={choice} {...card} onStyle={undefined} /></div>
        <ul className="layout-options" role="radiogroup" aria-label={t("title")}>
          {WEEK_STYLES.map((s) => <li key={s}><button role="radio" aria-checked={s === choice} className={s === choice ? "on" : ""} onClick={() => setChoice(s)}>{t(`${s}.name`)}</button></li>)}
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
