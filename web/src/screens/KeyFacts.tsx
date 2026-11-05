import { useState, type FormEvent } from "react";
import { useLocale, useTranslations } from "use-intl";
import { Field, useToast } from "../App";
import type { Recipient } from "../lib/api";
import { saveChildWeight, type ChildInfo } from "../lib/vaccines";
import { todayOn } from "../lib/keyFacts";
import "./keyFacts.css";

/** The key facts block (pregnancy week, a child's birth date and weight), on the emergency card and the doctor summary. */
export function KeyFactLines({ lines, className = "" }: { lines: string[]; className?: string }) {
  if (!lines.length) return null;
  return <div className={`key-facts ${className}`.trim()} role="note">{lines.map((line) => <p key={line}>{line}</p>)}</div>;
}

/** Parses "6.8", "6,8" or Hindi digits; null when not a number with at most 2 decimals. */
function parseKg(text: string): number | null {
  const ascii = text.trim().replace(/[०-९]/g, (d) => String("०१२३४५६७८९".indexOf(d))).replace(",", ".");
  if (!/^\d{1,3}(\.\d{1,2})?$/.test(ascii)) return null;
  return Number(ascii);
}

/** A child's weight: a recorded fact (0.5 to 200 kg) and the day it was measured. Shown on the emergency card screen. */
export function ChildWeightEditor({ recipient, info, onSaved }: { recipient: Recipient; info: ChildInfo | null; onSaved: (next: ChildInfo) => void }) {
  if (!info) return null;
  // A new saved value starts the form afresh (no copying props into state in an effect).
  return <WeightForm key={`${info.weightKg ?? ""}|${info.weightOn ?? ""}`} recipient={recipient} info={info} onSaved={onSaved} />;
}

function WeightForm({ recipient, info, onSaved }: { recipient: Recipient; info: ChildInfo; onSaved: (next: ChildInfo) => void }) {
  const t = useTranslations("facts");
  const language = useLocale();
  const toast = useToast();
  const today = todayOn(recipient.timezone);
  const [kgText, setKgText] = useState(info.weightKg != null ? String(info.weightKg) : "");
  const [on, setOn] = useState(info.weightOn ?? today);
  const [error, setError] = useState<"" | "kg" | "date">("");
  const [busy, setBusy] = useState(false);
  const ready = !!info.guardianConfirmedAt && !!info.dob;
  const save = async (event: FormEvent) => {
    event.preventDefault();
    const value = kgText.trim() ? parseKg(kgText) : null;
    if (kgText.trim() && (value === null || value < 0.5 || value > 200)) { setError("kg"); document.getElementById("key-weight-kg")?.focus(); return; }
    if (value !== null && (!on || on > today || (info.dob && on < info.dob))) { setError("date"); document.getElementById("key-weight-on")?.focus(); return; }
    setError("");
    setBusy(true);
    try {
      onSaved(await saveChildWeight(recipient.id, value, value === null ? null : on));
      toast(t(value === null ? "weightCleared" : "weightSaved"));
    } catch {
      toast(t("errWeightSave"));
    } finally {
      setBusy(false);
    }
  };
  return (
    <form className="card key-weight" onSubmit={save} noValidate aria-labelledby="key-weight-title">
      <h2 id="key-weight-title">{t("weightTitle")}</h2>
      {!ready ? <p className="form-note">{t("weightNeedsDob")}</p> : (
        <>
          <p className="form-note">{t("weightHint")}</p>
          <div className="two-col">
            <Field id="key-weight-kg" label={t("weightLabel")} error={error === "kg" ? t("errWeight") : undefined}>
              <input id="key-weight-kg" inputMode="decimal" autoComplete="off" placeholder="6.8" value={kgText} lang={language}
                onChange={(event) => { setKgText(event.target.value); setError(""); }}
                aria-invalid={error === "kg"} aria-describedby={error === "kg" ? "key-weight-kg-error" : undefined} />
            </Field>
            <Field id="key-weight-on" label={t("weightOnLabel")} error={error === "date" ? t("errWeightDate") : undefined}>
              <input id="key-weight-on" type="date" max={today} min={info.dob ?? undefined} value={on}
                onChange={(event) => { setOn(event.target.value); setError(""); }}
                aria-invalid={error === "date"} aria-describedby={error === "date" ? "key-weight-on-error" : undefined} />
            </Field>
          </div>
          <button className="secondary-button" type="submit" disabled={busy}>{busy ? t("saving") : t("weightSave")}</button>
        </>
      )}
    </form>
  );
}
