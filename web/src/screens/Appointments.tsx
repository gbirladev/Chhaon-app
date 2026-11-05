import { useEffect, useState, type FormEvent } from "react";
import "./appointments.css";
import { useLocale, useTranslations } from "use-intl";
import { AppHeader, Field, Icon, parentCall, useDialog, useToast } from "../App";
import { ConfirmSheet } from "./Invites";
import { createAppointment, deleteAppointment, loadAppointments, updateAppointment, doctorName, localTimeExists, utcToZoned, zonedToUtc, type Appointment } from "../lib/appointments";
import { formatClock, formatDate, type Language } from "../lib/format";
import type { Recipient } from "../lib/api";
import { authUserId } from "../lib/auth";
import { receiverValues } from "../lib/reminderReceiver";
import { isPregnancy } from "../lib/pregnancy";
import { UsualChecks, type CheckPrefill } from "./Pregnancy";

const when = (iso: string, tz: string, language: Language) => {
  const day = formatDate(new Date(iso), language, { weekday: "short", day: "numeric", month: "short", timeZone: tz });
  return `${day} • ${formatClock(new Date(iso), tz, language)}`;
};

/** The "next visit" card on Today. Shows the next upcoming visit, or an invitation to add one. */
export function NextAppointment({ recipient, go }: { recipient: Recipient; go: () => void }) {
  const t = useTranslations("appointments");
  const language = useLocale() as Language;
  const tz = recipient.timezone ?? "Asia/Kolkata";
  const [next, setNext] = useState<Appointment | null | undefined>(undefined);
  useEffect(() => {
    let cancelled = false;
    const load = () => void loadAppointments(recipient.id).then((list) => { if (!cancelled) setNext(list.find((a) => new Date(a.at).getTime() > Date.now()) ?? null); }).catch(() => {});
    load();
    const id = window.setInterval(load, 60000);
    return () => { cancelled = true; window.clearInterval(id); };
  }, [recipient.id]);
  if (next === undefined) return null;
  return (
    <button className={`next-visit${next ? "" : " empty"}`} onClick={go}>
      <span className="next-visit-icon" aria-hidden="true"><Icon name="calendar" size={18} /></span>
      <span className="next-visit-text">
        <strong>{next ? t("with", { doctor: doctorName(next.doctor) }) : t("addFirst")}</strong>
        {next && <em>{when(next.at, tz, language)}</em>}
      </span>
      <Icon name="chevron" size={18} />
    </button>
  );
}

export function AppointmentSheet({ recipient, existing, prefill, onClose, onSaved }: { recipient: Recipient; existing: Appointment | null; prefill?: CheckPrefill; onClose: () => void; onSaved: () => void }) {
  const t = useTranslations("appointments");
  const language = useLocale() as Language;
  const toast = useToast();
  const dialog = useDialog(onClose);
  const tz = recipient.timezone ?? "Asia/Kolkata";
  const call = parentCall(recipient, language);
  // A usual pregnancy check ("+ Add") only suggests a date and a note; the family fills in the rest and saves (or cancels).
  const initial = existing ? utcToZoned(existing.at, tz) : { date: prefill?.date ?? "", time: "" };
  const today = utcToZoned(new Date().toISOString(), tz).date;
  const [remind, setRemind] = useState(existing?.remind ?? true);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const canRemind = !!recipient.consent_at && !recipient.opted_out_at;
  // The reminder goes to whoever gets this person's reminders: the person, a helper, or you (lib/reminderReceiver.ts).
  const tw = useTranslations("who");
  const who = receiverValues(recipient, authUserId(), { call, you: tw("you"), someone: tw("someone") });

  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const doctor = String(data.get("doctor") ?? "").trim();
    const date = String(data.get("date") ?? "");
    const time = String(data.get("time") ?? "");
    const found: Record<string, string> = {};
    if (!doctor) found["a-doctor"] = t("errDoctor");
    const at = date && time ? zonedToUtc(date, time, tz) : null;
    if (!date) found["a-date"] = t("errDate");
    if (!time) found["a-time"] = t("errTime");
    if (date && time && !at) found["a-time"] = t("errTime");
    else if (date && time && !localTimeExists(date, time, tz)) found["a-time"] = t("errGap", { call });
    if (at && !existing && new Date(at).getTime() < Date.now() - 60000) found["a-date"] = t("errPast");
    setErrors(found);
    if (Object.keys(found).length || !at) { document.getElementById(Object.keys(found)[0])?.focus(); return; }
    setBusy(true);
    const input = { doctor, at, location: String(data.get("location") ?? "").trim() || null, notes: String(data.get("notes") ?? "").trim() || null, remind };
    const ok = existing ? await updateAppointment(existing.id, input) : await createAppointment(recipient.id, input);
    setBusy(false);
    if (!ok) return toast(t("errSave"));
    toast(t(existing ? "updated" : "added"));
    onSaved();
  };
  const remove = async () => {
    if (!existing) return;
    setBusy(true);
    const ok = await deleteAppointment(existing.id);
    setBusy(false);
    setConfirming(false);
    if (!ok) return toast(t("errSave"));
    toast(t("deleted"));
    onSaved();
  };

  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <section className="sheet" ref={dialog} tabIndex={-1} role="dialog" aria-modal="true" aria-label={t(existing ? "editTitle" : "addTitle")} onClick={(event) => event.stopPropagation()}>
        <h3>{t(existing ? "editTitle" : "addTitle")}</h3>
        <form onSubmit={save} noValidate>
          <Field id="a-doctor" label={t("doctor")} error={errors["a-doctor"]}>
            <input id="a-doctor" name="doctor" maxLength={100} autoComplete="off" placeholder={t("doctorPlaceholder")} defaultValue={existing?.doctor ?? ""} aria-invalid={!!errors["a-doctor"]} aria-describedby={errors["a-doctor"] ? "a-doctor-error" : undefined} />
          </Field>
          <div className="two-col">
            <Field id="a-date" label={t("date")} error={errors["a-date"]}>
              <input id="a-date" name="date" type="date" min={existing ? undefined : today} defaultValue={initial.date} aria-invalid={!!errors["a-date"]} aria-describedby={errors["a-date"] ? "a-date-error" : undefined} />
            </Field>
            <Field id="a-time" label={t("time")} error={errors["a-time"]}>
              <input id="a-time" name="time" type="time" defaultValue={initial.time} aria-invalid={!!errors["a-time"]} aria-describedby={errors["a-time"] ? "a-time-error" : undefined} />
            </Field>
          </div>
          <p className="form-note appt-tz">{t("tzNote", { call })}</p>
          <Field id="a-location" label={t("location")}><input id="a-location" name="location" maxLength={200} autoComplete="off" placeholder={t("locationPlaceholder")} defaultValue={existing?.location ?? ""} /></Field>
          <Field id="a-notes" label={t("notes")}><textarea id="a-notes" name="notes" maxLength={500} rows={2} defaultValue={existing?.notes ?? prefill?.notes ?? ""} /></Field>
          <label className="toggle-row">
            <span><strong>{tw("apptRemind", who)}</strong><small>{canRemind ? t("remindHint") : tw("apptRemindNoConsent", who)}</small></span>
            <input type="checkbox" role="switch" checked={remind} onChange={(event) => setRemind(event.target.checked)} /><i className={remind ? "" : "off"} />
          </label>
          <div className="stack">
            <button className="primary-button" type="submit" disabled={busy}>{busy ? t("saving") : t("save")}</button>
            <button className="secondary-button" type="button" onClick={onClose}>{t("cancel")}</button>
            {existing && <button className="danger-button" type="button" onClick={() => setConfirming(true)} disabled={busy}>{t("delete")}</button>}
          </div>
        </form>
        {confirming && <ConfirmSheet title={t("confirmTitle", { doctor: doctorName(existing?.doctor ?? "") })} body={t("confirmBody")} confirm={t("delete")} busy={busy} onConfirm={remove} onCancel={() => setConfirming(false)} />}
      </section>
    </div>
  );
}

