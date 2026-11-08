import { useEffect, useState } from "react";
import { useTranslations } from "use-intl";
import { loadMembers, type Recipient } from "../lib/api";
import { authUserId } from "../lib/auth";
import { isChild } from "../lib/vaccines";
import { InviteSheet } from "./Invites";
import "./consentInvite.css";

/** How long after the person's "yes" the card is offered (then it would be old news). */
const FRESH_DAYS = 14;
const key = (userId: string, recipientId: string) => `chhaon-consent-invite-${userId}-${recipientId}`;

function dismissed(userId: string, recipientId: string): boolean {
  try { return localStorage.getItem(key(userId, recipientId)) !== null; } catch { return false; }
}
function remember(userId: string, recipientId: string) {
  try { localStorage.setItem(key(userId, recipientId), "1"); } catch { /* storage blocked: the card may show again */ }
}

/**
 * "Papa said yes on WhatsApp, invite your brother or sister" on Today: shown once, soon after the person agreed, only to the circle's
 * owner while they are its only member. Opens the existing family invite sheet. "Not now" or a created invite hides it for good
 * (per caregiver and per person, on this device).
 */
export function ConsentInviteCard({ recipient, call }: { recipient: Recipient | null; call: string }) {
  const t = useTranslations("shareLoops");
  const userId = authUserId();
  const recipientId = recipient?.id ?? null;
  const circleId = recipient?.circle_id ?? null;
  const consentAt = recipient?.consent_at && !recipient.opted_out_at ? recipient.consent_at : null;
  const eligible = !!userId && !!recipientId && !!circleId && !!consentAt && !recipient?.is_self && !isChild(recipient);
  /** The person the card is shown for (null: not shown). */
  const [shownFor, setShownFor] = useState<string | null>(null);
  const [sheet, setSheet] = useState(false);

  useEffect(() => {
    if (!eligible || !userId || !recipientId || !circleId || !consentAt) return;
    const at = Date.parse(consentAt);
    if (!Number.isFinite(at) || Date.now() - at > FRESH_DAYS * 86_400_000 || dismissed(userId, recipientId)) return;
    let cancelled = false;
    loadMembers(circleId).then((members) => {
      if (!cancelled && members.length === 1 && members[0].userId === userId && members[0].role === "owner") setShownFor(recipientId);
    }).catch(() => {});
    return () => { cancelled = true; };
  }, [eligible, userId, recipientId, circleId, consentAt]);

  if (!eligible || !userId || !recipientId || !circleId) return null;
  const hidden = shownFor !== recipientId;
  if (hidden && !sheet) return null;
  const close = () => { remember(userId, recipientId); setShownFor(null); };
  return (
    <>
      {!hidden && (
        <section className="consent-invite" role="note" aria-label={t("consentTitle", { call })}>
          <h3>{t("consentTitle", { call })}</h3>
          <p>{t("consentBody", { call })}</p>
          <div className="consent-invite-actions">
            <button type="button" className="primary-button" onClick={() => setSheet(true)}>{t("consentInvite")}</button>
            <button type="button" className="text-button" onClick={close}>{t("consentNotNow")}</button>
          </div>
        </section>
      )}
      {sheet && <InviteSheet circleId={circleId} call={call} onClose={() => setSheet(false)} onCreated={close} />}
    </>
  );
}
