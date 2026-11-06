import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "use-intl";
import { HEARD_FROM, loadReferralStats, type HeardFrom, type ReferralStats } from "../lib/referral";
import "./referral.css";

/** The one optional question on the first "Add your parent" form. One tap picks an answer; a second tap on it takes it back. */
export function HeardFromQuestion({ value, onChange }: { value: HeardFrom | null; onChange: (answer: HeardFrom | null) => void }) {
  const t = useTranslations("referral");
  return (
    <fieldset className="field heard-from">
      <legend className="field-label">{t("question")}</legend>
      <div className="choice-chips wrap">
        {HEARD_FROM.map((answer) => (
          <button type="button" key={answer} className={value === answer ? "selected" : ""} aria-pressed={value === answer}
            onClick={() => onChange(value === answer ? null : answer)}>{t(`answers.${answer}`)}</button>
        ))}
      </div>
      <p className="hint">{t("hint")}</p>
    </fieldset>
  );
}

/** Pilot stats (admins only): sign-ups per doctor code and per answer. Counts only; nothing here goes to a doctor. */
export function ReferralStatsSection() {
  const t = useTranslations("referral");
  const locale = useLocale();
  const [stats, setStats] = useState<ReferralStats | "error" | null>(null);
  useEffect(() => {
    let cancelled = false;
    loadReferralStats().then((s) => { if (!cancelled) setStats(s); }).catch(() => { if (!cancelled) setStats("error"); });
    return () => { cancelled = true; };
  }, []);
  if (!stats || stats === "error") return null;   // an older backend without the counts: the rest of the page still works
  const number = (n: number) => n.toLocaleString(locale === "hi" ? "hi-IN" : "en-IN");
  const direct = stats.total - stats.withCode;
  return (
    <section className="referral-stats" aria-label={t("statsTitle")}>
      <h2>{t("statsTitle")}</h2>
      <p className="note">{t("statsSub", { withCode: number(stats.withCode), direct: number(direct) })}</p>
      {stats.byCode.length === 0 ? <p className="note">{t("statsNoCodes")}</p> : (
        <table>
          <thead><tr><th scope="col">{t("colCode")}</th><th scope="col">{t("colSignups")}</th><th scope="col">{t("colWithPerson")}</th><th scope="col">{t("colAgreed")}</th></tr></thead>
          <tbody>{stats.byCode.map((c) => (
            <tr key={c.code}><td>{c.code}</td><td>{number(c.signups)}</td><td>{number(c.withPerson)}</td><td>{number(c.agreed)}</td></tr>
          ))}</tbody>
        </table>
      )}
      <h2>{t("heardTitle")}</h2>
      <table>
        <thead><tr><th scope="col">{t("colAnswer")}</th><th scope="col">{t("colSignups")}</th></tr></thead>
        <tbody>{stats.byHeardFrom.map((h) => (
          <tr key={h.answer}><td>{h.answer === "not_answered" ? t("notAnswered") : t(`answers.${h.answer}`)}</td><td>{number(h.signups)}</td></tr>
        ))}</tbody>
      </table>
    </section>
  );
}
