import { useEffect, useRef, useState } from "react";
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

/** The big stock number. Tap it to type an exact quantity: Enter, the tick or leaving the box saves, Escape cancels. */
export function StockField({ value, max, disabled, onCommit }: { value: number; max: number; disabled?: boolean; onCommit: (next: number) => void }) {
  const t = useTranslations("medicine");
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState("");
  const [error, setError] = useState(false);
  const finished = useRef(false);
  const opener = useRef<HTMLButtonElement>(null);
  const wasEditing = useRef(false);

  useEffect(() => {
    if (wasEditing.current && !editing) opener.current?.focus();
    wasEditing.current = editing;
  }, [editing]);

  const open = () => { finished.current = false; setText(String(value)); setError(false); setEditing(true); };
  const cancel = () => { finished.current = true; setError(false); setEditing(false); };
  const save = () => {
    const next = parseStock(text, max);
    if (next === null) { setError(true); return; }
    finished.current = true;
    setEditing(false);
    if (next !== value) onCommit(next);
  };

  if (!editing) {
    return <button type="button" className="stock-number" onClick={open} disabled={disabled} ref={opener} aria-label={`${t("editStock")}: ${value}`} aria-live="polite">{value}</button>;
  }
  return (
    <div className="stock-edit">
      <div className="stock-edit-row">
        <input
          autoFocus
          type="text"
          inputMode="numeric"
          pattern="[0-9]*"
          enterKeyHint="done"
          autoComplete="off"
          value={text}
          aria-label={t("stockInput")}
          aria-invalid={error}
          aria-describedby={error ? "stock-error" : undefined}
          onFocus={(e) => e.currentTarget.select()}
          onChange={(e) => { setText(e.target.value); setError(false); }}
          onKeyDown={(e) => {
            if (e.key === "Enter") { e.preventDefault(); save(); }
            else if (e.key === "Escape") { e.preventDefault(); cancel(); }
          }}
          onBlur={() => { if (!finished.current) save(); }}
        />
        <button type="button" className="stock-save" aria-label={t("saveStock")} onMouseDown={(e) => e.preventDefault()} onClick={save}>✓</button>
        <button type="button" className="stock-cancel" aria-label={t("cancelStock")} onMouseDown={(e) => e.preventDefault()} onClick={cancel}>✕</button>
      </div>
      {error && <p id="stock-error" className="stock-error" role="alert">{t("errStockNumber", { max })}</p>}
    </div>
  );
}
