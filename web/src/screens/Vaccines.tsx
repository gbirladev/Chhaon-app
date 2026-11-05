import { useEffect, useState, type FormEvent } from "react";
import "./vaccines.css";
import { useLocale, useTranslations } from "use-intl";
import { AppHeader, Field, Icon, parentCall, useDialog, useToast } from "../App";
import type { Recipient } from "../lib/api";
import type { Language } from "../lib/format";
import { Icon3D } from "./Icon3D";
import { CardIcon } from "./TodayCard";
import {
  ageGroup, childDetailsErrors, doseText, fmtPlainDate as fmt, isChild, loadChild, loadVaccines, localToday, makeVaccinePlan, saveChild,
  updateVaccine, vaccinesGivenEarlier, type ChildDetails, type ChildInfo, type Vaccine, type VaccineChange,
} from "../lib/vaccines";

/*
 * Child profiles: date of birth, the guardian confirmation and the vaccination plan (docs/use-cases.md, step 3).
 * Medical safety: no doses or amounts, no "optional/required" beyond the notes of the IAP table, every date "as advised by your doctor",
 * nothing changes unless the family taps it. Children's data (DPDP): the guardian confirmation comes before anything is saved; no
 * analytics, tracking or ads on these screens; only the family circle can see it (enforced by the backend and the database).
 *
 * TODO(care-type merge): in ProfileForm (App.tsx), when the family picks "Child":
 *   1. render <ChildDetailsFields .../> (date of birth + "I am the parent or legal guardian of this child") and keep the save button
 *      from saving until childDetailsErrors(...) (lib/vaccines.ts) is empty;
 *   2. after the profile is created/updated, call saveChildDetails(newId, dob) from lib/vaccines.ts (it records the confirmation and the date of birth).
 * VaccinesLink / NextVaccine already show only for a child (isChild reads care_type defensively).
 */

function useDoseLabel() {
  const t = useTranslations("vaccines");
  return (dose: string) => doseText(t, dose);
}

function useAgeLabel() {
  const t = useTranslations("vaccines.age");
  return (group: string | null) => {
    const g = ageGroup(group);
    if (!g) return "";
    if (g.kind === "birth") return t("birth");
    if (g.to !== undefined) return t(g.kind === "years" ? "yearsRange" : g.kind === "months" ? "monthsRange" : "weeks", { from: g.from, to: g.to, n: g.from });
    return t(g.kind, { n: g.from });
  };
}

const vaccineName = (v: Pick<Vaccine, "vaccineEn" | "vaccineHi">, language: Language) => (language === "hi" && v.vaccineHi ? v.vaccineHi : v.vaccineEn);

// ---------------------------------------------------------------- the date of birth + guardian confirmation

/** Date of birth + "I am the parent or legal guardian of this child". Exported for the add/edit form (care-type merge). */
export function ChildDetailsFields({ value, onChange, today, errors = {}, guardianLocked = false }: {
  value: ChildDetails; onChange: (next: ChildDetails) => void; today: string; errors?: Record<string, string>; guardianLocked?: boolean;
}) {
  const t = useTranslations("vaccines");
  return (
    <>
      <Field id="child-dob" label={t("dob")} hint={t("dobHint")} error={errors["child-dob"]}>
        <input id="child-dob" type="date" max={today} value={value.dob} onChange={(event) => onChange({ ...value, dob: event.target.value })}
          aria-invalid={!!errors["child-dob"]} aria-describedby={errors["child-dob"] ? "child-dob-error" : undefined} />
      </Field>
      <label className="guardian-check">
        <input id="child-guardian" type="checkbox" checked={value.guardian || guardianLocked} disabled={guardianLocked}
          onChange={(event) => onChange({ ...value, guardian: event.target.checked })}
          aria-invalid={!!errors["child-guardian"]} aria-describedby={errors["child-guardian"] ? "child-guardian-error" : "child-guardian-hint"} />
        <span><strong>{t("guardian")}</strong><small id="child-guardian-hint">{t("guardianHint")}</small></span>
      </label>
      {errors["child-guardian"] && <p className="field-error" id="child-guardian-error" role="alert">{errors["child-guardian"]}</p>}
    </>
  );
}

