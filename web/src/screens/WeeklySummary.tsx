import { useEffect, useState } from "react";
import "./weeklySummary.css";
import { useLocale, useTranslations } from "use-intl";
import { parentCall } from "../App";
import type { Recipient } from "../lib/api";
import { formatClock, formatDate, type Language } from "../lib/format";
import { doseText, plainDate } from "../lib/vaccines";
import { loadWeekSummary, showGivers, weekIsQuiet, type WeekGiver, type WeekSummary } from "../lib/weeklySummary";

/*
 * "This week" on the Family tab (feature F3, docs/weekly-summary.md). Read-only, the same numbers as the Sunday WhatsApp summary.
 * Not shown on the caregiver's own "Me" profile (no summary is sent about it either).
 */

const day = (iso: string, language: Language, withWeekday = false) =>
  formatDate(plainDate(iso), language, { ...(withWeekday ? { weekday: "short" } : {}), day: "numeric", month: "short", timeZone: "UTC" });

export function WeeklyCard({ recipient }: { recipient: Recipient | null }) {
  const t = useTranslations("weekly");
  const tv = useTranslations("vaccines");
  const language = useLocale() as Language;
  const recipientId = recipient?.id;
  const self = !!recipient?.is_self;
  const [week, setWeek] = useState<WeekSummary | null>(null);
  useEffect(() => {
    if (!recipientId || self) return;   // a week of another person is never shown (checked below)
    let cancelled = false;
    loadWeekSummary(recipientId).then((w) => { if (!cancelled) setWeek(w); }).catch(() => {});
    return () => { cancelled = true; };
  }, [recipientId, self]);
  if (!recipient || self || !week || week.recipientId !== recipient.id) return null;
  const call = parentCall(recipient, language);
  const giverName = (g: WeekGiver) => g.kind === "you" ? t("you") : g.kind === "person" ? call : g.kind === "family" || !g.name ? t("family") : g.name;
  const missedByMedicine = new Map<string, string[]>();
  for (const m of week.missed) {
    const list = missedByMedicine.get(m.medicine) ?? [];
    list.push(`${formatDate(plainDate(m.day), language, { weekday: "short", timeZone: "UTC" })} ${t(`part.${m.part}`)}`);
    missedByMedicine.set(m.medicine, list);
  }
  const quiet = weekIsQuiet(week);
  const percent = week.total > 0 ? Math.round((week.confirmed / week.total) * 100) : 0;
  return (
    <section className="weekly-card" aria-labelledby="weekly-title">
      <header>
        <h2 id="weekly-title">{t("title")}{week.pregnancyWeek ? <em> · {t("pregnancyWeek", { n: week.pregnancyWeek })}</em> : null}</h2>
        <small>{t("period", { from: day(week.from, language), to: day(week.to, language) })}</small>
      </header>
      {quiet && <p className="weekly-quiet">{t("quiet", { call })}</p>}
      {week.total > 0 && (
        <div className="weekly-score">
          <strong>{t("confirmed", { confirmed: week.confirmed, total: week.total })}</strong>
          <span className="weekly-bar" aria-hidden="true"><span style={{ width: `${percent}%` }} /></span>
        </div>
      )}
      <dl>
        {missedByMedicine.size > 0 && <><dt>{t("missed")}</dt><dd>{[...missedByMedicine].map(([med, times]) => `${med} ${times.join(", ")}`).join("; ")}</dd></>}
        {showGivers(week) && <><dt>{t("givenBy")}</dt><dd>{week.givers.map((g) => `${giverName(g)} ${g.count}`).join(" · ")}</dd></>}
        {week.checkinsTotal > 0 && <><dt>{t("checkins")}</dt><dd>{t("checkinsValue", { answered: week.checkinsAnswered, total: week.checkinsTotal })}</dd></>}
        {week.refills.length > 0 && <><dt>{t("refill")}</dt><dd>{week.refills.map((r) => r.left > 0 ? t("left", { medicine: r.medicine, n: r.left }) : t("noneLeft", { medicine: r.medicine })).join(", ")}</dd></>}
        {week.nextVisit && <><dt>{t("nextVisit")}</dt><dd>{week.nextVisit.doctor}, {formatDate(new Date(week.nextVisit.at), language, { weekday: "short", day: "numeric", month: "short", timeZone: week.zone })}, {formatClock(new Date(week.nextVisit.at), week.zone, language)}</dd></>}
        {week.nextVaccine && <><dt>{t("nextVaccine")}</dt><dd>{language === "hi" && week.nextVaccine.hi ? week.nextVaccine.hi : week.nextVaccine.en}{week.nextVaccine.dose ? ` · ${doseText(tv, week.nextVaccine.dose)}` : ""}, {day(week.nextVaccine.due, language, true)}</dd></>}
      </dl>
      <p className="weekly-note">{t("note")}</p>
    </section>
  );
}
