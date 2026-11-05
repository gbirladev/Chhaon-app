import { useTranslations } from "use-intl";
import { Field } from "../App";
import type { Recipient } from "../lib/api";
import type { CareType } from "../lib/pregnancy";
import { receiverName, receiverOf, type ReceiverMode } from "../lib/reminderReceiver";
import "./reminderReceiver.css";

/** Suggestions for a helper's relation (the family may type anything short). */
const RELATION_HINTS = ["maid", "attendant", "nurse", "nanny", "husband", "wife", "Nani", "Dadi", "Mummy", "Papa"];

/**
 * The choice on the person form. `call` is what the family calls the person ("" while no name is typed: the chip then reads
 * `chip`, "them" / "Her", and the hints use their wording without a name). A child never gets the reminders: no "person" option.
 */
export function ReceiverChoice({ mode, onMode, call, chip, careType = "older", otherName, helperName, helperRelation, onHelperName, onHelperRelation, nameError }: {
  mode: ReceiverMode; onMode: (mode: ReceiverMode) => void; call: string; chip: string; careType?: CareType; otherName: string;
  helperName: string; helperRelation: string; onHelperName: (v: string) => void; onHelperRelation: (v: string) => void; nameError?: string;
}) {
  const t = useTranslations("profile");
  const child = careType === "child";
  const named = call.trim() !== "";
  const base: ReceiverMode[] = child ? ["caregiver", "helper"] : ["person", "caregiver", "helper"];
  const options: ReceiverMode[] = mode === "other" ? [...base.slice(0, child ? 0 : 1), "other", "caregiver", "helper"] : base;
  const label = (m: ReceiverMode) => m === "person" ? (named ? t("receiverPerson", { call }) : chip) : m === "caregiver" ? t("receiverMe") : m === "helper" ? t("receiverHelper") : otherName;
  const pregnant = careType === "pregnant";
  const hintKey = mode === "person" ? (named ? (pregnant ? "receiverHintPersonPregnant" : "receiverHintPerson") : pregnant ? "receiverHintPersonNoNamePregnant" : "receiverHintPersonNoName")
    : mode === "caregiver" ? (child ? (named ? "receiverHintMeChild" : "receiverHintMeChildNoName") : named ? "receiverHintMe" : "receiverHintMeNoName")
    : child ? "receiverHintHelperChild" : "receiverHintHelper";
  return (
    <>
      <div className="field receiver-choice">
        <span className="field-label" id="f-receiver-label">{t("receiverTitle")}</span>
        <div className="choice-chips wrap" role="radiogroup" aria-labelledby="f-receiver-label">
          {options.map((m) => (
            <button key={m} type="button" role="radio" aria-checked={mode === m} className={mode === m ? "selected" : ""} onClick={() => onMode(m)}>{label(m)}</button>
          ))}
        </div>
        <p className="hint">{mode === "other" ? t("receiverHintOther", { call: named ? call : chip, name: otherName }) : t(hintKey, { call })}</p>
      </div>
      {mode === "helper" && (
        <div className="two-col">
          <Field id="f-helperName" label={t("helperName")} error={nameError}>
            <input id="f-helperName" name="helperName" autoComplete="off" maxLength={60} placeholder={t("helperNamePlaceholder")} value={helperName}
              aria-invalid={nameError ? true : undefined} aria-describedby={nameError ? "f-helperName-error" : undefined} onChange={(event) => onHelperName(event.target.value)} />
          </Field>
          <Field id="f-helperRelation" label={t("helperRelation")}>
            <input id="f-helperRelation" name="helperRelation" list="helper-relations" autoComplete="off" maxLength={30} placeholder={t("helperRelationPlaceholder")} value={helperRelation} onChange={(event) => onHelperRelation(event.target.value)} />
            <datalist id="helper-relations">{RELATION_HINTS.map((r) => <option key={r} value={r} />)}</datalist>
          </Field>
        </div>
      )}
    </>
  );
}

/** "Daily 'How are you?' check-in" on the person form (only when the person gets the reminders themself; migration 0050). */
export function CheckinSwitch({ on, onChange, pregnant }: { on: boolean; onChange: (on: boolean) => void; pregnant: boolean }) {
  const t = useTranslations("profile");
  return (
    <label className="toggle-row checkin-switch">
      <span><strong>{t("checkinSwitch")}</strong><small>{on ? t(pregnant ? "checkinSwitchOnPregnant" : "checkinSwitchOn") : t("checkinSwitchOff")}</small></span>
      <input type="checkbox" role="switch" checked={on} onChange={(event) => onChange(event.target.checked)} /><i className={on ? "" : "off"} />
    </label>
  );
}

/** "Language for Sunita's messages": shown only when a helper or a family member gets the reminders (migration 0050). */
export function ReceiverLanguageField({ value, onChange, mode, name }: { value: "en" | "hi"; onChange: (v: "en" | "hi") => void; mode: ReceiverMode; name: string }) {
  const t = useTranslations("profile");
  const label = mode === "caregiver" ? t("receiverLanguageMe") : name.trim() ? t("receiverLanguage", { name: name.trim() }) : t("receiverLanguageThem");
  return (
    <Field id="f-reminderLanguage" label={label} hint={t("receiverLanguageHint")}>
      <select id="f-reminderLanguage" name="reminderLanguage" value={value} onChange={(event) => onChange(event.target.value === "en" ? "en" : "hi")}>
        <option value="hi">{t("langHindi")}</option><option value="en">{t("langEnglish")}</option>
      </select>
    </Field>
  );
}

/** One line on Today / Family: "WhatsApp reminders go to Sunita (maid)" (nothing when the person gets them). */
export function ReceiverNote({ recipient, userId, call }: { recipient: Recipient | null; userId: string | null; call: string }) {
  const t = useTranslations("today");
  const r = receiverOf(recipient);
  if (!recipient || recipient.is_self || r.reminder_to === "person") return null;
  const you = r.reminder_to === "caregiver" && r.reminder_user_id === userId;
  return (
    <p className="receiver-note" role="note">
      {you ? t("receiverYou", { call }) : t("receiverOther", { call, name: receiverName(r) || t("receiverSomeone") })}
    </p>
  );
}
