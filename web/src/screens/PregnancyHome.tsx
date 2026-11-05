import { useEffect, useState, type ReactNode } from "react";
import { useTranslations } from "use-intl";
import "./pregnancyHome.css";
import { Icon } from "../App";
import type { Recipient, TodayDose } from "../lib/api";
import { doctorName, loadAppointments, type Appointment } from "../lib/appointments";
import { formatClock, formatDate, localDate, localizeDose, type Language } from "../lib/format";
import { addDays, daysBetween, dateOfWeek, pregnancyStatus, suggestDate, todayIn } from "../lib/pregnancy";
import { USUAL_CHECKS, type UsualCheck } from "../lib/pregnancyChecks";
import { loadDocuments } from "../lib/wallet";
import { AppointmentSheet } from "./Appointments";
import type { CheckPrefill } from "./Pregnancy";

/**
 * FEATURE 7, "Pregnancy timeline home": what Today shows when the selected person is a pregnancy (care_type = pregnant).
 * The week hero, today's medicines (supplements) as a checklist, and one timeline that merges the common checks
 * (pregnancyChecks.ts, NEEDS GYNAECOLOGIST REVIEW) with her real doctor visits, ending at the due date.
 * Nothing about the sex of the baby, no diet / baby-size content (docs/use-cases.md). Older and child people never see this.
 */
type Go = (screen: "profile" | "medicines" | "addMedicine" | "appointments" | "reports") => void;

/** A "YYYY-MM-DD" date as "25 Feb 2027" (the date has no time zone of its own). */
const showDay = (ymd: string, language: Language, weekday = false) =>
  formatDate(new Date(`${ymd}T12:00:00Z`), language, { ...(weekday ? { weekday: "short" } : {}), day: "numeric", month: "short", ...(weekday ? {} : { year: "numeric" }), timeZone: "UTC" });

const checkName = (check: UsualCheck, language: Language) => (language === "hi" ? check.name.hi : check.name.en);

/** A visit "is" a common check when its notes (or doctor line) carry the check's name, which is what "+ Add" writes. */
const matchCheck = (a: Appointment) => {
  const text = `${a.notes ?? ""} ${a.doctor}`.toLowerCase();
  return USUAL_CHECKS.find((c) => c.from !== undefined && (text.includes(c.name.en.toLowerCase()) || text.includes(c.name.hi)));
};

type Item = {
  key: string;
  sort: string;
  state: "done" | "now" | "later";
  title: string;
  sub: string;
  visit?: Appointment;
  add?: CheckPrefill;
  addLabel?: string;
  report?: boolean;
  delivery?: boolean;
};

