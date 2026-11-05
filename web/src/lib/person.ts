import { useTranslations } from "use-intl";
import type { Recipient } from "./api";
import { authUserId } from "./auth";
import { careTypeOf, pregnancyStatus, todayIn } from "./pregnancy";
import { plainDate } from "./vaccines";
import { receiverName, receiverOf } from "./reminderReceiver";

/** The kind of person, for the avatar colour and the corner icon (screens/PersonBadge.tsx). */
export type PersonKind = "older" | "pregnant" | "child" | "self";

export const personKindOf = (recipient: Pick<Recipient, "care_type" | "is_self"> | null | undefined): PersonKind =>
  recipient?.is_self ? "self" : careTypeOf(recipient);

/** From the activity feed's people ("older" | "pregnant" | "child" | null, isSelf). */
export const personKindFrom = (careType: string | null | undefined, isSelf: boolean): PersonKind =>
  isSelf ? "self" : careType === "pregnant" || careType === "child" ? careType : "older";

export const initialOf = (name: string) => ([...name.trim()][0] ?? "?").toUpperCase();


const dayNumber = (iso: string) => Math.round(plainDate(iso).getTime() / 86_400_000);
function monthsBetween(from: string, to: string): number {
  const [fy, fm, fd] = from.split("-").map(Number);
  const [ty, tm, td] = to.split("-").map(Number);
  return (ty - fy) * 12 + (tm - fm) - (td < fd ? 1 : 0);
}

/**
 * The second line of a person in the switcher: what kind of care and who gets the WhatsApp reminders.
 * "Pregnant · week 24", "5 months · reminders to Kamla (Nani)", "68 years · reminders to Sunita (maid)", "Older parent · reminders to Papa".
 */
export function usePersonLine() {
  const t = useTranslations("person");
  const tc = useTranslations("childHome");
  return (r: Recipient, call: string): string => {
    if (r.is_self) return t("selfLine");
    const kind = careTypeOf(r);
    const today = todayIn(r.timezone || "Asia/Kolkata");
    let first: string;
    if (kind === "pregnant") {
      const week = r.due_date ? pregnancyStatus(r.due_date, today)?.week ?? null : null;
      first = week ? t("pregnantWeek", { week }) : t("pregnantLine");
    } else if (kind === "child") {
      const dob = r.dob;
      if (!dob || dob > today) first = t("child");
      else {
        const days = dayNumber(today) - dayNumber(dob);
        const months = monthsBetween(dob, today);
        first = days === 0 ? tc("newborn") : days < 14 ? tc("days", { count: days }) : days < 60 ? tc("weeks", { count: Math.floor(days / 7) })
          : months < 24 ? tc("months", { count: months }) : tc("years", { count: Math.floor(months / 12) });
      }
    } else {
      first = typeof r.age === "number" && r.age > 0 ? t("ageYears", { age: r.age }) : t("older");
    }
    const rcv = receiverOf(r);
    let to = "";
    if (rcv.reminder_to === "caregiver") to = rcv.reminder_user_id && rcv.reminder_user_id === authUserId() ? t("toYou") : t("toName", { name: receiverName(rcv) || t("someone") });
    else if (rcv.reminder_to === "helper") to = t("toName", { name: receiverName(rcv) || t("someone") });
    else if (kind === "older" && r.whatsapp_phone) to = t("toName", { name: call });
    return to ? `${first} · ${to}` : first;
  };
}
