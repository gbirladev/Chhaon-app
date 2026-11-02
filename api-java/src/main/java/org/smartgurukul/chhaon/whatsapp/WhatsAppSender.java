package org.smartgurukul.chhaon.whatsapp;

/**
 * Boundary for sending WhatsApp messages. The real Cloud API implementation is a separate port;
 * until then {@link DryRunWhatsAppSender} logs and returns a fake message id. The scheduler's
 * claim/revert logic around these calls is the real contract, not the send itself.
 */
public interface WhatsAppSender {

    /** @return provider message id on success. Throws on failure (caller reverts + retries). */
    String sendTemplate(String toPhone, String template, String lang, Object... params);

    /** Whether a real provider is configured (false = dry-run). */
    boolean isLive();
}
