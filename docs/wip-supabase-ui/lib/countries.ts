// Dial codes for the markets we expect: India plus the main NRI destinations.
export type Country = { iso: string; dial: string; name: string };

export const COUNTRIES: Country[] = [
  { iso: "IN", dial: "+91", name: "India" },
  { iso: "US", dial: "+1", name: "United States" },
  { iso: "GB", dial: "+44", name: "United Kingdom" },
  { iso: "AE", dial: "+971", name: "UAE" },
  { iso: "CA", dial: "+1", name: "Canada" },
  { iso: "AU", dial: "+61", name: "Australia" },
  { iso: "SG", dial: "+65", name: "Singapore" },
  { iso: "SA", dial: "+966", name: "Saudi Arabia" },
  { iso: "QA", dial: "+974", name: "Qatar" },
  { iso: "KW", dial: "+965", name: "Kuwait" },
  { iso: "OM", dial: "+968", name: "Oman" },
  { iso: "BH", dial: "+973", name: "Bahrain" },
  { iso: "NZ", dial: "+64", name: "New Zealand" },
  { iso: "DE", dial: "+49", name: "Germany" },
  { iso: "NL", dial: "+31", name: "Netherlands" },
  { iso: "IE", dial: "+353", name: "Ireland" },
];

export const E164 = /^\+[1-9]\d{7,14}$/;

/** Split an E.164 number into a known country and the national part. Defaults to India. */
export function splitE164(value: string): { iso: string; national: string } {
  const match = [...COUNTRIES]
    .sort((a, b) => b.dial.length - a.dial.length)
    .find((c) => value.startsWith(c.dial));
  return match ? { iso: match.iso, national: value.slice(match.dial.length) } : { iso: "IN", national: "" };
}

export function toE164(iso: string, national: string): string {
  const dial = COUNTRIES.find((c) => c.iso === iso)?.dial ?? "+91";
  const digits = national.replace(/\D/g, "").replace(/^0+/, "");
  return digits ? `${dial}${digits}` : "";
}
