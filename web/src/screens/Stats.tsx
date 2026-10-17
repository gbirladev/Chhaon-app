import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "use-intl";
import { AppHeader } from "../App";
import { FeedbackInbox } from "./Feedback";
import { loadPilotStats, type PilotStats } from "../lib/stats";
import "./stats.css";

// Meta's per-message fees in India (rupees, GST extra) as found on third-party pages: an UPPER estimate, because a reminder sent
// while the parent's 24-hour window is open is free. Check Meta's rate card before relying on it.
const UTILITY_RUPEES = 0.115;
const MARKETING_RUPEES = 0.86;

const percent = (part: number, whole: number) => (whole > 0 ? Math.round((part / whole) * 100) : null);

/** The owner's numbers: how many people use it and whether the reminders work. Totals only; nobody's name, number or email is here. */
export function StatsScreen({ go }: { go: (screen: "family") => void }) {
  const t = useTranslations("stats");
  const locale = useLocale();
  const [stats, setStats] = useState<PilotStats | "error" | null>(null);
  const [picked, setPicked] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    loadPilotStats().then((s) => { if (!cancelled) setStats(s); }).catch(() => { if (!cancelled) setStats("error"); });
    return () => { cancelled = true; };
  }, []);

  const number = (n: number) => n.toLocaleString(locale === "hi" ? "hi-IN" : "en-IN");
  const day = (iso: string) => new Intl.DateTimeFormat(locale === "hi" ? "hi-IN" : "en-IN", { day: "numeric", month: "short" }).format(new Date(`${iso}T00:00:00`));

  return (
    <main className="scroll-page no-nav stats-page">
      <AppHeader title={t("title")} subtitle={t("subtitle")} back={() => go("family")} />
      {stats === null && <p className="note" role="status">{t("loading")}</p>}
      {stats === "error" && <p className="note" role="alert">{t("error")}</p>}
      {stats && stats !== "error" && (() => {
        const s = stats;
        const reminders = percent(s.reminders7d.taken, s.reminders7d.sent);
        const checkins = percent(s.checkins7d.replied, s.checkins7d.sent);
        const retention = percent(s.caregivers.stillActiveOfThose, s.caregivers.joined4wAgo);
        const paid = Object.entries(s.plans.byPlan).filter(([plan]) => plan !== "free").reduce((sum, [, n]) => sum + n, 0);
        const fee = Math.round(s.whatsapp7d.utility * UTILITY_RUPEES + s.whatsapp7d.marketing * MARKETING_RUPEES);
        const most = Math.max(1, ...s.daily.map((d) => d.remindersTaken));
        const shown = picked !== null ? s.daily[picked] : null;
        return (
          <>
            <section className="stats-hero" aria-label={t("heroLabel")}>
              <span className="big">{number(s.caregivers.active7d)}</span>
              <p>{t("heroText", { total: number(s.caregivers.total) })}</p>
            </section>

            <h2>{t("peopleTitle")}</h2>
            <div className="tiles">
              <div className="tile"><span className="label">{t("caregivers")}</span><span className="value">{number(s.caregivers.total)}</span><span className="sub">{t("newThisWeek", { count: s.caregivers.new7d })}</span></div>
              <div className="tile"><span className="label">{t("parentsAgreed")}</span><span className="value">{number(s.parents.agreed)}<small> / {number(s.parents.total)}</small></span><span className="sub">{t("parentsSub", { waiting: s.parents.waiting, stopped: s.parents.stopped })}</span></div>
              <div className="tile"><span className="label">{t("retention")}</span><span className="value">{retention === null ? "–" : `${retention}%`}</span><span className="sub">{retention === null ? t("retentionEarly") : t("retentionSub", { still: s.caregivers.stillActiveOfThose, joined: s.caregivers.joined4wAgo })}</span></div>
              <div className="tile"><span className="label">{t("familyJoined")}</span><span className="value">{number(s.family.membersJoined)}</span><span className="sub">{t("familySub", { count: s.family.circlesWithMoreThanOne })}</span></div>
            </div>

            <h2>{t("worksTitle")}</h2>
            <div className="tiles">
              <div className="tile"><span className="label">{t("remindersConfirmed")}</span><span className="value">{reminders === null ? "–" : `${reminders}%`}</span><span className="sub">{t("remindersSub", { taken: s.reminders7d.taken, sent: s.reminders7d.sent, missed: s.reminders7d.missed })}</span></div>
              <div className="tile"><span className="label">{t("checkinsAnswered")}</span><span className="value">{checkins === null ? "–" : `${checkins}%`}</span><span className="sub">{t("checkinsSub", { replied: s.checkins7d.replied, sent: s.checkins7d.sent })}</span></div>
            </div>

            <h2>{t("chartTitle")}</h2>
            <section className="chart-card" aria-label={t("chartTitle")}>
              <p className="chart-sub">{t("chartSub", { days: s.daily.length })}</p>
              <div className="bars" role="group" aria-label={t("chartTitle")} onPointerLeave={() => setPicked(null)}>
                {s.daily.map((d, i) => {
                  const peak = d.remindersTaken === most && d.remindersTaken > 0;
                  return (
                    <button
                      key={d.date}
                      type="button"
                      className={`bar-slot${picked === i ? " on" : ""}`}
                      aria-label={t("readout", { date: day(d.date), taken: d.remindersTaken, sent: d.remindersSent })}
                      onPointerEnter={() => setPicked(i)}
                      onFocus={() => setPicked(i)}
                      onBlur={() => setPicked(null)}
                      onClick={() => setPicked(picked === i ? null : i)}
                    >
                      {peak && <span className="bar-value">{d.remindersTaken}</span>}
                      <span className="bar" style={{ height: `${Math.max(2, (d.remindersTaken / most) * 100)}%` }} />
                    </button>
                  );
                })}
              </div>
              <div className="axis" aria-hidden="true"><span>{day(s.daily[0].date)}</span><span>{day(s.daily[s.daily.length - 1].date)}</span></div>
              <p className="readout" aria-live="polite">{shown ? t("readout", { date: day(shown.date), taken: shown.remindersTaken, sent: shown.remindersSent }) : t("readoutHint")}</p>
              <details>
                <summary>{t("tableToggle")}</summary>
                <table>
                  <thead><tr><th scope="col">{t("colDate")}</th><th scope="col">{t("colSent")}</th><th scope="col">{t("colTaken")}</th><th scope="col">{t("colNew")}</th></tr></thead>
                  <tbody>{[...s.daily].reverse().map((d) => (<tr key={d.date}><td>{day(d.date)}</td><td>{d.remindersSent}</td><td>{d.remindersTaken}</td><td>{d.newCaregivers}</td></tr>))}</tbody>
                </table>
              </details>
            </section>

            <h2>{t("plansTitle")}</h2>
            <div className="tiles">
              <div className="tile"><span className="label">{t("onTrial")}</span><span className="value">{number(s.plans.onTrial)}</span><span className="sub">{t("trialEnding", { count: s.plans.trialEnding7d })}</span></div>
              <div className="tile"><span className="label">{t("trialOver")}</span><span className="value">{number(s.plans.trialEndedFree)}</span><span className="sub">{t("trialOverSub")}</span></div>
              <div className="tile"><span className="label">{t("paidPlans")}</span><span className="value">{number(paid)}</span><span className="sub">{Object.entries(s.plans.byPlan).filter(([p]) => p !== "free").map(([p, n]) => `${p} ${n}`).join(" · ") || t("none")}</span></div>
              <div className="tile"><span className="label">{t("paused")}</span><span className="value">{number(s.medicines.pausedByPlan)}</span><span className="sub">{t("pausedSub", { parents: s.parents.pausedByPlan })}</span></div>
            </div>

            <h2>{t("whatsappTitle")}</h2>
            <div className="tiles">
              <div className="tile wide"><span className="label">{t("messages")}</span><span className="value">{number(s.whatsapp7d.utility + s.whatsapp7d.marketing)}</span><span className="sub">{t("messagesSub", { utility: s.whatsapp7d.utility, marketing: s.whatsapp7d.marketing })}</span><span className="sub">{t("fee", { rupees: number(fee) })}</span></div>
            </div>
            <p className="note">{t("note", { time: new Intl.DateTimeFormat(locale === "hi" ? "hi-IN" : "en-IN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(s.generatedAt)) })}</p>
            <FeedbackInbox />
          </>
        );
      })()}
    </main>
  );
}
