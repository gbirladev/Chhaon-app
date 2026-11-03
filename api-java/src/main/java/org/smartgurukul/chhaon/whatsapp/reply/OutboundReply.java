package org.smartgurukul.chhaon.whatsapp.reply;

/** A free-form reply to send AFTER the database work has committed. */
public record OutboundReply(String phone, String text) {
    @Override
    public String toString() {
        return "OutboundReply[to=" + org.smartgurukul.chhaon.whatsapp.WhatsAppSender.mask(phone) + "]";
    }
}
