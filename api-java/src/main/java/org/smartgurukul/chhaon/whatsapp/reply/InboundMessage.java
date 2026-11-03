package org.smartgurukul.chhaon.whatsapp.reply;

/**
 * A parent's message as the reply pipeline sees it.
 *
 * @param messageId the gateway's message id (idempotency key)
 * @param phone     the sender in E.164 ("+919876543210")
 * @param text      what was typed (never logged)
 * @param quotedId  the id of the message this one replies to (swipe-reply), or null
 */
public record InboundMessage(String messageId, String phone, String text, String quotedId) {
    @Override
    public String toString() {
        // Never print the text or the full number.
        return "InboundMessage[id=" + messageId + "]";
    }
}
