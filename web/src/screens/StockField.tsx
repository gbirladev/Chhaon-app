import { useState } from "react";
import { useTranslations } from "use-intl";
import "./stockField.css";

const DEVANAGARI_DIGITS = "०१२३४५६७८९";

/** Whole number from 0 to max, or null. Accepts Hindi digits; rejects empty, negative, decimals and out-of-range. */
export function parseStock(text: string, max: number): number | null {
  const ascii = text.trim().replace(/[०-९]/g, (d) => String(DEVANAGARI_DIGITS.indexOf(d)));
  if (!/^\d{1,9}$/.test(ascii)) return null;
  const n = Number(ascii);
  return n <= max ? n : null;
}

/** The big stock number, always a number box: type the exact quantity. Enter or leaving the box saves it; whatever you typed stays
 *  on screen, and a value that is not allowed is simply not saved (it shows what is wrong). Escape puts the saved number back. */
export function StockField({ value, max, disabled, onCommit }: { value: number; max: number; disabled?: boolean; onCommit: (next: number) => void }) {
  const t = useTranslations("medicine");
  const [draft, setDraft] = useState<string | null>(null);   // null: nothing typed, show the saved number
  const [error, setError] = useState(false);

  const commit = () => {
    if (draft === null) return;
    const next = parseStock(draft, max);
    if (next === null) { setError(true); return; }          // blocked: the typed text stays, nothing is saved
    setError(false);
    setDraft(null);
    if (next !== value) onCommit(next);
  };

  return (
    <div className="stock-edit">
      <input
        className="stock-input"
        type="text"
        inputMode="numeric"
        pattern="[0-9]*"
        enterKeyHint="done"
        autoComplete="off"
        value={draft ?? String(value)}
        disabled={disabled}
        aria-label={t("stockInput")}
        aria-invalid={error}
        aria-describedby={error ? "stock-error" : undefined}
        onFocus={(e) => e.currentTarget.select()}
        onChange={(e) => { setDraft(e.target.value); setError(false); }}
        onKeyDown={(e) => {
          if (e.key === "Enter") { e.preventDefault(); commit(); }
          else if (e.key === "Escape") { e.preventDefault(); setDraft(null); setError(false); }
        }}
        onBlur={commit}
      />
      {error && <p id="stock-error" className="stock-error" role="alert">{t("errStockNumber", { max })}</p>}
    </div>
  );
}

/** Stock not tracked (the family left the count empty): a neutral line and a box to start counting. Nothing is "out of stock". */
export function StockStart({ max, disabled, onStart }: { max: number; disabled?: boolean; onStart: (count: number) => void }) {
  const t = useTranslations("medicine");
  const [draft, setDraft] = useState("");
  const [error, setError] = useState(false);
  const start = () => {
    const next = parseStock(draft, max);
    if (next === null) { setError(true); return; }
    setError(false);
    onStart(next);
  };
  return (
    <div className="stock-start">
      <p className="form-note">{t("notTrackedHint")}</p>
      <div className="stock-start-row">
        <input className="stock-input" type="text" inputMode="numeric" pattern="[0-9]*" enterKeyHint="done" autoComplete="off" placeholder="30"
          value={draft} disabled={disabled} aria-label={t("stockInput")} aria-invalid={error} aria-describedby={error ? "stock-start-error" : undefined}
          onChange={(e) => { setDraft(e.target.value); setError(false); }}
          onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); start(); } }} />
        <button type="button" className="secondary-button" disabled={disabled || !draft.trim()} onClick={start}>{t("addCount")}</button>
      </div>
      {error && <p id="stock-start-error" className="stock-error" role="alert">{t("errStockNumber", { max })}</p>}
    </div>
  );
}