function ChildSetup({ recipient, info, onDone, onCancel }: { recipient: Recipient; info: ChildInfo; onDone: () => void; onCancel?: () => void }) {
  const t = useTranslations("vaccines");
  const toast = useToast();
  const today = localToday(recipient.timezone ?? "Asia/Kolkata");
  const [details, setDetails] = useState<ChildDetails>({ dob: info.dob ?? "", guardian: false });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const found = childDetailsErrors(details, today, !!info.guardianConfirmedAt, { errDob: t("errDob"), errGuardian: t("errGuardian") });
    setErrors(found);
    if (Object.keys(found).length) { document.getElementById(Object.keys(found)[0])?.focus(); return; }
    setBusy(true);
    try {
      await saveChild(recipient.id, details.dob, details.guardian || !!info.guardianConfirmedAt);
      await makeVaccinePlan(recipient.id);
      onDone();
    } catch {
      toast(t("errSave"));
    } finally {
      setBusy(false);
    }
  };
  return (
    <form className="vaccine-setup" onSubmit={submit} noValidate>
      <h2>{t(info.planDoses ? "editDobTitle" : "setupTitle")}</h2>
      <p>{t("setupBody")}</p>
      <ChildDetailsFields value={details} onChange={setDetails} today={today} errors={errors} guardianLocked={!!info.guardianConfirmedAt} />
      <div className="stack">
        <button className="primary-button" type="submit" disabled={busy}>{busy ? t("making") : t(info.planDoses ? "saveDob" : "makePlan")}</button>
        {onCancel && <button className="secondary-button" type="button" onClick={onCancel}>{t("close")}</button>}
      </div>
    </form>
  );
}

// ---------------------------------------------------------------- one dose