export function AppointmentsScreen({ go, recipient }: { go: (screen: "today") => void; recipient: Recipient | null }) {
  const t = useTranslations("appointments");
  const tw = useTranslations("who");
  const language = useLocale() as Language;
  const [list, setList] = useState<Appointment[] | null>(null);
  const [error, setError] = useState(false);
  const [sheet, setSheet] = useState<{ existing: Appointment | null; prefill?: CheckPrefill } | null>(null);
  const [showPast, setShowPast] = useState(false);
  const tz = recipient?.timezone ?? "Asia/Kolkata";
  const refresh = async () => {
    if (!recipient) return;
    try { setList(await loadAppointments(recipient.id)); setError(false); } catch { setError(true); }
  };
  useEffect(() => { setList(null); void refresh(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [recipient?.id]);
  if (!recipient) return null;

  const now = Date.now();
  const upcoming = (list ?? []).filter((a) => new Date(a.at).getTime() >= now);
  const past = (list ?? []).filter((a) => new Date(a.at).getTime() < now).reverse();
  const card = (a: Appointment, isPast: boolean) => (
    <button key={a.id} className={`appt-card${isPast ? " past" : ""}`} onClick={() => setSheet({ existing: a })}>
      <span className="appt-icon" aria-hidden="true"><Icon name="calendar" size={20} /></span>
      <span className="appt-body">
        <strong>{t("with", { doctor: doctorName(a.doctor) })}</strong>
        <em>{when(a.at, tz, language)}</em>
        {a.location && <small>{a.location}</small>}
        {a.notes && <small>{a.notes}</small>}
      </span>
      {!isPast && a.remind && <span className="appt-bell" title={t("reminderOn")} aria-label={t("reminderOn")}><Icon name="bell" size={16} /></span>}
    </button>
  );
  return (
    <main className="scroll-page">
      <AppHeader title={t("title")} subtitle={t("subtitle", { call: parentCall(recipient, language) })} back={() => go("today")} />
      {error && <p role="alert" className="form-error">{t("loadFailed")}</p>}
      {list === null && !error && <div className="skeleton card" aria-busy="true" />}
      {list !== null && upcoming.length === 0 && <div className="empty-note"><strong>{t("emptyTitle")}</strong><p>{tw("apptEmptyBody", receiverValues(recipient, authUserId(), { call: parentCall(recipient, language), you: tw("you"), someone: tw("someone") }))}</p></div>}
      {upcoming.length > 0 && <section className="appt-list" aria-label={t("upcoming")}>{upcoming.map((a) => card(a, false))}</section>}
      {past.length > 0 && (
        <>
          <button className="inline-link appt-past-toggle" aria-expanded={showPast} onClick={() => setShowPast((v) => !v)}>{showPast ? t("hidePast") : t("showPast", { count: past.length })}</button>
          {showPast && <section className="appt-list">{past.slice(0, 20).map((a) => card(a, true))}</section>}
        </>
      )}
      {isPregnancy(recipient) && <UsualChecks recipient={recipient} uiLanguage={language} onAdd={(prefill) => setSheet({ existing: null, prefill })} />}
      <button className="fab" onClick={() => setSheet({ existing: null })}><Icon name="plus" /><span>{t("add")}</span></button>
      {sheet && <AppointmentSheet recipient={recipient} existing={sheet.existing} prefill={sheet.prefill} onClose={() => setSheet(null)} onSaved={() => { setSheet(null); void refresh(); }} />}
    </main>
  );
}
