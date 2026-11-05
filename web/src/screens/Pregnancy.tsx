import { useTranslations } from "use-intl";
import "./pregnancy.css";
import type { Recipient } from "../lib/api";
import { formatDate, type Language } from "../lib/format";
import { pregnancyStatus, suggestDate, todayIn, type CareType } from "../lib/pregnancy";
import { SUPPLEMENT_NAMES, USUAL_CHECKS } from "../lib/pregnancyChecks";

/** A "YYYY-MM-DD" date as "14 Mar 2027" in the app's language (the date has no time zone of its own). */
const showDate = (ymd: string, language: Language) =>
  formatDate(new Date(`${ymd}T12:00:00Z`), language, { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });

/** "Who are you caring for?" on the add / edit person form. Child is shown as coming soon (not selectable). */
export function CareTypePicker({ value, onChange }: { value: CareType; onChange: (value: CareType) => void }) {
  const t = useTranslations("profile");
  const options: { type: CareType; emoji: string; label: string; soon?: boolean }[] = [
    { type: "older", emoji: "👴", label: t("careOlder") },
    { type: "pregnant", emoji: "🤰", label: t("carePregnant") },
    { type: "child", emoji: "👶", label: t("careChild") },
  ];
  return (
    <div className="field care-type">
      <p className="field-label" id="f-careType-label">{t("careType")}</p>
      <div className="care-type-options" role="radiogroup" aria-labelledby="f-careType-label">
        {options.map((o) => (
          <button key={o.type} type="button" role="radio" aria-checked={value === o.type} disabled={o.soon}
            className={`care-type-option${value === o.type ? " selected" : ""}${o.soon ? " soon" : ""}`}
            onClick={() => { if (!o.soon) onChange(o.type); }}>
            <span className="care-type-emoji" aria-hidden="true">{o.emoji}</span>
            <span className="care-type-label">{o.label}</span>
            {o.soon && <small>{t("careSoon")}</small>}
          </button>
        ))}
      </div>
    </div>
  );
}

/** Today: "Week 24 · 2nd trimester · 112 days to go (due 14 Mar 2027)", counted on the PERSON's clock. */
export function PregnancyCard({ recipient, language, onChecks }: { recipient: Recipient; language: Language; onChecks: () => void }) {
  const t = useTranslations("pregnancy");
  const due = recipient.due_date;
  if (!due) return null;
  const status = pregnancyStatus(due, todayIn(recipient.timezone ?? "Asia/Kolkata"));
  if (!status) return null;
  const date = showDate(due, language);
  const { week, trimester, daysToGo } = status;
  const line = week === null
    ? t("dueOnly", { date })
    : daysToGo > 0
      ? `${t("week", { week })} · ${t(`trimester${trimester ?? 1}`)} · ${t("daysToGo", { count: daysToGo })} ${t("dueParen", { date })}`
      : `${t("week", { week })} · ${daysToGo === 0 ? t("dueToday") : t("duePassed", { date })}`;
  return (
    <section className="pregnancy-card" aria-label={t("cardTitle")}>
      <span className="pregnancy-icon" aria-hidden="true">🤰</span>
      <div className="pregnancy-text">
        <small>{t("cardTitle")}</small>
        <strong>{line}</strong>
        {week !== null && <span className="pregnancy-bar" aria-hidden="true"><i style={{ width: `${Math.min(100, (week / 40) * 100)}%` }} /></span>}
        <button type="button" className="inline-link" onClick={onChecks}>{t("checksLink")}</button>
      </div>
    </section>
  );
}

/** One-tap supplement NAMES on the add-medicine screen of a pregnancy; dose and times stay for the family to type. */
export function SupplementChips({ personLanguage, onPick }: { personLanguage: Language; onPick: (name: string) => void }) {
  const t = useTranslations("pregnancy");
  return (
    <div className="supplement-chips" role="group" aria-labelledby="supplements-label">
      <p className="hint" id="supplements-label">{t("supplementsTitle")}</p>
      <div className="choice-chips wrap">
        {SUPPLEMENT_NAMES.map((s) => {
          const name = personLanguage === "hi" ? s.hi : s.en;
          return <button type="button" key={s.en} onClick={() => onPick(name)}>{name}</button>;
        })}
      </div>
      <p className="form-note">{t("supplementsNote")}</p>
    </div>
  );
}

/** What "+ Add" hands to a NEW appointment form: the check's name and a suggested date inside its window. */
export type CheckPrefill = { notes: string; date: string };

/** "Common checks — confirm with your doctor": a list with week windows; "+ Add" opens a new appointment, never saves one. */
export function UsualChecks({ recipient, uiLanguage, onAdd }: { recipient: Recipient; uiLanguage: Language; onAdd: (prefill: CheckPrefill) => void }) {
  const t = useTranslations("pregnancy");
  const due = recipient.due_date;
  const today = todayIn(recipient.timezone ?? "Asia/Kolkata");
  const week = due ? pregnancyStatus(due, today)?.week ?? null : null;
  return (
    <section className="usual-checks" aria-labelledby="usual-checks-title">
      <h2 id="usual-checks-title">{t("checksTitle")}</h2>
      <p className="form-note">{t("checksNote")}</p>
      {!due && <p className="form-note">{t("needDueDate")}</p>}
      <ul>
        {USUAL_CHECKS.map((check) => {
          const label = uiLanguage === "hi" ? check.name.hi : check.name.en;
          const windowed = check.from !== undefined && check.to !== undefined;
          const date = due && windowed ? suggestDate(check, due, today) : null;
          const now = windowed && week !== null && week >= (check.from ?? 0) && week <= (check.to ?? 0);
          const passed = windowed && !!due && date === null;
          return (
            <li key={check.id} className={`usual-check${passed ? " passed" : ""}${now ? " now" : ""}`}>
              <span className="usual-check-text">
                <strong>{label}</strong>
                <small>
                  {windowed ? t("weeks", { from: check.from ?? 0, to: check.to ?? 0 }) : t("everyVisit")}
                  {now && <em> · {t("windowNow")}</em>}
                  {passed && <em> · {t("windowPast")}</em>}
                </small>
              </span>
              {date && (
                <button type="button" className="secondary-button usual-check-add" aria-label={t("addCheckLabel", { name: label })}
                  onClick={() => onAdd({ notes: label, date })}>
                  + {t("addCheck")}
                </button>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}
