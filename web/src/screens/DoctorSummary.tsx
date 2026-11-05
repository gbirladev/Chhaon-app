import { useEffect, useMemo, useState } from "react";
import "./doctorSummary.css";
import { createTranslator, useLocale, useTranslations } from "use-intl";
import { AppHeader, Field, Icon, parentCall, shareContent } from "../App";
import { MESSAGES } from "../i18n";
import { loadAdherence, type Adherence } from "../lib/adherence";
import { CLINIC_MAX, DOCTOR_MAX, cleanLine, cleanTyped, loadDoctorAddress, saveDoctorAddress, withoutTitle, type DoctorAddress } from "../lib/doctorAddress";
import { describeTimes, formatDate, localizeDose, type Language, type T } from "../lib/format";
import type { Medicine, Recipient } from "../lib/api";
import { isChild } from "../lib/vaccines";
import { todayOn, yearsSince } from "../lib/keyFacts";
import { factLines, useChildInfo } from "../lib/childFacts";
import { KeyFactLines } from "./KeyFacts";

const DAYS = 30;
const INSTRUCTION_LABEL: Record<string, string> = { after_food: "add.afterFood", before_food: "add.beforeFood", with_food: "add.withFood" };
/** Rounded, but never 100% while a dose is unconfirmed and never 0% while one is confirmed: the doctor reads this number. */
const percent = (confirmed: number, total: number) => {
  if (!total || !confirmed) return 0;
  if (confirmed >= total) return 100;
  return Math.min(99, Math.max(1, Math.round((confirmed * 100) / total)));
};

/**
 * One page for the doctor: who the patient is, the medicines now, and what the parent confirmed over the last 30 days.
 * It is built only from records the family already has. It never interprets them ("poor", "good") and never advises:
 * the doctor reads the numbers. The sheet is English by default (what doctors read); Hindi is one tap away.
 */
