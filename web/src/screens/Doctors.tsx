import { useState, type FormEvent } from "react";
import "./doctors.css";
import { useLocale, useTranslations } from "use-intl";
import { AppHeader, Field, Icon, parentCall, useDialog, useToast } from "../App";
import { ConfirmSheet } from "./Invites";
import { Icon3D } from "./Icon3D";
import { FeatureEntry, type EntryLook } from "./FeatureEntry";
import { normalizeCallablePhone, saveDoctorsApi, type Recipient } from "../lib/api";
import { cleanTyped } from "../lib/doctorAddress";
import { ADDRESS_MAX, CLINIC_MAX, DOCTORS_MAX, NAME_MAX, SPECIALITY_MAX, directionsUrl, doctorsOf, toPayload, type Doctor } from "../lib/doctors";
import type { Language } from "../lib/format";

/** A map pin for "Directions" (the shared icon set has none). */
const Pin = () => (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21z" /><circle cx="12" cy="9.5" r="2.5" />
  </svg>
);

/** The Family link to this screen, with how many doctors are saved. */
export function DoctorsLink({ go, recipient, look }: { go: () => void; recipient: Recipient | null; look?: EntryLook }) {
  const t = useTranslations("doctors");
  const language = useLocale() as Language;
  const count = doctorsOf(recipient?.doctor_contacts).length;
  return <FeatureEntry look={look} icon="doctors" title={t("linkTitle")} sub={count ? t("linkCount", { count }) : t("linkSub", { call: parentCall(recipient, language) })} onClick={go} />;
}

function DoctorSheet({ existing, onClose, onSave, onRemove }: { existing: Doctor | null; onClose: () => void; onSave: (doctor: Doctor) => Promise<boolean>; onRemove: () => Promise<boolean> }) {
  const t = useTranslations("doctors");
  const dialog = useDialog(onClose);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const read = (key: string, max: number) => cleanTyped(String(data.get(key) ?? ""), max).replace(/\s+/g, " ").trim();
    const name = read("name", NAME_MAX);
    const phoneTyped = read("phone", 30);
    const phone = phoneTyped ? normalizeCallablePhone(phoneTyped) : "";
    const found: Record<string, string> = {};
    if (!name) found["d-name"] = t("errName");
    if (phone === null) found["d-phone"] = t("errPhone");
    setErrors(found);
    if (Object.keys(found).length) { document.getElementById(Object.keys(found)[0])?.focus(); return; }
    setBusy(true);
    const ok = await onSave({ name, speciality: read("speciality", SPECIALITY_MAX), clinic: read("clinic", CLINIC_MAX), phone: phone ?? "", address: read("address", ADDRESS_MAX) });
    setBusy(false);
    if (ok) onClose();
  };
  const remove = async () => {
    setBusy(true);
    const ok = await onRemove();
    setBusy(false);
    setConfirming(false);
    if (ok) onClose();
  };
  const input = (id: string, name: string, max: number, value: string, extra: Record<string, string> = {}) => (
    <input id={id} name={name} maxLength={max} autoComplete="off" defaultValue={value} aria-invalid={!!errors[id]} aria-describedby={errors[id] ? `${id}-error` : undefined} {...extra} />
  );
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <section className="sheet" ref={dialog} tabIndex={-1} role="dialog" aria-modal="true" aria-label={t(existing ? "editTitle" : "addTitle")} onClick={(event) => event.stopPropagation()}>
        <h3>{t(existing ? "editTitle" : "addTitle")}</h3>
        <form onSubmit={save} noValidate>
          <Field id="d-name" label={t("name")} error={errors["d-name"]}>{input("d-name", "name", NAME_MAX, existing?.name ?? "", { placeholder: t("namePlaceholder") })}</Field>
          <Field id="d-speciality" label={t("speciality")} hint={t("specialityHint")}>{input("d-speciality", "speciality", SPECIALITY_MAX, existing?.speciality ?? "", { placeholder: t("specialityPlaceholder") })}</Field>
          <Field id="d-clinic" label={t("clinic")}>{input("d-clinic", "clinic", CLINIC_MAX, existing?.clinic ?? "", { placeholder: t("clinicPlaceholder") })}</Field>
          <Field id="d-phone" label={t("phone")} error={errors["d-phone"]} hint={t("phoneHint")}>{input("d-phone", "phone", 30, existing?.phone ?? "", { type: "tel", inputMode: "tel", placeholder: "+91 99999 00001" })}</Field>
          <Field id="d-address" label={t("address")} hint={t("addressHint")}>
            <textarea id="d-address" name="address" maxLength={ADDRESS_MAX} rows={2} defaultValue={existing?.address ?? ""} placeholder={t("addressPlaceholder")} />
          </Field>
          <div className="stack">
            <button className="primary-button" type="submit" disabled={busy}>{busy ? t("saving") : t("save")}</button>
            <button className="secondary-button" type="button" onClick={onClose}>{t("cancel")}</button>
            {existing && <button className="danger-button" type="button" onClick={() => setConfirming(true)} disabled={busy}>{t("remove")}</button>}
          </div>
        </form>
        {confirming && existing && <ConfirmSheet title={t("removeTitle", { name: existing.name })} body={t("removeBody")} confirm={t("remove")} busy={busy} onConfirm={() => void remove()} onCancel={() => setConfirming(false)} />}
      </section>
    </div>
  );
}

