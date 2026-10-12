
import { useState } from "react";
import { COUNTRIES, splitE164, toE164 } from "@/lib/countries";

/** Country-code picker + number. `value` / `onChange` use E.164 (e.g. +919876543210) or "". */
export function PhoneField({
  id,
  value,
  onChange,
  placeholder,
  countryLabel,
  required,
  autoComplete = "tel-national",
}: {
  id: string;
  value: string;
  onChange: (e164: string) => void;
  placeholder?: string;
  countryLabel: string;
  required?: boolean;
  autoComplete?: string;
}) {
  const initial = splitE164(value);
  const [iso, setIso] = useState(initial.iso);
  const [national, setNational] = useState(initial.national);

  return (
    <div className="phone-input">
      <select
        aria-label={countryLabel}
        value={iso}
        onChange={(e) => { setIso(e.target.value); onChange(toE164(e.target.value, national)); }}
      >
        {COUNTRIES.map((c) => (
          <option key={c.iso} value={c.iso}>{c.iso} {c.dial}</option>
        ))}
      </select>
      <input
        id={id}
        type="tel"
        inputMode="tel"
        autoComplete={autoComplete}
        placeholder={placeholder}
        required={required}
        value={national}
        onChange={(e) => { setNational(e.target.value); onChange(toE164(iso, e.target.value)); }}
      />
    </div>
  );
}
