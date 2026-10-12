import { useEffect, useRef, useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { useTranslations } from "use-intl";
import { z } from "zod";
import { PageHeader } from "@/components/PageHeader";
import { PhoneField } from "@/components/PhoneField";
import { useToast } from "@/components/Toast";
import { E164 } from "@/lib/countries";
import { TIMEZONES } from "@/lib/timezones";
import { supabase } from "@/lib/supabase";
import { useRecipient, type Recipient } from "@/lib/recipient";
import type { Json } from "@/lib/database.types";

// Stored as canonical English so reports and the emergency card stay consistent in any UI language.
const CONDITION_PRESETS = [
  ["Diabetes", "diabetes"],
  ["High BP", "highBp"],
  ["Thyroid", "thyroid"],
  ["Heart disease", "heart"],
  ["Arthritis", "arthritis"],
  ["Asthma", "asthma"],
] as const;
const BLOOD_GROUPS = ["A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"];

type Contact = { name: string; phone: string };
type FormState = {
  name: string; relation: "father" | "mother" | "other"; dob: string; whatsapp: string; city: string;
  timezone: string; language: "en" | "hi"; conditions: string[]; allergies: string; bloodGroup: string;
  doctor: Contact; emergency: Contact; attest: boolean;
};

function firstContact(value: Json): Contact {
  const first = Array.isArray(value) ? value[0] : null;
  if (first && typeof first === "object" && !Array.isArray(first)) {
    return { name: String(first.name ?? ""), phone: String(first.phone ?? "") };
  }
  return { name: "", phone: "" };
}

function fromRecipient(r: Recipient): FormState {
  return {
    name: r.name, relation: r.relation as FormState["relation"], dob: r.dob ?? "", whatsapp: r.whatsapp_phone ?? "",
    city: r.city ?? "", timezone: r.timezone, language: r.language as FormState["language"], conditions: r.conditions,
    allergies: r.allergies.join(", "), bloodGroup: r.blood_group ?? "",
    doctor: firstContact(r.doctor_contacts), emergency: firstContact(r.emergency_contacts), attest: true,
  };
}

const EMPTY: FormState = {
  name: "", relation: "father", dob: "", whatsapp: "", city: "", timezone: "Asia/Kolkata", language: "hi",
  conditions: [], allergies: "", bloodGroup: "", doctor: { name: "", phone: "" }, emergency: { name: "", phone: "" }, attest: false,
};

const splitList = (s: string) => s.split(/[,\n]/).map((x) => x.trim()).filter(Boolean);
const contactJson = (c: Contact) => (c.name.trim() || c.phone ? [{ name: c.name.trim(), phone: c.phone }] : []);

function ageFrom(dob: string): number | null {
  const d = new Date(dob);
  if (Number.isNaN(d.getTime()) || d > new Date()) return null;
  const now = new Date();
  let age = now.getFullYear() - d.getFullYear();
  if (now < new Date(now.getFullYear(), d.getMonth(), d.getDate())) age -= 1;
  return age >= 0 && age < 130 ? age : null;
}

export default function ParentFormPage({ mode }: { mode: "new" | "edit" }) {
  const t = useTranslations("parent");
  const tc = useTranslations("common");
  const navigate = useNavigate();
  const toast = useToast();
  const { recipient, reload } = useRecipient();
  const editing = mode === "edit" && recipient;

  const [form, setForm] = useState<FormState>(() => (editing ? fromRecipient(editing) : EMPTY));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [customCondition, setCustomCondition] = useState("");
  const touched = useRef({ timezone: mode === "edit", language: mode === "edit" });

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => setForm((f) => ({ ...f, [key]: value }));

  // Smart defaults from the WhatsApp number: an Indian number means Indian time and (usually) Hindi messages.
  function onWhatsappChange(value: string) {
    setForm((f) => {
      const next = { ...f, whatsapp: value };
      if (value.startsWith("+91")) {
        if (!touched.current.timezone) next.timezone = "Asia/Kolkata";
        if (!touched.current.language) next.language = "hi";
      } else if (value.length > 3 && !touched.current.language) {
        next.language = "en";
      }
      return next;
    });
    setErrors((e) => ({ ...e, whatsapp: "" }));
  }

  useEffect(() => {
    const first = Object.keys(errors).find((k) => errors[k]);
    if (first) document.getElementById(`field-${first}`)?.focus();
  }, [errors]);

  const age = form.dob ? ageFrom(form.dob) : null;
  const toggleCondition = (value: string) =>
    set("conditions", form.conditions.includes(value) ? form.conditions.filter((c) => c !== value) : [...form.conditions, value]);
  const addCustomCondition = () => {
    const v = customCondition.trim();
    if (v && !form.conditions.some((c) => c.toLowerCase() === v.toLowerCase())) set("conditions", [...form.conditions, v]);
    setCustomCondition("");
  };

  function validate(): Record<string, string> {
    const e: Record<string, string> = {};
    if (!z.string().trim().min(1).safeParse(form.name).success) e.name = t("errors.name");
    if (!E164.test(form.whatsapp)) e.whatsapp = t("errors.phone");
    if (form.dob && ageFrom(form.dob) === null) e.dob = t("errors.dob");
    if (form.doctor.phone && !E164.test(form.doctor.phone)) e.doctorPhone = t("errors.phone");
    if (form.emergency.phone && !E164.test(form.emergency.phone)) e.emergencyPhone = t("errors.phone");
    if (!editing && !form.attest) e.attest = t("errors.attest");
    return e;
  }

  async function submit(ev: FormEvent) {
    ev.preventDefault();
    setSaveError(null);
    const found = validate();
    setErrors(found);
    if (Object.keys(found).length) return;
    if (!navigator.onLine) return setSaveError(tc("offline"));

    const conditions = form.conditions;
    const allergies = splitList(form.allergies);
    setSaving(true);

    let failed: string | null = null;
    if (editing) {
      const { error } = await supabase.from("care_recipients").update({
        name: form.name.trim(), relation: form.relation, dob: form.dob || null, city: form.city.trim() || null,
        whatsapp_phone: form.whatsapp, timezone: form.timezone, language: form.language, conditions, allergies,
        blood_group: form.bloodGroup || null, doctor_contacts: contactJson(form.doctor), emergency_contacts: contactJson(form.emergency),
      }).eq("id", editing.id);
      failed = error?.message ?? null;
    } else {
      const { error } = await supabase.rpc("create_care_recipient", {
        p: {
          name: form.name.trim(), relation: form.relation, dob: form.dob, city: form.city.trim(), whatsapp_phone: form.whatsapp,
          timezone: form.timezone, language: form.language, conditions, allergies, blood_group: form.bloodGroup,
          doctor_contacts: contactJson(form.doctor), emergency_contacts: contactJson(form.emergency), caregiver_attested: form.attest,
        },
      });
      failed = error?.message ?? null;
    }
    setSaving(false);
    if (failed) { setSaveError(failed); return; }

    await reload();
    toast.show(tc("saved"));
    navigate("/dashboard", { replace: true });
  }

  const err = (key: string) => errors[key] ? <p className="field-error" role="alert">{errors[key]}</p> : null;
  const cls = (key: string) => `field${errors[key] ? " invalid" : ""}`;

  return (
    <main className="scroll-page no-nav">
      <PageHeader
        title={editing ? t("titleEdit") : t("titleNew")}
        subtitle={editing ? t("subtitleEdit") : t("subtitleNew")}
        backTo={editing ? "/dashboard" : undefined}
      />
      <form id="parent-form" className="form-card" onSubmit={submit} noValidate>
        <div className={cls("name")}>
          <label htmlFor="field-name">{t("name")}</label>
          <input id="field-name" value={form.name} autoComplete="off" onChange={(e) => { set("name", e.target.value); setErrors((x) => ({ ...x, name: "" })); }} />
          {err("name")}
        </div>

        <div className="field">
          <label>{t("relation")}</label>
          <div className="segmented" role="group" aria-label={t("relation")}>
            {(["father", "mother", "other"] as const).map((r) => (
              <button key={r} type="button" aria-pressed={form.relation === r} onClick={() => set("relation", r)}>{t(`relations.${r}`)}</button>
            ))}
          </div>
        </div>

        <div className={cls("dob")}>
          <label htmlFor="field-dob">{t("dob")} <span className="hint" style={{ display: "inline" }}>({tc("optional")})</span></label>
          <input id="field-dob" type="date" value={form.dob} max={new Date().toISOString().slice(0, 10)} onChange={(e) => { set("dob", e.target.value); setErrors((x) => ({ ...x, dob: "" })); }} />
          {age !== null && <p className="smart-hint">{t("ageYears", { count: age })}</p>}
          {err("dob")}
        </div>

        <div className={cls("whatsapp")}>
          <label htmlFor="field-whatsapp">{t("whatsapp")}</label>
          <PhoneField id="field-whatsapp" value={form.whatsapp} onChange={onWhatsappChange} countryLabel={t("countryLabel")} placeholder="98765 43210" />
          <span className="hint">{t("whatsappHint")}</span>
          {err("whatsapp")}
        </div>

        <div className="field">
          <label htmlFor="field-city">{t("city")}</label>
          <input id="field-city" value={form.city} autoComplete="off" onChange={(e) => set("city", e.target.value)} />
        </div>

        <div className="two-col">
          <div className="field">
            <label htmlFor="field-timezone">{t("timezone")}</label>
            <select id="field-timezone" value={form.timezone} onChange={(e) => { touched.current.timezone = true; set("timezone", e.target.value); }}>
              {TIMEZONES.map((z) => <option key={z} value={z}>{z.replace("_", " ")}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="field-language">{t("language")}</label>
            <select id="field-language" value={form.language} onChange={(e) => { touched.current.language = true; set("language", e.target.value as "en" | "hi"); }}>
              <option value="hi">{t("languages.hi")}</option>
              <option value="en">{t("languages.en")}</option>
            </select>
          </div>
        </div>
        <p className="hint" style={{ marginTop: -6, marginBottom: 16 }}>{t("timezoneHint")}</p>

        <div className="field">
          <label>{t("conditions")}</label>
          <div className="chips">
            {CONDITION_PRESETS.map(([value, key]) => (
              <button key={value} type="button" className={form.conditions.includes(value) ? "selected" : ""} aria-pressed={form.conditions.includes(value)} onClick={() => toggleCondition(value)}>
                {t(`conditionPresets.${key}`)}
              </button>
            ))}
            {form.conditions.filter((c) => !CONDITION_PRESETS.some(([v]) => v === c)).map((c) => (
              <span key={c} className="chip selected chip">{c}
                <button type="button" className="chip-remove" aria-label={`${tc("delete")} ${c}`} onClick={() => toggleCondition(c)}>×</button>
              </span>
            ))}
          </div>
          <div className="row-add">
            <input aria-label={t("conditionAdd")} placeholder={t("conditionAdd")} value={customCondition} onChange={(e) => setCustomCondition(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addCustomCondition(); } }} />
            <button type="button" onClick={addCustomCondition}>{t("conditionAddButton")}</button>
          </div>
        </div>

        <div className="two-col">
          <div className="field">
            <label htmlFor="field-blood">{t("bloodGroup")}</label>
            <select id="field-blood" value={form.bloodGroup} onChange={(e) => set("bloodGroup", e.target.value)}>
              <option value="">{t("notSure")}</option>
              {BLOOD_GROUPS.map((g) => <option key={g} value={g}>{g}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="field-allergies">{t("allergies")}</label>
            <input id="field-allergies" value={form.allergies} placeholder={t("allergiesPlaceholder")} onChange={(e) => set("allergies", e.target.value)} />
          </div>
        </div>
      </form>

      <h2 className="form-section">{t("doctorSection")} <span className="hint" style={{ display: "inline" }}>({tc("optional")})</span></h2>
      <div className="form-card">
        <div className="field">
          <label htmlFor="field-doctorName">{t("doctorName")}</label>
          <input id="field-doctorName" form="parent-form" value={form.doctor.name} onChange={(e) => set("doctor", { ...form.doctor, name: e.target.value })} />
        </div>
        <div className={cls("doctorPhone")}>
          <label htmlFor="field-doctorPhone">{t("doctorPhone")}</label>
          <PhoneField id="field-doctorPhone" value={form.doctor.phone} onChange={(v) => set("doctor", { ...form.doctor, phone: v })} countryLabel={t("countryLabel")} />
          {err("doctorPhone")}
        </div>
      </div>

      <h2 className="form-section">{t("emergencySection")} <span className="hint" style={{ display: "inline" }}>({tc("optional")})</span></h2>
      <div className="form-card">
        <div className="field">
          <label htmlFor="field-emergencyName">{t("emergencyName")}</label>
          <input id="field-emergencyName" value={form.emergency.name} onChange={(e) => set("emergency", { ...form.emergency, name: e.target.value })} />
        </div>
        <div className={cls("emergencyPhone")}>
          <label htmlFor="field-emergencyPhone">{t("emergencyPhone")}</label>
          <PhoneField id="field-emergencyPhone" value={form.emergency.phone} onChange={(v) => set("emergency", { ...form.emergency, phone: v })} countryLabel={t("countryLabel")} />
          {err("emergencyPhone")}
        </div>
      </div>

      {!editing && (
        <div className="form-card" style={{ marginTop: 16 }}>
          <label className="attest">
            <input id="field-attest" type="checkbox" form="parent-form" checked={form.attest} onChange={(e) => { set("attest", e.target.checked); setErrors((x) => ({ ...x, attest: "" })); }} />
            <span>{t("attest")}</span>
          </label>
          <p className="hint">{t("attestNote")}</p>
          {err("attest")}
        </div>
      )}

      {saveError && <p role="alert" className="form-error">{saveError}</p>}

      <div className="sticky-cta">
        <button className="primary-button" type="submit" form="parent-form" disabled={saving}>
          {saving ? tc("saving") : editing ? t("saveEdit") : t("save")}
        </button>
      </div>
    </main>
  );
}