/**
 * "Your doctors": the family's own list (name, speciality, clinic, phone, address). One tap to call, one tap for directions
 * (a Google Maps search of the address, in a new tab). Name, speciality and phone show on the emergency card; the first
 * doctor is the starting "Prepared for" on the doctor summary. Nothing here is suggested, ranked or recommended by Chhaon.
 */
export function DoctorsScreen({ go, recipient, onChanged }: { go: (screen: "family") => void; recipient: Recipient | null; onChanged: () => Promise<unknown> }) {
  const t = useTranslations("doctors");
  const language = useLocale() as Language;
  const toast = useToast();
  // The list on screen: what the server returned last (so a save shows at once, before the app reloads the person).
  const [saved, setSaved] = useState<{ id: string; list: Doctor[] } | null>(null);
  const [sheet, setSheet] = useState<{ index: number | null } | null>(null);
  if (!recipient) return null;
  const list = saved?.id === recipient.id ? saved.list : doctorsOf(recipient.doctor_contacts);
  const call = parentCall(recipient, language);

  const store = async (next: Doctor[], done: string) => {
    try {
      const updated = await saveDoctorsApi(recipient.id, toPayload(next));
      setSaved({ id: recipient.id, list: doctorsOf(updated.doctor_contacts) });
      toast(t(done));
      void onChanged();
      return true;
    } catch {
      toast(t("errSave"));
      return false;
    }
  };
  const editing = sheet && sheet.index !== null ? list[sheet.index] ?? null : null;

  return (
    <main className="scroll-page no-nav">
      <AppHeader title={t("title")} subtitle={t("subtitle", { call })} back={() => go("family")} action={<Icon3D name="doctors" size={40} eager className="header-3d" />} />
      {list.length === 0 && (
        <div className="empty-note"><Icon3D name="doctors" size={64} className="empty-3d" /><strong>{t("emptyTitle")}</strong><p>{t("emptyBody", { call })}</p></div>
      )}
      {list.length > 0 && (
        <section className="doctor-list" aria-label={t("title")}>
          {list.map((d, index) => (
            <article className="doctor-card" key={`${index}-${d.name}`}>
              <button className="doctor-main" onClick={() => setSheet({ index })} aria-label={t("editLabel", { name: d.name })}>
                <span className="doctor-body">
                  <strong>{d.name}</strong>
                  {d.speciality && <em>{d.speciality}</em>}
                  {d.clinic && <small>{d.clinic}</small>}
                  {d.address && <small>{d.address}</small>}
                  {d.phone && <small dir="ltr">{d.phone}</small>}
                </span>
                <Icon name="edit" size={18} />
              </button>
              {(d.phone || d.address || d.clinic) && (
                <div className="doctor-actions">
                  {d.phone && <a className="doctor-action" href={`tel:${d.phone}`} aria-label={t("callLabel", { name: d.name })}><Icon name="phone" size={20} /> {t("call")}</a>}
                  {(d.address || d.clinic) && <a className="doctor-action" href={directionsUrl(d)} target="_blank" rel="noopener noreferrer" aria-label={t("directionsLabel", { name: d.name })}><Pin /> {t("directions")}</a>}
                </div>
              )}
            </article>
          ))}
        </section>
      )}
      {list.length < DOCTORS_MAX
        ? <button className="primary-button doctor-add" onClick={() => setSheet({ index: null })}><Icon name="plus" size={18} /> {t("add")}</button>
        : <p className="form-note">{t("full", { count: DOCTORS_MAX })}</p>}
      <p className="form-note doctor-note"><Icon name="shield" size={16} /> {t("note")}</p>
      {sheet && (
        <DoctorSheet
          existing={editing}
          onClose={() => setSheet(null)}
          onSave={(doctor) => store(sheet.index === null ? [...list, doctor] : list.map((d, i) => (i === sheet.index ? doctor : d)), sheet.index === null ? "added" : "updated")}
          onRemove={() => store(list.filter((_, i) => i !== sheet.index), "removed")}
        />
      )}
    </main>
  );
}
