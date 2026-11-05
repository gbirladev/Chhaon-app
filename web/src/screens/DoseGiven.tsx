import { useState } from "react";
import { useTranslations } from "use-intl";
import { Icon, useDialog, useToast } from "../App";
import type { Recipient, TodayDose } from "../lib/api";
import { formatClock, localizeDose } from "../lib/format";
import { receiverName, receiverOf } from "../lib/reminderReceiver";
import { canMarkGiven, canUndo, isManual, markDoseGiven, undoDoseGiven, type GivenSource } from "../lib/doseGiven";
import "./doseGiven.css";

type Option = { source: GivenSource; label: string };

/**
 * Under Today's medicines: every dose that is waiting or missed gets "Mark as given" (and "Call" while it is waiting), and a dose a
 * family member marked shows "Marked given by Arjun, 8:41 AM" with Undo for 10 minutes. Nothing shows when there is nothing to do.
 */
export function DoseGivenList({ doses, recipient, call, tz, language, userId, onChanged }: {
  doses: TodayDose[]; recipient: Recipient; call: string; tz: string; language: string; userId: string | null; onChanged: () => void;
}) {
  const t = useTranslations("doseGiven");
  const tAll = useTranslations();
  const toast = useToast();
  const [open, setOpen] = useState<TodayDose | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const rows = doses.filter((d) => canMarkGiven(d) || isManual(d.confirmSource));
  if (rows.length === 0) return null;

  const r = receiverOf(recipient);
  const receiverIsMe = r.reminder_to === "caregiver" && !!userId && r.reminder_user_id === userId;
  const helper = r.reminder_to === "helper" || (r.reminder_to === "caregiver" && !receiverIsMe) ? receiverName(r) || t("someone") : null;
  // Dial whoever receives the reminders (the person, a helper, another family member); never yourself.
  const phone = recipient.is_self || receiverIsMe ? null : recipient.whatsapp_phone ?? null;
  const callName = r.reminder_to === "person" ? call : helper ?? call;
  const options: Option[] = recipient.is_self
    ? [{ source: "caregiver_in_person", label: t("optSelf") }]
    : [
        { source: "caregiver_told", label: helper ? t("optHelperTold", { helper }) : t("optPersonTold", { call }) },
        { source: "caregiver_in_person", label: t("optInPerson") },
      ];
  const time = (iso: string) => formatClock(new Date(iso), tz, language);

  const save = async (dose: TodayDose, source: GivenSource) => {
    setBusy(dose.id);
    try {
      const res = await markDoseGiven(dose.id, source);
      toast(res.result === "marked" ? t("marked", { name: dose.name })
        : res.result === "already" ? t(res.source === "whatsapp" ? "alreadyWhatsapp" : "alreadyMarked")
        : res.result === "too_early" ? t("tooEarly") : res.result === "too_old" ? t("tooOld") : t("notAllowed"));
      setOpen(null);
      onChanged();
    } catch {
      toast(t("failed"));
    } finally {
      setBusy(null);
    }
  };

  const undo = async (dose: TodayDose) => {
    setBusy(dose.id);
    try {
      const res = await undoDoseGiven(dose.id);
      toast(res.result === "undone" ? t("undone") : res.result === "too_late" ? t("undoTooLate") : t("undoNotPossible"));
      onChanged();
    } catch {
      toast(t("failed"));
    } finally {
      setBusy(null);
    }
  };

  return (
    <section className="dose-given" aria-label={t("title")}>
      <h3>{t("title")}</h3>
      <ul>
        {rows.map((d) => {
          const manual = isManual(d.confirmSource);
          const waiting = d.status === "pending" || d.status === "sent";
          const mine = !!d.confirmedBy && d.confirmedBy === userId;
          const who = (d.confirmedByName ?? "").trim().split(/\s+/)[0] || t("someone");
          const at = d.respondedAt ? time(d.respondedAt) : "";
          return (
            <li key={d.id} className={manual ? "done" : d.status === "missed" ? "missed" : "waiting"}>
              <div className="dg-main">
                <strong>{d.name}{d.dosage && <small> {localizeDose(d.dosage, tAll)}</small>}</strong>
                {manual ? (
                  <p>{mine ? t("markedByYou", { time: at }) : t("markedBy", { name: who, time: at })}{d.confirmSource === "caregiver_told" && <> · {t("toldSuffix")}</>}</p>
                ) : (
                  <p>{time(d.scheduledAt)} · {tAll(d.status === "missed" ? "today.missed" : d.status === "skipped" ? "today.notSent" : d.status === "sent" ? "today.pending" : "today.due")}</p>
                )}
              </div>
              <div className="dg-actions">
                {manual && canUndo(d, userId) && <button className="dg-undo" disabled={busy === d.id} onClick={() => void undo(d)}>{t("undo")}</button>}
                {!manual && waiting && phone && (
                  <a className="dg-call" href={`tel:${phone}`} aria-label={t("callLabel", { name: callName })}><Icon name="phone" size={17} /><span>{t("call")}</span></a>
                )}
                {!manual && <button className="dg-mark" disabled={busy === d.id} onClick={() => setOpen(d)}>{t("mark")}</button>}
              </div>
            </li>
          );
        })}
      </ul>
      {open && <GivenSheet dose={open} options={options} busy={busy === open.id} time={time(open.scheduledAt)} onSave={(s) => void save(open, s)} onClose={() => setOpen(null)} />}
    </section>
  );
}

function GivenSheet({ dose, options, busy, time, onSave, onClose }: {
  dose: TodayDose; options: Option[]; busy: boolean; time: string; onSave: (source: GivenSource) => void; onClose: () => void;
}) {
  const t = useTranslations("doseGiven");
  const dialog = useDialog(onClose);
  const [choice, setChoice] = useState<GivenSource>(options[0].source);
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <section className="sheet dose-given-sheet" ref={dialog} tabIndex={-1} role="dialog" aria-modal="true" aria-label={t("sheetTitle", { name: dose.name })} onClick={(event) => event.stopPropagation()}>
        <h3>{t("sheetTitle", { name: dose.name })}</h3>
        <p className="sheet-hint">{t("sheetHint", { time })}</p>
        <div className="dg-options" role="radiogroup" aria-label={t("sheetTitle", { name: dose.name })}>
          {options.map((o) => (
            <button key={o.source} role="radio" aria-checked={choice === o.source} className={choice === o.source ? "on" : ""} onClick={() => setChoice(o.source)}>
              <span className="dg-radio" aria-hidden="true" />{o.label}
            </button>
          ))}
        </div>
        <div className="dg-sheet-actions">
          <button className="primary-button" disabled={busy} onClick={() => onSave(choice)}>{t("save")}</button>
          <button className="secondary-button" onClick={onClose}>{t("cancel")}</button>
        </div>
      </section>
    </div>
  );
}
