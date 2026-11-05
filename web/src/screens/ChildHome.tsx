import { useEffect, useState } from "react";
import "./childHome.css";
import { useLocale, useTranslations } from "use-intl";
import { Icon, parentCall, useDialog } from "../App";
import type { Recipient } from "../lib/api";
import { authUserId } from "../lib/auth";
import { formatDate, type Language } from "../lib/format";
import { receiverName, receiverOf } from "../lib/reminderReceiver";
import { ageGroup, doseText, loadChild, loadVaccines, localToday, plainDate, type ChildInfo, type Vaccine } from "../lib/vaccines";
import { VaccineSheet } from "./Vaccines";

/*
 * Today for a child (care type "child"), mockup "8 · Child vaccine card": who the child is and how old, who gets the reminders,
 * the next vaccine, a small vaccine card of the current age band, and two quick actions. Today's medicines, the consent card and
 * the alerts card stay below it (rendered by Today in App.tsx).
 * Medical safety: every date is "as advised by your doctor"; a dose whose date has passed is never shown as "Due <past date>",
 * it is counted as "to check" and the family is sent to the Vaccines screen. Nothing here says a vaccine is optional or required.
 * Children's data (DPDP): no analytics or tracking here; the data comes from the same family-only endpoints as the Vaccines screen.
 */

type Go = (screen: "vaccines" | "reports") => void;

/** Short names for the tiles (the usual abbreviations on Indian vaccine cards, the same in both languages). */
const SHORT: Record<string, string> = {
  bcg: "BCG", opv: "OPV", hepb: "HepB", dtp: "DTP", ipv: "IPV", hib: "Hib", rotavirus: "Rota", pcv: "PCV", influenza: "Flu",
  tcv: "TCV", mmr: "MMR", hepa: "HepA", varicella: "Var", hpv: "HPV", tdap: "Tdap", td: "Td",
};

/** Age bands of the card, in months from birth: first year, 1-2 years, 2-6 years, 7-18 years. */
const BAND_STARTS = [0, 12, 24, 84];
const bandOfMonths = (months: number) => BAND_STARTS.reduce((band, start, i) => (months >= start ? i : band), 0);

const dayNumber = (iso: string) => Math.round(plainDate(iso).getTime() / 86_400_000);

/** Completed months between two plain dates. */
function monthsBetween(from: string, to: string): number {
  const [fy, fm, fd] = from.split("-").map(Number);
  const [ty, tm, td] = to.split("-").map(Number);
  return (ty - fy) * 12 + (tm - fm) - (td < fd ? 1 : 0);
}

/** Where a dose sits, in months from birth: from its age group, else from its suggested date. */
function doseMonths(v: Vaccine, dob: string | null): number {
  const g = ageGroup(v.group);
  if (g?.kind === "birth") return 0;
  if (g?.kind === "weeks") return g.from / 4.345;
  if (g?.kind === "months") return g.from;
  if (g?.kind === "years") return g.from * 12;
  return dob ? Math.max(0, monthsBetween(dob, v.suggestedDueDate)) : 0;
}

const isOpen = (v: Vaccine) => v.status === "due_soon" || v.status === "later";
const vaccineName = (v: Vaccine, language: Language) => (language === "hi" && v.vaccineHi ? v.vaccineHi : v.vaccineEn);