export function PregnancyHome({ recipient, language, doses, dosesError, medicineCount, go, alerts }: {
  recipient: Recipient; language: Language; doses: TodayDose[] | null; dosesError: boolean; medicineCount: number; go: Go; alerts?: ReactNode;
}) {
  const t = useTranslations();
  const tz = recipient.timezone ?? "Asia/Kolkata";
  const due = recipient.due_date;
  const today = todayIn(tz);
  const status = due ? pregnancyStatus(due, today) : null;
  const week = status?.week ?? null;
  const [visits, setVisits] = useState<{ id: string; list: Appointment[] } | null>(null);
  const [visitsError, setVisitsError] = useState(false);
  const [reportDays, setReportDays] = useState<Set<string>>(new Set());
  const [sheet, setSheet] = useState<CheckPrefill | null>(null);
  const [reload, setReload] = useState(0);
  // "Now" for past / upcoming, ticking every minute (Today itself refreshes the doses every 30 s).
  const [nowMs, setNowMs] = useState(() => Date.now());
  useEffect(() => { const id = window.setInterval(() => setNowMs(Date.now()), 60_000); return () => window.clearInterval(id); }, []);

  useEffect(() => {
    let cancelled = false;
    loadAppointments(recipient.id)
      .then((list) => { if (!cancelled) { setVisits({ id: recipient.id, list }); setVisitsError(false); } })
      .catch(() => { if (!cancelled) setVisitsError(true); });
    // Optional: "report in Reports" next to a past visit with a paper of the same day. Any failure just hides the hint.
    loadDocuments(recipient.id)
      .then((docs) => { if (!cancelled) setReportDays(new Set(docs.map((d) => d.docDate).filter((d): d is string => !!d))); })
      .catch(() => { if (!cancelled) setReportDays(new Set()); });
    return () => { cancelled = true; };
  }, [recipient.id, reload]);
  const list = visits && visits.id === recipient.id ? visits.list : null;

  // ---- the timeline ----
  const items: Item[] = [];
  const start = due ? dateOfWeek(due, 0) : null;
  const booked = new Set<string>();
  const weeksText = (c: UsualCheck) => t("pregnancy.weeks", { from: c.from ?? 0, to: c.to ?? 0 });
  const inWindow = (c: UsualCheck) => week !== null && c.from !== undefined && c.to !== undefined && week >= c.from && week <= c.to;
  const pastVisits = (list ?? []).filter((a) => new Date(a.at).getTime() < nowMs);
  for (const a of list ?? []) {
    const day = localDate(new Date(a.at), tz);
    const past = new Date(a.at).getTime() < nowMs;
    if (start && daysBetween(start, day) < 0) continue;              // before this pregnancy
    if (!start && past && pastVisits.indexOf(a) < pastVisits.length - 5) continue;   // no due date yet: only the latest few past visits
    const check = matchCheck(a);
    if (check) booked.add(check.id);
    const visitWeek = due ? pregnancyStatus(due, day)?.week ?? null : null;
    const doctor = t("appointments.with", { doctor: doctorName(a.doctor) });
    const when = `${showDay(day, language, true)}, ${formatClock(new Date(a.at), tz, language)}`;
    const parts: string[] = [];
    if (past) {
      parts.push(t("pregnancyHome.done"));
      if (visitWeek !== null) parts.push(t("pregnancy.week", { week: visitWeek }));
      if (check) parts.push(doctor);
    } else {
      if (check && inWindow(check)) parts.push(t("pregnancy.windowNow"));
      if (check) parts.push(weeksText(check));
      else if (visitWeek !== null) parts.push(t("pregnancy.week", { week: visitWeek }));
      parts.push(check ? `${when}, ${doctor}` : when);
    }
    items.push({
      key: `v-${a.id}`, sort: day, visit: a,
      state: past ? "done" : check && inWindow(check) ? "now" : "later",
      title: check ? checkName(check, language) : doctor,
      sub: parts.join(" · "),
      report: past && reportDays.has(day),
    });
  }
  if (list) {
    for (const check of USUAL_CHECKS) {
      if (check.from === undefined || check.to === undefined || booked.has(check.id)) continue;
      const name = checkName(check, language);
      const add: CheckPrefill = { notes: name, date: due ? suggestDate(check, due, today) ?? "" : "" };
      if (due && !add.date) continue;                                 // its window has passed: the full list stays on Doctor visits
      const now = inWindow(check);
      const from = due ? dateOfWeek(due, check.from) : null;
      items.push({
        key: `c-${check.id}`,
        sort: from && daysBetween(today, from) > 0 ? from : due ? today : `~${String(check.from).padStart(2, "0")}`,
        state: now ? "now" : "later",
        title: name,
        sub: now ? `${t("pregnancy.windowNow")} · ${weeksText(check)}` : weeksText(check),
        add, addLabel: t("pregnancy.addCheckLabel", { name }),
      });
    }
    if (due) items.push({ key: "delivery", sort: addDays(due, 0), state: "later", title: t("pregnancyHome.delivery"), sub: t("pregnancyHome.dueOn", { date: showDay(due, language) }), delivery: true });
  }
  // Date order; with the same date, a done visit first, then the "now" ones (stable for equal keys).
  const rank = { done: 0, now: 1, later: 2 } as const;
  items.sort((x, y) => (x.sort < y.sort ? -1 : x.sort > y.sort ? 1 : rank[x.state] - rank[y.state]));
  const everyVisit = USUAL_CHECKS.filter((c) => c.from === undefined).map((c) => checkName(c, language));

  // ---- today's medicines ----
  const doseRow = (d: TodayDose) => {
    const time = formatClock(new Date(d.scheduledAt), tz, language);
    const future = new Date(d.scheduledAt).getTime() > nowMs;
    const [kind, word] =
      d.status === "taken" ? ["taken", t("today.taken")]
      : d.status === "taken_late" ? ["taken", t("today.takenLate")]
      : d.status === "missed" ? ["missed", t("today.missed")]
      : d.status === "skipped" ? ["skipped", t("today.notSent")]
      : d.status === "sent" ? ["sent", t("today.pending")]
      : future ? ["todo", t("today.upcoming")] : ["todo", t("today.due")];
    return (
      <li key={d.id}>
        <button type="button" className={`ph-dose ${kind}`} onClick={() => go("medicines")}>
          <span className="ph-dose-mark" aria-hidden="true">{kind === "taken" ? <Icon name="check" size={14} /> : kind === "missed" ? "!" : ""}</span>
          <span className="ph-dose-text">
            <strong>{d.name}{d.dosage ? <small> · {localizeDose(d.dosage, t)}</small> : null}</strong>
            <em>{time} · {word}</em>
          </span>
          <Icon name="chevron" size={16} />
        </button>
      </li>
    );
  };

  return (
    <div className="pregnancy-home">
      {due && status ? (
        <section className="ph-hero" aria-label={t("pregnancy.cardTitle")}>
          {week !== null ? (
            <p className="ph-week"><b>{week}</b><span>{t("pregnancyHome.weeksWord", { count: week })}{status.trimester ? ` · ${t(`pregnancy.trimester${status.trimester}`)}` : ""}</span></p>
          ) : (
            <p className="ph-week only">{t("pregnancy.dueOnly", { date: showDay(due, language) })}</p>
          )}
          {week !== null && (
            <span className="ph-bar" role="progressbar" aria-valuemin={0} aria-valuemax={40} aria-valuenow={Math.min(40, week)} aria-label={t("pregnancyHome.progress", { week })}>
              <i style={{ width: `${Math.min(100, (week / 40) * 100)}%` }} />
            </span>
          )}
          <p className="ph-due">
            <span>{status.daysToGo > 0 ? t("pregnancy.daysToGo", { count: status.daysToGo }) : status.daysToGo === 0 ? t("pregnancy.dueToday") : t("pregnancy.duePassed", { date: showDay(due, language) })}</span>
            {status.daysToGo > 0 && <span>{t("pregnancyHome.dueOn", { date: showDay(due, language) })}</span>}
          </p>
        </section>
      ) : (
        <button type="button" className="ph-hero ph-nodue" onClick={() => go("profile")}>
          <span className="ph-nodue-icon" aria-hidden="true"><Icon name="calendar" size={22} /></span>
          <span className="ph-nodue-text"><strong>{t("pregnancyHome.noDueTitle")}</strong><small>{t("pregnancyHome.noDueBody")}</small></span>
          <Icon name="chevron" size={18} />
        </button>
      )}

      <section className="ph-card" aria-labelledby="ph-today-title">
        <div className="ph-card-head"><h2 id="ph-today-title">{t("pregnancyHome.todayTitle")}</h2><button type="button" className="ph-link" onClick={() => go("medicines")}>{t("today.viewAll")}</button></div>
        {dosesError && <p role="alert" className="form-error">{t("today.loadFailed")}</p>}
        {doses === null && !dosesError && <div className="skeleton card" aria-busy="true" />}
        {doses !== null && doses.length > 0 && <ul className="ph-doses">{doses.map(doseRow)}</ul>}
        {doses !== null && doses.length === 0 && (
          <div className="ph-empty">
            <p>{medicineCount === 0 ? t("pregnancyHome.noMedicines") : t("today.dosesEmpty")}</p>
            {medicineCount === 0 && <button type="button" className="secondary-button" onClick={() => go("addMedicine")}>{t("medicines.add")}</button>}
          </div>
        )}
      </section>

      <section className="ph-timeline-wrap" aria-labelledby="ph-timeline-title">
        <div className="ph-card-head"><h2 id="ph-timeline-title">{t("pregnancyHome.timelineTitle")}</h2><button type="button" className="ph-link" onClick={() => go("appointments")}>{t("today.viewAll")}</button></div>
        {visitsError && <p role="alert" className="form-error">{t("appointments.loadFailed")}</p>}
        {list === null && !visitsError && <div className="skeleton card" aria-busy="true" />}
        {list !== null && (
          <ol className="ph-timeline">
            {items.map((item) => (
              <li key={item.key} className={`ph-item ${item.state}${item.delivery ? " delivery" : ""}`}>
                <span className="ph-rail" aria-hidden="true"><i>{item.state === "done" ? <Icon name="check" size={11} /> : null}</i></span>
                <div className="ph-item-body">
                  {item.visit ? (
                    <button type="button" className="ph-item-main" onClick={() => go("appointments")}>
                      <strong>{item.title}</strong><small>{item.sub}</small>
                    </button>
                  ) : (
                    <div className="ph-item-main"><strong>{item.title}</strong><small>{item.sub}</small></div>
                  )}
                  {item.report && <button type="button" className="ph-report" onClick={() => go("reports")}>{t("pregnancyHome.reportInReports")}</button>}
                </div>
                {item.add && (
                  <button type="button" className="ph-add" aria-label={item.addLabel} onClick={() => setSheet(item.add ?? null)}>
                    + {t("pregnancy.addCheck")}
                  </button>
                )}
              </li>
            ))}
          </ol>
        )}
        {list !== null && everyVisit.length > 0 && <p className="ph-every">{everyVisit.join(", ")} · {t("pregnancy.everyVisit")}</p>}
        <p className="ph-footer">{t("pregnancyHome.footer")}</p>
      </section>

      {alerts}

      {sheet && <AppointmentSheet recipient={recipient} existing={null} prefill={sheet} onClose={() => setSheet(null)} onSaved={() => { setSheet(null); setReload((n) => n + 1); }} />}
    </div>
  );
}