export function VaccineSheet({ recipient, vaccine, onClose, onSaved }: { recipient: Recipient; vaccine: Vaccine; onClose: () => void; onSaved: () => void }) {
  const t = useTranslations("vaccines");
  const tn = useTranslations("vaccines.note");
  const language = useLocale() as Language;
  const toast = useToast();
  const dialog = useDialog(onClose);
  const doseLabel = useDoseLabel();
  const today = localToday(recipient.timezone ?? "Asia/Kolkata");
  // A dose given with the date not recorded keeps only the plan date as a placeholder: never offer it as the date it was given.
  const [givenOn, setGivenOn] = useState(vaccine.givenDateUnknown ? "" : vaccine.givenOn ?? (vaccine.dueDate <= today ? vaccine.dueDate : today));
  const [dueDate, setDueDate] = useState(vaccine.dueDate);
  const [notes, setNotes] = useState(vaccine.notes ?? "");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const open = vaccine.status !== "given" && vaccine.status !== "not_given";
  const send = async (change: VaccineChange) => {
    setBusy(true);
    try { await updateVaccine(vaccine.id, change); toast(t("saved")); onSaved(); }
    catch { toast(t("errSave")); }
    finally { setBusy(false); }
  };
  const markGiven = () => {
    if (!givenOn || givenOn > today) { setError(t("errGivenDate")); return; }
    void send({ status: "given", givenOn, notes });
  };
  const givenNoDate = async () => {
    setBusy(true);
    try { await vaccinesGivenEarlier(recipient.id, [vaccine.id], []); toast(t("saved")); onSaved(); }
    catch { toast(t("errSave")); }
    finally { setBusy(false); }
  };
  const title = [vaccineName(vaccine, language), doseLabel(vaccine.dose)].filter(Boolean).join(" · ");
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <section className="sheet vaccine-sheet" ref={dialog} tabIndex={-1} role="dialog" aria-modal="true" aria-label={title} onClick={(event) => event.stopPropagation()}>
        <div className="sheet-head"><h3>{title}</h3><button className="sheet-x" aria-label={t("close")} onClick={onClose}>×</button></div>
        {vaccine.note && tn.has(vaccine.note) && <p className="vaccine-note">{tn(vaccine.note)}</p>}
        {open ? (
          <>
            <Field id="v-given" label={t("givenDate")} error={error}>
              <input id="v-given" type="date" max={today} value={givenOn} onChange={(event) => { setGivenOn(event.target.value); setError(""); }}
                aria-invalid={!!error} aria-describedby={error ? "v-given-error" : undefined} />
            </Field>
            <div className="stack">
              <button className="primary-button" type="button" disabled={busy} onClick={markGiven}><Icon name="check" size={18} /> {t("markGiven")}</button>
              {vaccine.askIfGiven && <button className="secondary-button" type="button" disabled={busy} onClick={() => void givenNoDate()}>{t("markGivenNoDate")}</button>}
              {vaccine.askIfGiven && <button className="secondary-button" type="button" disabled={busy} onClick={() => void send({ status: "due", notes })}>{t("markNotYet")}</button>}
              <button className="secondary-button" type="button" disabled={busy} onClick={() => void send({ status: "not_given", notes })}>{t("markNotGiven")}</button>
            </div>
            <hr className="vaccine-rule" />
            <Field id="v-due" label={t("changeDate")} hint={t("asAdvised")}>
              <input id="v-due" type="date" min={recipient.dob ?? undefined} value={dueDate} onChange={(event) => setDueDate(event.target.value)} />
            </Field>
            <div className="stack">
              <button className="secondary-button" type="button" disabled={busy || !dueDate || dueDate === vaccine.dueDate} onClick={() => void send({ dueDate, notes })}>{t("saveDate")}</button>
              {vaccine.dueDateByFamily && vaccine.suggestedDueDate !== vaccine.dueDate && (
                <button className="inline-link" type="button" disabled={busy} onClick={() => void send({ resetDueDate: true, notes })}>{t("useSuggested", { date: fmt(vaccine.suggestedDueDate, language) })}</button>
              )}
            </div>
          </>
        ) : (
          <>
            <p className="vaccine-state">{vaccine.status === "given" && vaccine.givenDateUnknown ? t("givenNoDate")
              : vaccine.status === "given" && vaccine.givenOn ? t("givenOn", { date: fmt(vaccine.givenOn, language) }) : t("notGiven")}</p>
            {vaccine.status === "given" && vaccine.givenDateUnknown && (
              <>
                <Field id="v-given" label={t("addGivenDate")} error={error}>
                  <input id="v-given" type="date" min={recipient.dob ?? undefined} max={today} value={givenOn} onChange={(event) => { setGivenOn(event.target.value); setError(""); }}
                    aria-invalid={!!error} aria-describedby={error ? "v-given-error" : undefined} />
                </Field>
                <button className="secondary-button vaccine-gap" type="button" disabled={busy || !givenOn} onClick={markGiven}>{t("saveDate")}</button>
              </>
            )}
            <button className="secondary-button" type="button" disabled={busy} onClick={() => void send({ status: "due", notes })}>{t("reopen")}</button>
          </>
        )}
        <hr className="vaccine-rule" />
        <Field id="v-notes" label={t("notes")}>
          <textarea id="v-notes" rows={2} maxLength={300} placeholder={t("notesPlaceholder")} value={notes} onChange={(event) => setNotes(event.target.value)} />
        </Field>
        <button className="secondary-button" type="button" disabled={busy || notes === (vaccine.notes ?? "")} onClick={() => void send({ notes })}>{t("saveNotes")}</button>
      </section>
    </div>
  );
}

// ---------------------------------------------------------------- "Which ones were already given?"

/**
 * For a child set up after birth: every open dose whose date has passed, grouped by age, all UNticked (nothing is assumed).
 * Save = the ticked ones are given (date not recorded), the unticked ones "not given yet" (only then called overdue).
 * "Not sure" = save only what is ticked (if anything) and leave the rest to ask again later.
 */
