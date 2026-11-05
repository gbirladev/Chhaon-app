// Who gets the WhatsApp reminders for a person (migration 20261105000044, docs/reminder-receiver.md).
// whatsapp_phone stays "the number that receives the reminders"; these fields say whose number it is.
import type { Recipient } from "./api";

export type ReminderTo = "person" | "caregiver" | "helper";

export type ReceiverFields = {
  reminder_to: ReminderTo;
  reminder_name: string | null;       // the helper's name, or the caregiver's own name
  reminder_relation: string | null;   // a helper's relation in the family's words: maid, Nani, nanny ...
  reminder_user_id: string | null;    // the family member who gets them (reminder_to = caregiver)
};

/** Per-person settings of round 2 (migration 20261105000050). */
export type PersonSettings = {
  checkin_enabled: boolean;           // the daily "How are you?" check-in (only sent when the person gets the reminders)
  reminder_language: "en" | "hi" | null;   // the language for a helper / family member who gets them; null = the person's
};

type ReceiverDto = {
  reminderTo?: string | null; reminderName?: string | null; reminderRelation?: string | null; reminderUserId?: string | null;
  checkinEnabled?: boolean | null; reminderLanguage?: string | null;
};

const asReminderTo = (value: unknown): ReminderTo => (value === "caregiver" || value === "helper" ? value : "person");
const asLanguage = (value: unknown): "en" | "hi" | null => (value === "en" || value === "hi" ? value : null);

/** The Java API's camelCase fields as row fields (an older backend without them means "the person", as before; check-in on). */
export function receiverRow(d: unknown): ReceiverFields & PersonSettings {
  const dto = (d ?? {}) as ReceiverDto;
  return {
    reminder_to: asReminderTo(dto.reminderTo),
    reminder_name: dto.reminderName ?? null,
    reminder_relation: dto.reminderRelation ?? null,
    reminder_user_id: dto.reminderUserId ?? null,
    checkin_enabled: dto.checkinEnabled !== false,
    reminder_language: asLanguage(dto.reminderLanguage),
  };
}

/** The round-2 settings of a loaded person (check-in on, no receiver language when unknown). */
export function settingsOf(recipient: Recipient | null): PersonSettings {
  const r = (recipient ?? {}) as Partial<PersonSettings>;
  return { checkin_enabled: r.checkin_enabled !== false, reminder_language: asLanguage(r.reminder_language) };
}

/**
 * Is there a daily "How are you?" check-in for this person? Only when the person gets the reminders themself, it is switched on,
 * and it is neither "Me" nor a child (a child never gets messages).
 */
export function hasCheckin(recipient: Recipient | null): boolean {
  if (!recipient || recipient.is_self) return false;
  if ((recipient as { care_type?: string }).care_type === "child") return false;
  return receiverOf(recipient).reminder_to === "person" && settingsOf(recipient).checkin_enabled;
}

/** Does the signed-in user (me) get this person's reminders? */
export function receiverIsMe(recipient: Recipient | null, me: string | null): boolean {
  const r = receiverOf(recipient);
  return !!recipient && !recipient.is_self && r.reminder_to === "caregiver" && !!me && r.reminder_user_id === me;
}

/**
 * Who receives the WhatsApp reminders, in words: "Kamla (Nani)" (a helper or another family member), "you" (me), or the
 * person's own name as the family calls them ("Shivani"). `words.call` is what the family calls the person (parentCall),
 * `words.you` the word for "you" in the sentence's language, `words.someone` the fallback when no name is stored.
 * Pass me = null for text that leaves the app (a doctor summary): then a family member is named, never "you".
 */
export function receiverLabel(recipient: Recipient | null, me: string | null, words: { call: string; you: string; someone: string }): string {
  const r = receiverOf(recipient);
  if (!recipient || recipient.is_self || r.reminder_to === "person") return words.call;
  if (receiverIsMe(recipient, me)) return words.you;
  return receiverName(r) || words.someone;
}

/** ICU values for a sentence about the receiver: {name} and {me, select, yes {...} other {...}} (grammar differs for "you"). */
export function receiverValues(recipient: Recipient | null, me: string | null, words: { call: string; you: string; someone: string }) {
  return { name: receiverLabel(recipient, me, words), me: receiverIsMe(recipient, me) ? "yes" : "no" };
}

/** The receiver fields of a loaded person (defaults to "the person themself"). */
export function receiverOf(recipient: Recipient | null): ReceiverFields {
  const r = (recipient ?? {}) as Partial<ReceiverFields>;
  return {
    reminder_to: asReminderTo(r.reminder_to),
    reminder_name: r.reminder_name ?? null,
    reminder_relation: r.reminder_relation ?? null,
    reminder_user_id: r.reminder_user_id ?? null,
  };
}

/** "Sunita (maid)" / "Ganesh"; "" for the person themself or when no name is known. */
export function receiverName(fields: ReceiverFields): string {
  if (fields.reminder_to === "person") return "";
  const name = (fields.reminder_name ?? "").trim();
  if (fields.reminder_to === "caregiver") return name.split(/\s+/)[0] ?? "";
  const relation = (fields.reminder_relation ?? "").trim();
  return relation && name ? `${name} (${relation})` : name;
}

/**
 * "Who gets the WhatsApp reminders?" on the form. "other" = another family member already gets them: shown so a sibling who
 * opens the form does not take them over by saving.
 */
export type ReceiverMode = "person" | "caregiver" | "helper" | "other";

/** The mode the form starts in for a stored person, seen by the signed-in user. */
export function initialReceiverMode(recipient: Recipient | null, userId: string | null): ReceiverMode {
  const r = receiverOf(recipient);
  if (r.reminder_to === "caregiver") return r.reminder_user_id && r.reminder_user_id === userId ? "caregiver" : "other";
  return r.reminder_to;
}