export function ChildHome({ recipient, go, onLanguage, onBell, onPeople, unread }: {
  recipient: Recipient; go: Go; onLanguage: () => void; onBell: () => void; onPeople: () => void; unread: number;
}) {
  const t = useTranslations("childHome");
  const tv = useTranslations("vaccines");
  const ta = useTranslations("vaccines.age");
  const tc = useTranslations("common");
  const language = useLocale() as Language;
  const call = parentCall(recipient, language);
  const today = localToday(recipient.timezone ?? "Asia/Kolkata");
  const recipientId = recipient.id;
  // Loaded data is tagged with the child it belongs to, so switching person never shows the previous child's card.
  const [data, setData] = useState<{ id: string; info: ChildInfo | null; list: Vaccine[] } | null>(null);
  const [failedFor, setFailedFor] = useState<string | null>(null);
  const [open, setOpen] = useState<Vaccine | null>(null);
  const [remindOpen, setRemindOpen] = useState(false);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let cancelled = false;
    Promise.all([loadChild(recipientId).catch(() => null), loadVaccines(recipientId)])
      .then(([info, list]) => { if (!cancelled) { setData({ id: recipientId, info, list }); setFailedFor(null); } })
      .catch(() => { if (!cancelled) setFailedFor(recipientId); });
    return () => { cancelled = true; };
  }, [recipientId, tick]);
  const mine = data && data.id === recipientId ? data : null;
  const dob = mine?.info?.dob ?? recipient.dob ?? null;

  // ---- age line: "Child · 5 months"
  const ageText = (() => {
    if (!dob || dob > today) return "";
    const days = dayNumber(today) - dayNumber(dob);
    if (days === 0) return t("newborn");
    if (days < 14) return t("days", { count: days });
    if (days < 60) return t("weeks", { count: Math.floor(days / 7) });
    const months = monthsBetween(dob, today);
    return months < 24 ? t("months", { count: months }) : t("years", { count: Math.floor(months / 12) });
  })();

  // ---- who gets the WhatsApp (medicine) reminders
  const rcv = receiverOf(recipient);
  const rcvName = receiverName(rcv);
  const toMe = rcv.reminder_to === "caregiver" && !!rcv.reminder_user_id && rcv.reminder_user_id === authUserId();
  const receiverLine = rcv.reminder_to === "person" ? "" : toMe ? t("receiverYou") : t("receiverOther", { name: rcvName || t("receiverSomeone") });

  // ---- the plan
  const list = mine ? [...mine.list].sort((a, b) => a.dueDate.localeCompare(b.dueDate)) : [];
  const toCheck = list.filter((v) => v.status === "overdue");
  const next = list.find(isOpen) ?? null;
  const needsSetup = !!mine && (list.length === 0 || (mine.info !== null && (!mine.info.dob || !mine.info.guardianConfirmedAt)));
  const edition = mine?.info?.edition ?? list[0]?.edition ?? "";
  const reviewed = mine?.info?.reviewedByPaediatrician ?? false;

  const shortDate = (iso: string) => formatDate(plainDate(iso), language,
    iso.slice(0, 4) === today.slice(0, 4) ? { day: "numeric", month: "short", timeZone: "UTC" } : { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
  const ageLabel = (group: string | null) => {
    const g = ageGroup(group);
    if (!g) return "";
    if (g.kind === "birth") return ta("birth");
    if (g.to !== undefined) return ta(g.kind === "years" ? "yearsRange" : g.kind === "months" ? "monthsRange" : "weeks", { from: g.from, to: g.to, n: g.from });
    return ta(g.kind, { n: g.from });
  };
  const title = (v: Vaccine) => [vaccineName(v, language), doseText(tv, v.dose)].filter(Boolean).join(" · ");
  const when = (v: Vaccine) => {
    const days = dayNumber(v.dueDate) - dayNumber(today);
    const date = shortDate(v.dueDate);
    const base = days <= 0 ? t("dueToday", { date }) : days === 1 ? t("dueTomorrow", { date }) : t("inDays", { count: days, date });
    const age = ageLabel(v.group);
    return age ? `${base} (${age})` : base;
  };

  // ---- the card: the doses of the child's current age band
  const ageMonths = dob && dob <= today ? monthsBetween(dob, today) : 0;
  const band = bandOfMonths(ageMonths);
  const tiles = list.filter((v) => bandOfMonths(doseMonths(v, dob)) === band);
  const given = tiles.filter((v) => v.status === "given").length;
  const tileState = (v: Vaccine) =>
    v.status === "given" ? "given" : v.status === "not_given" ? "notgiven" : v.status === "overdue" ? "check" : next && isOpen(v) && v.dueDate === next.dueDate ? "next" : "later";
  const shownStates = new Set(tiles.map(tileState));
  const stateWord: Record<string, string> = { given: t("legendGiven"), next: t("legendNext"), check: t("legendCheck"), later: t("legendLater"), notgiven: t("legendNotGiven") };

  // ---- hero
  const hero = () => {
    if (failedFor === recipientId && !mine) return <p role="alert" className="form-error">{tv("loadFailed")} <button className="inline-link" onClick={() => setTick((n) => n + 1)}>{t("retry")}</button></p>;
    if (!mine) return <div className="skeleton card" aria-busy="true" />;
    if (needsSetup) {
      return (
        <button type="button" className="child-hero setup" onClick={() => go("vaccines")}>
          <span className="child-hero-kicker">{t("nextLabel")}</span>
          <strong>{tv("nextNone", { call })}</strong>
          <span className="child-hero-sub">{t("setupBody")}</span>
          <span className="child-hero-cta">{t("setupAction")} <Icon name="chevron" size={16} /></span>
        </button>
      );
    }
    if (toCheck.length > 0) {
      return (
        <button type="button" className="child-hero check" onClick={() => go("vaccines")}>
          <span className="child-hero-kicker">{t("checkLabel")}</span>
          <strong>{t("checkTitle", { count: toCheck.length })}</strong>
          <span className="child-hero-sub">{t("checkBody", { count: toCheck.length })}</span>
          {next && <span className="child-hero-sub">{t("checkNext", { vaccine: title(next), date: shortDate(next.dueDate) })}</span>}
          <span className="child-hero-cta">{t("checkAction")} <Icon name="chevron" size={16} /></span>
        </button>
      );
    }
    if (next) {
      return (
        <button type="button" className="child-hero" onClick={() => setOpen(next)}>
          <span className="child-hero-kicker">{t("nextLabel")}</span>
          <strong>{title(next)}</strong>
          <span className="child-hero-when">{when(next)}</span>
          <span className="child-hero-sub">{tv("asAdvised")}</span>
        </button>
      );
    }
    return (
      <button type="button" className="child-hero done" onClick={() => go("vaccines")}>
        <span className="child-hero-kicker">{t("nextLabel")}</span>
        <strong>{t("allDone")}</strong>
        <span className="child-hero-sub">{tv("asAdvised")}</span>
      </button>
    );
  };

  return (
    <div className="child-home">
      <header className="home-header">
        <button className="parent-profile" aria-haspopup="dialog" onClick={onPeople}>
          <span className="initials-avatar child-avatar" aria-hidden="true">{([...recipient.name][0] ?? "C").toUpperCase()}</span>
          <span>
            <small className="child-kicker">{ageText ? t("kicker", { age: ageText }) : t("kickerNoAge")}</small>
            <strong>{call} <Icon name="down" size={16} /></strong>
            {receiverLine && <em>{receiverLine}</em>}
          </span>
        </button>
        <div className="header-actions">
          <button className="language-button" aria-label={tc("switchLanguageLabel")} onClick={onLanguage}>{tc("switchLanguage")}</button>
          <button className="icon-button notification" aria-label={tc("notifications")} onClick={onBell}><Icon name="bell" />{unread > 0 && <i />}</button>
        </div>
      </header>

      {hero()}

      {mine && !needsSetup && tiles.length > 0 && (
        <section className="child-card" aria-labelledby="child-card-title">
          <div className="child-card-head">
            <div>
              <h2 id="child-card-title">{t(`band${band}` as "band0")}</h2>
              <p>{t("cardCount", { given, total: tiles.length })}</p>
            </div>
            <button type="button" className="inline-link" onClick={() => go("vaccines")}>{t("seeAll")}</button>
          </div>
          <div className="child-tiles">
            {tiles.map((v) => {
              const state = tileState(v);
              return (
                <button key={v.id} type="button" className={`child-tile ${state}`} onClick={() => setOpen(v)} aria-label={`${title(v)}: ${stateWord[state]}`}>
                  <b>{SHORT[v.vaccine] ?? v.vaccineEn.split(/[\s(]/)[0].slice(0, 5)}</b>
                  {v.dose && <small>{v.dose}</small>}
                  {state === "given" && <i aria-hidden="true"><Icon name="check" size={12} /></i>}
                </button>
              );
            })}
          </div>
          <ul className="child-legend" aria-label={t("legendLabel")}>
            {(["given", "next", "check", "later", "notgiven"] as const).filter((s) => s === "given" || s === "later" || shownStates.has(s)).map((s) => (
              <li key={s}><span className={`child-swatch ${s}`} aria-hidden="true" />{stateWord[s]}</li>
            ))}
          </ul>
        </section>
      )}

      <div className="child-actions">
        <button type="button" className="child-action" onClick={() => go("reports")}>
          <span className="child-action-icon" aria-hidden="true"><Icon name="camera" size={20} /></span>
          <span><strong>{t("actionPhoto")}</strong><small>{t("actionPhotoSub")}</small></span>
        </button>
        <button type="button" className="child-action" onClick={() => setRemindOpen(true)}>
          <span className="child-action-icon" aria-hidden="true"><Icon name="bell" size={20} /></span>
          <span><strong>{t("actionRemind")}</strong><small>{t("actionRemindSub")}</small></span>
        </button>
      </div>

      {edition && <p className="child-source">{reviewed ? t("source", { edition }) : t("sourceUnreviewed", { edition })}</p>}

      {open && <VaccineSheet recipient={recipient} vaccine={open} onClose={() => setOpen(null)} onSaved={() => { setOpen(null); setTick((n) => n + 1); }} />}
      {remindOpen && (
        <RemindSheet call={call} name={toMe ? "" : rcv.reminder_to === "person" ? "" : rcvName} onClose={() => setRemindOpen(false)} onOpenVaccines={() => { setRemindOpen(false); go("vaccines"); }} />
      )}
    </div>
  );
}

/** What "Reminder 3 days before" does today: an in-app reminder to the family. WhatsApp to the receiver is not built yet (VaccineReminderService TODO). */
function RemindSheet({ call, name, onClose, onOpenVaccines }: { call: string; name: string; onClose: () => void; onOpenVaccines: () => void }) {
  const t = useTranslations("childHome");
  const dialog = useDialog(onClose);
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <section className="sheet child-remind" ref={dialog} tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby="child-remind-title" onClick={(event) => event.stopPropagation()}>
        <div className="sheet-head"><h3 id="child-remind-title">{t("remindTitle")}</h3><button className="sheet-x" aria-label={t("close")} onClick={onClose}>×</button></div>
        <p>{t("remindBody", { call })}</p>
        <p>{name ? t("remindWhatsAppName", { name }) : t("remindWhatsApp")}</p>
        <p>{t("remindDates")}</p>
        <div className="stack">
          <button className="primary-button" type="button" onClick={onOpenVaccines}>{t("openVaccines")}</button>
          <button className="secondary-button" type="button" onClick={onClose}>{t("close")}</button>
        </div>
      </section>
    </div>
  );
}
