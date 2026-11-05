import { useTranslations } from "use-intl";
import { Field } from "../App";
import type { Recipient } from "../lib/api";
import { receiverName, receiverOf, type ReceiverMode } from "../lib/reminderReceiver";
import "./reminderReceiver.css";

/** Suggestions for a helper's relation (the family may type anything short). */
const RELATION_HINTS = ["maid", "attendant", "nurse", "nanny", "husband", "wife", "Nani", "Dadi", "Mummy", "Papa"];

export function ReceiverChoice({ mode, onMode, call, otherName, helperName, helperRelation, onHelperName, onHelperRelation, nameError }: {
  mode: ReceiverMode; onMode: (mode: ReceiverMode) => void; call: string; otherName: string;
  helperName: string; helperRelation: string; onHelperName: (v: string) => void; onHelperRelation: (v: string) => void; nameError?: string;
}) {
  const t = useTranslations("profile");
  const options: ReceiverMode[] = mode === "other" ? ["person", "other", "caregiver", "helper"] : ["person", "caregiver", "helper"];
  const label = (m: ReceiverMode) => m === "person" ? t("receiverPerson", { call }) : m === "caregiver" ? t("receiverMe") : m === "helper" ? t("receiverHelper") : otherName;
  return (
    <>
      <div className="field receiver-choice">
        <span className="field-label" id="f-receiver-label">{t("receiverTitle")}</span>
        <div className="choice-chips wrap" role="radiogroup" aria-labelledby="f-receiver-label">
          {options.map((m) => (
            <button key={m} type="button" role="radio" aria-checked={mode === m} className={mode === m ? "selected" : ""} onClick={() => onMode(m)}>{label(m)}</button>
          ))}
        </div>
        <p className="hint">{mode === "other" ? t("receiverHintOther", { call, name: otherName }) : t(mode === "person" ? "receiverHintPerson" : mode === "caregiver" ? "receiverHintMe" : "receiverHintHelper", { call })}</p>
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
