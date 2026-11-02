package org.smartgurukul.chhaon.whatsapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Dry-run sender: logs the intent and returns a synthetic message id. Active until the real
 * WhatsApp Cloud API sender is added (which will replace this bean).
 */
@Component
@ConditionalOnMissingBean(name = "liveWhatsAppSender")
public class DryRunWhatsAppSender implements WhatsAppSender {

    private static final Logger log = LoggerFactory.getLogger(DryRunWhatsAppSender.class);

    @Override
    public String sendTemplate(String toPhone, String template, String lang, Object... params) {
        log.info("[WA dry-run] template={} lang={} to={} (not actually sent)", template, lang,
                mask(toPhone));
        return "dryrun-" + UUID.randomUUID();
    }

    @Override
    public boolean isLive() {
        return false;
    }

    /** Never log full phone numbers. */
    private static String mask(String phone) {
        if (phone == null || phone.length() < 4) return "****";
        return "***" + phone.substring(phone.length() - 3);
    }
}