function GivenEarlierStep({ recipient, doses, onDone }: { recipient: Recipient; doses: Vaccine[]; onDone: (saved: boolean) => void }) {
  const t = useTranslations("vaccines");
  const language = useLocale() as Language;
  const toast = useToast();
  const doseLabel = useDoseLabel();
  const ageLabel = useAgeLabel();
  const [ticked, setTicked] = useState<Set<string>>(() => new Set());
  const [busy, setBusy] = useState(false);
  const groups: { key: string; label: string; birth: boolean; items: Vaccine[] }[] = [];
  for (const v of doses) {
    const label = ageLabel(v.group) || fmt(v.dueDate, language);
    const last = groups[groups.length - 1];
    if (last && last.label === label) last.items.push(v); else groups.push({ key: `${v.group ?? ""}-${v.id}`, label, birth: v.group === "birth", items: [v] });
  }
  const toggle = (id: string, on: boolean) => setTicked((prev) => { const next = new Set(prev); if (on) next.add(id); else next.delete(id); return next; });
  const tickUpTo = (index: number) => setTicked((prev) => {
    const next = new Set(prev);
    for (const g of groups.slice(0, index + 1)) for (const v of g.items) next.add(v.id);
    return next;
  });
  const save = async (restNotYet: boolean) => {
    const given = doses.filter((v) => ticked.has(v.id)).map((v) => v.id);
    const notYet = restNotYet ? doses.filter((v) => !ticked.has(v.id)).map((v) => v.id) : [];
    if (!given.length && !notYet.length) { onDone(false); return; }
    setBusy(true);
    try { await vaccinesGivenEarlier(recipient.id, given, notYet); toast(t("saved")); onDone(true); }
    catch { toast(t("errSave")); }
    finally { setBusy(false); }
  };
  return (
    <form className="vaccine-given-step" onSubmit={(event) => { event.preventDefault(); void save(true); }} noValidate>
      <h2>{t("askTitle")}</h2>
      <p className="vaccine-given-note"><Icon name="shield" size={18} /> <span>{t("askNote")}</span></p>
      {groups.map((g, index) => (
        <fieldset key={g.key} className="vaccine-given-group">
          <legend>{g.label}</legend>
          <button type="button" className="inline-link" onClick={() => tickUpTo(index)}>{g.birth ? t("tickBirth") : t("tickUpTo", { age: g.label })}</button>
          {g.items.map((v) => (
            <label key={v.id} className="vaccine-given-row">
              <input type="checkbox" checked={ticked.has(v.id)} onChange={(event) => toggle(v.id, event.target.checked)} />
              <span>
                <strong>{vaccineName(v, language)}{v.dose && <em> · {doseLabel(v.dose)}</em>}</strong>
                <small>{t("suggestedOn", { date: fmt(v.dueDate, language) })}</small>
              </span>
            </label>
          ))}
        </fieldset>
      ))}
      <p className="vaccine-given-hint">{t("askUnticked")}</p>
      <p className="vaccine-given-hint">{t("askDoctor")}</p>
      <div className="stack">
        <button className="primary-button" type="submit" disabled={busy}>{busy ? t("making") : t("askSave")}</button>
        <button className="secondary-button" type="button" disabled={busy} onClick={() => void save(false)}>{ticked.size ? t("askNotSureTicked") : t("askNotSure")}</button>
      </div>
    </form>
  );
}

// ---------------------------------------------------------------- the screen

