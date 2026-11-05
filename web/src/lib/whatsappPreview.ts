import { apiFetch } from "./http";

/** One chat bubble, worded by the SAME Java code as the real WhatsApp send (WhatsAppPreviewController). */
export type PreviewMessage = {
  kind: "consent" | "dose" | "checkin" | "vaccine" | "alert";
  /** receiver = the number that gets the reminders; family = an alert to a family member */
  to: "receiver" | "family";
  text: string;
  at: string | null;
  photo: boolean;
};

export type WhatsAppPreview = {
  receiverKind: "person" | "caregiver" | "helper";
  /** "Kamla (Nani)", "Papa", a family member's first name, or "you" / "आप" (receiverIsYou) */
  receiverName: string;
  receiverIsYou: boolean;
  /** the language the receiver reads */
  language: "en" | "hi";
  consent: "agreed" | "waiting" | "stopped" | "no_number";
  checkin: boolean;
  messages: PreviewMessage[];
  /** the reminder text of every active medicine, for the reminders really sent */
  doses: { medicine: string; text: string }[];
};

export const loadWhatsAppPreview = (recipientId: string) =>
  apiFetch<WhatsAppPreview>(`/api/v1/recipients/${recipientId}/whatsapp-preview`);

/** A WhatsApp text split into lines and *bold* runs, as WhatsApp shows it. Never HTML: React escapes every piece. */
export function waLines(text: string): { bold: boolean; text: string }[][] {
  return text.split("\n").map((line) =>
    line.split(/(\*[^*\n]+\*)/).filter(Boolean).map((part) =>
      part.length > 2 && part.startsWith("*") && part.endsWith("*") ? { bold: true, text: part.slice(1, -1) } : { bold: false, text: part }));
}
