package org.smartgurukul.chhaon.whatsapp.reply;

/**
 * What a parent's typed reply says, on its own (without knowing what it answers; the
 * {@link ReplyRouter} decides that).
 */
public enum Intent {
    /** Opt out of everything (STOP). Always wins. */
    STOP,
    /** Opt back in (START). */
    START,
    /** "2", "no", "nahi": declines, or "not yet" / "call me" depending on what it answers. */
    NO,
    /** "1", "yes", "haan", a green tick or thumbs up: agrees / taken / I am okay, depending on context. */
    YES,
    /** A bare "ok", "theek hai": an acknowledgement. Never counts as "taken" (the parent may only be acknowledging the reminder). */
    ACK,
    /** "taken", "le li", "liya": the medicine was taken. */
    TAKEN,
    /** "all taken", "sab le li": every medicine due at that time was taken. */
    TAKEN_ALL,
    /** "I am okay", "theek hoon": the answer to the daily check-in. */
    CHECKIN_OK,
    /** "call me", "phone karo": the parent wants a call. */
    CHECKIN_CALL_ME,
    /** "thanks", a folded-hands emoji: friendly, needs no answer. */
    THANKS,
    /** Anything else. */
    UNKNOWN
}