export function VaccinesScreen({ go, recipient }: { go: (screen: "family") => void; recipient: Recipient | null }) {
  const t = useTranslations("vaccines");
  const language = useLocale() as Language;
  const doseLabel = useDoseLabel();
  const ageLabel = useAgeLabel();
  const [info, setInfo] = useState<ChildInfo | null>(null);
  const [list, setList] = useState<Vaccine[] | null>(null);
  const [error, setError] = useState(false);
  const [open, setOpen] = useState<Vaccine | null>(null);
  const [editingDob, setEditingDob] = useState(false);
  const [showDone, setShowDone] = useState(false);
  const [asking, setAsking] = useState(false);
  const recipientId = recipient?.id;
  const refresh = async (): Promise<Vaccine[] | null> => {
    if (!recipientId) return null;
    try {
      const [i, l] = await Promise.all([loadChild(recipientId), loadVaccines(recipientId)]);
      setInfo(i); setList(l); setError(false);
      return l;
    } catch { setError(true); return null; }
  };
  useEffect(() => { setInfo(null); setList(null); setAsking(false); void refresh(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [recipientId]);
  if (!recipient) return null;
  const call = parentCall(recipient, language);
  const ready = info !== null && list !== null;
  const needsSetup = ready && (!info.guardianConfirmedAt || !info.dob || list.length === 0);

  const card = (v: Vaccine) => (
    <button key={v.id} type="button" className={`vaccine-card ${v.status}${v.askIfGiven ? " ask" : ""}`} onClick={() => setOpen(v)}>
      <span className="vaccine-icon" aria-hidden="true"><Icon name={v.status === "given" ? "check" : "calendar"} size={18} /></span>
      <span className="vaccine-body">
        <strong>{vaccineName(v, language)}{v.dose && <em> · {doseLabel(v.dose)}</em>}</strong>
        <small>
          {v.status === "given" && v.givenDateUnknown ? t("givenNoDate")
            : v.status === "given" && v.givenOn ? t("givenOn", { date: fmt(v.givenOn, language) })
            : v.status === "not_given" ? t("notGiven")
            : v.status === "overdue" && v.askIfGiven ? t("suggestedAsk", { date: fmt(v.dueDate, language) })
            : v.status === "overdue" ? t("overdueSince", { date: fmt(v.dueDate, language) })
            : t("dueOn", { date: fmt(v.dueDate, language) })}
          {v.dueDateByFamily && v.status !== "given" && v.status !== "not_given" ? ` · ${t("changedByFamily")}` : ""}
        </small>
      </span>
      <Icon name="chevron" size={18} />
    </button>
  );
  // Past their date: "ask" until the family said "not given yet" (never called overdue before that); "overdue" after.
  const pastOpen = (list ?? []).filter((v) => v.status === "overdue");
  const ask = pastOpen.filter((v) => v.askIfGiven);
  const overdue = pastOpen.filter((v) => !v.askIfGiven);
  const soon = (list ?? []).filter((v) => v.status === "due_soon");
  const later = (list ?? []).filter((v) => v.status === "later");
  const done = (list ?? []).filter((v) => v.status === "given" || v.status === "not_given");
  const laterGroups: { label: string; items: Vaccine[] }[] = [];
  for (const v of later) {
    const label = ageLabel(v.group) || fmt(v.dueDate, language);
    const last = laterGroups[laterGroups.length - 1];
    if (last && last.label === label) last.items.push(v); else laterGroups.push({ label, items: [v] });
  }

  return (
    <main className="scroll-page no-nav vaccines-page">
      <AppHeader title={t("title")} subtitle={t("subtitle", { call })} back={() => go("family")} />
      {error && <p role="alert" className="form-error">{t("loadFailed")}</p>}
      {!ready && !error && <div className="skeleton card" aria-busy="true" />}
      {ready && (info.isSelf || (info.careType !== null && info.careType !== "child")) && <div className="empty-note"><p>{t("notChild")}</p></div>}
      {ready && !info.isSelf && (info.careType === null || info.careType === "child") && (
        <>
          {(needsSetup || editingDob)
            ? <ChildSetup recipient={recipient} info={info} onCancel={editingDob ? () => setEditingDob(false) : undefined}
                onDone={() => { setEditingDob(false); void refresh().then((l) => { if (l?.some((v) => v.askIfGiven)) setAsking(true); }); }} />
            : asking && pastOpen.length > 0
            ? <GivenEarlierStep recipient={recipient} doses={pastOpen} onDone={(saved) => { setAsking(false); if (saved) void refresh(); }} />
            : (
              <>
                <div className="vaccine-dob">
                  <span>{t("dobLine", { date: fmt(info.dob as string, language) })}</span>
                  <button type="button" className="inline-link" onClick={() => setEditingDob(true)}>{t("changeDob")}</button>
                </div>
                <p className="vaccine-safety">
                  {t("safety", { edition: info.edition, call })}
                  {!info.reviewedByPaediatrician && <strong> {t("notReviewed")}</strong>}
                </p>
                {ask.length > 0 && (
                  <section className="vaccine-section ask" aria-labelledby="v-ask">
                    <h2 id="v-ask">{t("askSection")}</h2>
                    <div className="vaccine-ask-banner">
                      <p>{t("askBanner")}</p>
                      <button type="button" className="primary-button" onClick={() => setAsking(true)}>{t("askButton")}</button>
                    </div>
                    {ask.map(card)}
                  </section>
                )}
                {overdue.length > 0 && (
                  <section className="vaccine-section overdue" aria-labelledby="v-overdue">
                    <h2 id="v-overdue">{t("overdue")}</h2>
                    {overdue.map(card)}
                    {ask.length === 0 && <button type="button" className="inline-link vaccine-ask-link" onClick={() => setAsking(true)}>{t("askEdit")}</button>}
                  </section>
                )}
                <section className="vaccine-section" aria-labelledby="v-soon"><h2 id="v-soon">{t("dueSoon")}</h2>{soon.length ? soon.map(card) : <p className="vaccine-empty">{t("emptyNext")}</p>}</section>
                {laterGroups.length > 0 && (
                  <section className="vaccine-section" aria-labelledby="v-later">
                    <h2 id="v-later">{t("later")}</h2>
                    {laterGroups.map((g) => <div key={g.label} className="vaccine-group"><h3>{g.label}</h3>{g.items.map(card)}</div>)}
                  </section>
                )}
                {done.length > 0 && (
                  <>
                    <button type="button" className="inline-link vaccine-done-toggle" aria-expanded={showDone} onClick={() => setShowDone((s) => !s)}>{showDone ? t("hideDone") : t("showDone", { count: done.length })}</button>
                    {showDone && <section className="vaccine-section done" aria-label={t("done")}>{done.map(card)}</section>}
                  </>
                )}
              </>
            )}
        </>
      )}
      {open && <VaccineSheet recipient={recipient} vaccine={open} onClose={() => setOpen(null)} onSaved={() => { setOpen(null); void refresh(); }} />}
    </main>
  );
}

// ---------------------------------------------------------------- entry points (shown only for a child)

/** The Family tab entry. Shows nothing unless the profile is a child (care type from the care-type branch). */
export function VaccinesLink({ go, recipient }: { go: (screen: "vaccines") => void; recipient: Recipient | null }) {
  const t = useTranslations("vaccines");
  const language = useLocale() as Language;
  if (!isChild(recipient)) return null;
  return (
    <button className="feature-link" onClick={() => go("vaccines")}>
      <Icon3D name="vaccine" />
      <div><strong>{t("linkTitle")}</strong><p>{t("linkSub", { call: parentCall(recipient, language) })}</p></div>
      <Icon name="chevron" />
    </button>
  );
}

/** A small card on Today: the next open vaccine, or an invitation to set the plan up. Only for a child. */
export function NextVaccine({ go, recipient }: { go: (screen: "vaccines") => void; recipient: Recipient | null }) {
  const t = useTranslations("vaccines");
  const language = useLocale() as Language;
  const doseLabel = useDoseLabel();
  const child = isChild(recipient);
  const recipientId = recipient?.id;
  const [next, setNext] = useState<Vaccine | null | undefined>(undefined);
  useEffect(() => {
    if (!child || !recipientId) return;
    let cancelled = false;
    loadVaccines(recipientId)
      .then((list) => { if (!cancelled) setNext(list.find((v) => v.status === "overdue" || v.status === "due_soon" || v.status === "later") ?? null); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [child, recipientId]);
  if (!child || next === undefined) return null;
  return (
    <button className={`next-visit${next ? "" : " empty"}`} onClick={() => go("vaccines")}>
      <CardIcon name="syringe" />
      <span className="next-visit-text">
        <strong>{next ? `${vaccineName(next, language)}${next.dose ? ` · ${doseLabel(next.dose)}` : ""}` : t("nextNone", { call: parentCall(recipient, language) })}</strong>
        {next && <em>{t("dueOn", { date: fmt(next.dueDate, language) })}</em>}
      </span>
      <Icon name="chevron" size={18} />
    </button>
  );
}
