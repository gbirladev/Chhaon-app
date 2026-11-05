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

type ReceiverDto = { reminderTo?: string | null; reminderName?: string | null; reminderRelation?: string | null; reminderUserId?: string | null };

const asReminderTo = (value: unknown): ReminderTo => (value === "caregiver" || value === "helper" ? value : "person");

/** The Java API's camelCase fields as row fields (an older backend without them means "the person", as before). */
export function receiverRow(d: unknown): ReceiverFields {
  const dto = (d ?? {}) as ReceiverDto;
  return {
    reminder_to: asReminderTo(dto.reminderTo),
    reminder_name: dto.reminderName ?? null,
    reminder_relation: dto.reminderRelation ?? null,
    reminder_user_id: dto.reminderUserId ?? null,
  };
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
