/**
 * PREGNANCY: the usual checks and the supplement names, in ONE place so a gynaecologist can read and correct them.
 *
 * NEEDS REVIEW by a gynaecologist before real families are told about it (docs/use-cases.md, build order step 4).
 * Based on common Indian antenatal practice (MoHFW: at least 4 antenatal visits; PMSMA; Anemia Mukt Bharat; NHM). There
 * is no official per-visit test list, so the app shows these as "Common checks — confirm with your doctor". Nothing is
 * added automatically: "+ Add" only opens a NEW appointment with the name and a suggested date for the family to edit.
 *
 * Weeks are completed weeks of pregnancy (from the due date: week = 40 - weeks until due). `from`/`to` are inclusive.
 * A check without weeks (BP and weight) is shown as "at every visit" without an Add button.
 *
 * Wording rules (medical safety): names only; no doses, no advice, no diagnosis, nothing about the sex of the baby.
 */
export type UsualCheck = {
  id: string;
  /** Name shown in the list and written into the appointment notes, per language. */
  name: { en: string; hi: string };
  /** First and last week of the usual window; omitted = at every visit. */
  from?: number;
  to?: number;
};

export const USUAL_CHECKS: UsualCheck[] = [
  { id: "booking", name: { en: "First visit (booking) and blood tests", hi: "पहली जाँच (रजिस्ट्रेशन) और खून की जाँचें" }, from: 6, to: 12 },
  { id: "dating", name: { en: "Early (dating) scan", hi: "शुरुआती (डेटिंग) स्कैन" }, from: 6, to: 10 },
  { id: "nt", name: { en: "NT scan", hi: "NT स्कैन" }, from: 11, to: 14 },
  { id: "anomaly", name: { en: "Anomaly scan", hi: "एनॉमली स्कैन" }, from: 18, to: 22 },
  { id: "gtt", name: { en: "Sugar test (GTT)", hi: "शुगर की जाँच (GTT)" }, from: 24, to: 28 },
  { id: "growth", name: { en: "Growth scan", hi: "ग्रोथ स्कैन" }, from: 28, to: 36 },
  { id: "bp", name: { en: "BP and weight", hi: "BP और वज़न" } },
];

/**
 * Supplement NAMES offered as one-tap chips on the add-medicine screen of a pregnancy. They fill only the name: dose,
 * times and everything else are typed from the prescription ("Use the dose your doctor prescribed").
 * Iron + folic acid (Anemia Mukt Bharat), calcium + vitamin D3 (NHM antenatal package), folic acid (early pregnancy).
 * The name goes into the WhatsApp reminder, so it is written in the PERSON's message language.
 */
export const SUPPLEMENT_NAMES: { en: string; hi: string }[] = [
  { en: "Iron + folic acid", hi: "आयरन + फ़ोलिक एसिड" },
  { en: "Calcium + vitamin D3", hi: "कैल्शियम + विटामिन D3" },
  { en: "Folic acid", hi: "फ़ोलिक एसिड" },
];
