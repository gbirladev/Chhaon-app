// The login-free emergency card a paramedic opens from a link or QR code. Standalone on purpose: it does not load the
// app, the Supabase session or any caregiver data. Keep it small and fast.
import { useCallback, useEffect, useState } from "react";
import { createTranslator } from "use-intl";
import en from "../messages/en.json";
import hi from "../messages/hi.json";
import { fetchPublicCard, publicLinkToken, type CardResult, type PublicCard } from "../lib/emergencyPublic";
import { keyFactLines, todayOn } from "../lib/keyFacts";
import "./publicEmergency.css";

type Lang = "en" | "hi";
const MESSAGES = { en, hi } as const;

function initialLanguage(): Lang {
  try {
    const saved = JSON.parse(localStorage.getItem("carelink-language") ?? "null");
    if (saved === "en" || saved === "hi") return saved;
  } catch { /* storage blocked: fine */ }
  return navigator.language?.toLowerCase().startsWith("hi") ? "hi" : "en";
}

const PRESETS: Record<string, "morning" | "afternoon" | "night"> = { "08:00": "morning", "13:00": "afternoon", "20:00": "night" };
const WORDS = { en: { morning: "Morning", afternoon: "Afternoon", night: "Night", and: " & " }, hi: { morning: "सुबह", afternoon: "दोपहर", night: "रात", and: " और " } } as const;

/** Pregnancy week + due date, a child's birth date (with age) and weight: the first lines staff read. */
function factsOf(card: PublicCard, lang: Lang): string[] {
  const tf = createTranslator({ locale: lang, messages: MESSAGES[lang], namespace: "facts" }) as unknown as (key: string, values?: Record<string, string | number>) => string;
  const weight = card.child?.weight_kg;
  return keyFactLines({
    pregnancy: card.pregnancy ? { week: card.pregnancy.week ?? null, dueDate: card.pregnancy.due_date ?? null } : null,
    child: card.child ? { born: card.child.born ?? null, weightKg: weight === null || weight === undefined ? null : Number(weight), weightOn: card.child.weight_on ?? null } : null,
  }, tf, lang, todayOn(Intl.DateTimeFormat().resolvedOptions().timeZone));
}

function clock(time: string, lang: Lang) {
  const [h, m] = time.split(":").map(Number);
  const t = `${h % 12 || 12}:${String(m).padStart(2, "0")}`;
  if (lang === "hi") return `${h >= 4 && h < 12 ? "सुबह" : h >= 12 && h < 16 ? "दोपहर" : h >= 16 && h < 20 ? "शाम" : "रात"} ${t}`;
  return `${t} ${h < 12 ? "AM" : "PM"}`;
}

export default function PublicEmergency() {
  const [lang, setLang] = useState<Lang>(initialLanguage);
  const [result, setResult] = useState<CardResult | null>(null);
  const token = publicLinkToken(window.location.hash);
  const t = createTranslator({ locale: lang, messages: MESSAGES[lang], namespace: "publicCard" });
  const units = createTranslator({ locale: lang, messages: MESSAGES[lang], namespace: "units" });

  const load = useCallback(() => {
    setResult(null);
    if (!token) { setResult({ kind: "off" }); return () => undefined; }
    const controller = new AbortController();
    void fetchPublicCard(token, controller.signal).then((r) => { if (!controller.signal.aborted) setResult(r); });
    return () => controller.abort();
  }, [token]);
  useEffect(() => load(), [load]);
  useEffect(() => { document.documentElement.lang = lang; document.title = t("title"); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [lang]);

  const dose = (d: string) => {
    const m = /^([\d.]+)\s+(\w+?)s?$/.exec(d.trim());
    const unit = m?.[2];
    return m && unit && ["tablet", "capsule"].includes(unit) ? `${m[1]} ${units(unit as "tablet" | "capsule", { count: Number(m[1]) })}` : d;
  };
  const times = (list: string[]) => list.map((x) => { const p = PRESETS[x]; return p ? WORDS[lang][p] : clock(x, lang); }).join(WORDS[lang].and);

  return (
    <div className="pe-page">
      <header className="pe-top">
        <div><h1>{t("title")}</h1><p>{t("subtitle")}</p></div>
        <button className="pe-lang" onClick={() => setLang(lang === "en" ? "hi" : "en")} aria-label={t("switchLabel")}>{t("switchTo")}</button>
      </header>
      {result === null && <p className="pe-note" role="status">{t("loading")}</p>}
      {result?.kind === "off" && <section className="pe-card pe-state" role="alert"><h2>{t("offTitle")}</h2><p>{t("offBody")}</p></section>}
      {(result?.kind === "busy" || result?.kind === "error") && (
        <section className="pe-card pe-state" role="alert">
          <h2>{t(result.kind === "busy" ? "busyTitle" : "errorTitle")}</h2><p>{t(result.kind === "busy" ? "busyBody" : "errorBody")}</p>
          <button className="pe-button" onClick={load}>{t("retry")}</button>
        </section>
      )}
      {result?.kind === "ok" && (() => {
        const c = result.card;
        const facts = factsOf(c, lang);
        return (
          <main>
            <section className="pe-card pe-id">
              <div><h2>{c.name}</h2>{c.age !== null && <p>{t("years", { count: c.age })}</p>}{facts.map((line) => <p key={line} className="pe-fact">{line}</p>)}</div>
              <div className="pe-blood"><small>{t("bloodGroup")}</small><strong aria-label={`${t("bloodGroup")}: ${c.blood_group ?? t("unknown")}`}>{c.blood_group ?? "?"}</strong></div>
            </section>
            <section className="pe-card pe-alert"><h3>{t("allergies")}</h3><p>{c.allergies.length ? c.allergies.join(", ") : t("unknown")}</p></section>
            <section className="pe-card"><h3>{t("conditions")}</h3><p>{c.conditions.length ? c.conditions.join(", ") : t("unknown")}</p></section>
            <section className="pe-card">
              <h3>{t("medicines")}</h3>
              {c.medicines.length ? <ul>{c.medicines.map((m, i) => <li key={i}><strong>{[m.name, m.dose && dose(m.dose)].filter(Boolean).join(" ")}</strong>{m.times.length > 0 && <span>{times(m.times)}</span>}</li>)}</ul> : <p>{t("none")}</p>}
            </section>
            {c.doctor && <section className="pe-card"><h3>{t("doctor")}</h3><p>{c.doctor}</p></section>}
            {c.contacts.length > 0 && (
              <section className="pe-card">
                <h3>{t("contacts")}</h3>
                {c.contacts.map((p) => {
                  const who = p.name || t("callFamily");
                  return <a key={p.phone} className="pe-call" href={`tel:${p.phone}`}><span><strong>{who}</strong><small>{p.phone}</small></span><b>{t("call", { name: who })}</b></a>;
                })}
              </section>
            )}
            <p className="pe-note">{t("disclaimer")}</p>
          </main>
        );
      })()}
    </div>
  );
}