export function DoctorSummaryScreen({ go, recipient, medicines }: { go: (screen: "family") => void; recipient: Recipient | null; medicines: Medicine[] }) {
  const t = useTranslations("doctorSummary");
  const tApp = useTranslations();
  const appLanguage = useLocale() as Language;
  const [sheetLanguage, setSheetLanguage] = useState<Language>("en");
  const [today] = useState(() => new Date());
  // The result is stored with the parent it belongs to, so a different parent never shows the previous one's history.
  const [loaded, setLoaded] = useState<{ id: string; adherence: Adherence | null } | null>(null);
  // Optional: the family's own doctor, so the sheet reads "Prepared for: Dr ..., ...". Kept per parent on this device.
  const [address, setAddress] = useState<{ id: string; value: DoctorAddress } | null>(null);

  // While this screen is open, printing shows only the sheet (see doctorSummary.css).
  useEffect(() => {
    document.documentElement.classList.add("ds-print");
    return () => document.documentElement.classList.remove("ds-print");
  }, []);

  useEffect(() => {
    if (!recipient) return;
    let cancelled = false;
    loadAdherence(recipient, DAYS)
      .then((adherence) => { if (!cancelled) setLoaded({ id: recipient.id, adherence }); })
      .catch(() => { if (!cancelled) setLoaded({ id: recipient.id, adherence: null }); });
    return () => { cancelled = true; };
  }, [recipient?.id]);  // eslint-disable-line react-hooks/exhaustive-deps

  const ts = useMemo(() => createTranslator({ locale: sheetLanguage, messages: MESSAGES[sheetLanguage] }) as unknown as T, [sheetLanguage]);
  const child = useChildInfo(recipient);
  if (!recipient) return null;

  const current = loaded?.id === recipient.id ? loaded : null;
  const adherence = current?.adherence ?? null;
  const failed = !!current && !current.adherence;
  const sd = (key: string, values?: Record<string, string | number>) => ts(`doctorSummary.${key}`, values);
  const unknown = ts("emergency.unknown");
  const active = medicines.filter((m) => m.active);
  // The same key facts as the emergency card, in the sheet's language: pregnancy week + due date, a child's birth date, age, weight.
  const facts = factLines(recipient, child.info, (key, values) => ts(`facts.${key}`, values), sheetLanguage);
  const years = isChild(recipient) ? null : recipient.age ?? yearsSince(recipient.dob, todayOn(recipient.timezone));
  const ageCity = [years !== null ? ts("emergency.years", { count: years }) : null, recipient.city].filter(Boolean).join(" • ");
  const prepared = sd("prepared", { date: formatDate(today, sheetLanguage, { day: "numeric", month: "short", year: "numeric" }) });
  // Stored as a key ("after_food"); shown in the sheet's language. Anything else is shown as it was typed.
  const instruction = (value: string | null) => (value ? (INSTRUCTION_LABEL[value] ? ts(INSTRUCTION_LABEL[value]) : value) : "");
  const medicineLine = (name: string, dose: string | null) => [name, dose && localizeDose(dose, ts)].filter(Boolean).join(" ");
  // Until the family edits it, read what this device last used (or the profile's first doctor) for this parent.
  const typed = address?.id === recipient.id ? address.value : loadDoctorAddress(recipient.id, recipient.doctor_contacts);
  const editAddress = (field: keyof DoctorAddress, raw: string) => {
    const value = { ...typed, [field]: cleanTyped(raw, field === "doctor" ? DOCTOR_MAX : CLINIC_MAX) };
    setAddress({ id: recipient.id, value });
    saveDoctorAddress(recipient.id, value);
  };
  const doctor = withoutTitle(cleanLine(typed.doctor, DOCTOR_MAX));
  const clinic = cleanLine(typed.clinic, CLINIC_MAX);
  const preparedFor = doctor && clinic ? sd("preparedForBoth", { doctor, clinic })
    : doctor ? sd("preparedForDoctor", { doctor }) : clinic ? sd("preparedForClinic", { clinic }) : "";
  const hasHistory = !!adherence && adherence.total > 0;
  const overall = adherence ? sd("overall", { confirmed: adherence.confirmed, total: adherence.total, percent: percent(adherence.confirmed, adherence.total) }) : "";

  const shareText = [
    `${sd("sheetTitle")} — ${recipient.name}`,
    ...(preparedFor ? [preparedFor] : []),
    ...facts,
    ageCity,
    `${ts("emergency.bloodGroup")}: ${recipient.blood_group ?? unknown}`,
    `${ts("emergency.conditions")}: ${recipient.conditions.join(", ") || unknown}`,
    `${ts("emergency.allergies")}: ${recipient.allergies.join(", ") || unknown}`,
    "",
    `${sd("medicinesNow")}:`,
    ...(active.length ? active.map((m) => `- ${medicineLine(m.name, m.dose)} (${describeTimes(m.times, ts, sheetLanguage)})${m.instructions ? ` — ${instruction(m.instructions)}` : ""}`) : [sd("noMedicines")]),
    "",
    `${sd("lastDays", { days: DAYS })}:`,
    ...(hasHistory && adherence
      ? [overall, ...adherence.medicines.map((m) => `- ${medicineLine(m.name, m.dosage)}: ${sd("count", { confirmed: m.confirmed, total: m.total })}`),
         ...(adherence.checkinsTotal ? [sd("checkins", { answered: adherence.checkinsAnswered, total: adherence.checkinsTotal })] : [])]
      : [sd("noHistory")]),
    "",
    sd("note", { name: recipient.name }),
    prepared,
  ].join("\n");

  const print = () => {
    // "Save as PDF" names the file after the page title.
    const previous = document.title;
    document.title = `${recipient.name} - ${sd("sheetTitle")}`;
    const restore = () => { document.title = previous; window.removeEventListener("afterprint", restore); };
    window.addEventListener("afterprint", restore);
    window.print();
  };

  return (
    <main className="scroll-page no-nav">
      <AppHeader title={t("title")} subtitle={t("subtitle", { call: parentCall(recipient, appLanguage) })} back={() => go("family")} />

      <div className="ds-lang" role="group" aria-label={t("language")}>
        <span>{t("language")}</span>
        {(["en", "hi"] as const).map((language) => (
          <button key={language} type="button" aria-pressed={sheetLanguage === language} onClick={() => setSheetLanguage(language)}>
            {t(language === "en" ? "english" : "hindi")}
          </button>
        ))}
      </div>

      <section className="ds-for" aria-labelledby="ds-for-title">
        <h2 id="ds-for-title">{t("forTitle")}</h2>
        <p className="hint">{t("forHint")}</p>
        <Field id="ds-doctor" label={t("doctorName")}>
          <input id="ds-doctor" autoComplete="off" maxLength={DOCTOR_MAX} placeholder={t("doctorPlaceholder")} value={typed.doctor} onChange={(event) => editAddress("doctor", event.target.value)} />
        </Field>
        <Field id="ds-clinic" label={t("clinicName")}>
          <input id="ds-clinic" autoComplete="off" maxLength={CLINIC_MAX} placeholder={t("clinicPlaceholder")} value={typed.clinic} onChange={(event) => editAddress("clinic", event.target.value)} />
        </Field>
      </section>

      <article className="ds-sheet" lang={sheetLanguage}>
        <header>
          <p className="ds-kicker">{sd("sheetTitle")}</p>
          <h2>{recipient.name}</h2>
          <KeyFactLines lines={facts} className="ds-key-facts" />
          {ageCity && <p className="ds-sub">{ageCity}</p>}
          {preparedFor && <p className="ds-for-line">{preparedFor}</p>}
        </header>

        <dl className="ds-facts">
          <div><dt>{ts("emergency.bloodGroup")}</dt><dd>{recipient.blood_group ?? unknown}</dd></div>
          <div><dt>{ts("emergency.conditions")}</dt><dd>{recipient.conditions.join(", ") || unknown}</dd></div>
          <div><dt>{ts("emergency.allergies")}</dt><dd>{recipient.allergies.join(", ") || unknown}</dd></div>
        </dl>

        <section aria-labelledby="ds-meds">
          <h3 id="ds-meds">{sd("medicinesNow")}</h3>
          {active.length === 0 && <p className="ds-muted">{sd("noMedicines")}</p>}
          <ul className="ds-meds">
            {active.map((m) => (
              <li key={m.id}>
                <strong>{medicineLine(m.name, m.dose)}</strong>
                <span>{describeTimes(m.times, ts, sheetLanguage)}</span>
                {m.instructions && <small>{instruction(m.instructions)}</small>}
              </li>
            ))}
          </ul>
        </section>

        <section aria-labelledby="ds-history">
          <h3 id="ds-history">{sd("lastDays", { days: DAYS })}</h3>
          {failed && <p role="alert" className="form-error">{t("loadFailed")}</p>}
          {!adherence && !failed && <div className="skeleton card" aria-busy="true" />}
          {adherence && !hasHistory && <p className="ds-muted">{sd("noHistory")}</p>}
          {adherence && hasHistory && (
            <>
              <p className="ds-overall"><strong>{overall}</strong></p>
              <h4>{sd("byMedicine")}</h4>
              <ul className="ds-rows">
                {adherence.medicines.map((m) => (
                  <li key={m.medicineId}>
                    <div className="ds-row-head">
                      <span>{medicineLine(m.name, m.dosage)}{!m.active && <em> ({sd("notActive")})</em>}</span>
                      <strong>{sd("count", { confirmed: m.confirmed, total: m.total })}</strong>
                    </div>
                    <div className="ds-bar" aria-hidden="true"><i style={{ width: `${percent(m.confirmed, m.total)}%` }} /></div>
                  </li>
                ))}
              </ul>
              {adherence.checkinsTotal > 0 && <p className="ds-checkins">{sd("checkins", { answered: adherence.checkinsAnswered, total: adherence.checkinsTotal })}</p>}
            </>
          )}
        </section>

        <footer>
          <p className="ds-note">{sd("note", { name: recipient.name })}</p>
          <p className="ds-prepared">{prepared}</p>
        </footer>
      </article>

      <div className="ds-actions">
        <button className="primary-button" type="button" onClick={print}><Icon name="report" size={18} /> {t("print")}</button>
        <button className="secondary-button" type="button" onClick={() => void shareContent(t("shareTitle", { name: recipient.name }), shareText, tApp)}><Icon name="share" size={18} /> {t("share")}</button>
      </div>
    </main>
  );
}
