package org.smartgurukul.chhaon.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartgurukul.chhaon.scheduler.SchedulerService;
import org.smartgurukul.chhaon.whatsapp.reply.CircleNotifier;
import org.smartgurukul.chhaon.whatsapp.reply.InboundMessage;
import org.smartgurukul.chhaon.whatsapp.reply.Intent;
import org.smartgurukul.chhaon.whatsapp.reply.OutboundReply;
import org.smartgurukul.chhaon.whatsapp.reply.ReplyClassifier;
import org.smartgurukul.chhaon.whatsapp.reply.ReplyHandlers;
import org.smartgurukul.chhaon.whatsapp.reply.ReplyRouter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Processes the events the WhatsApp gateway posts to {@code /webhooks/whatsapp-gateway}
 * (see docs/java-whatsapp-replies.md for the payload).
 *
 * <p><b>Idempotency</b> (like {@code _claim_event} in the Python webhook): the gateway message id is claimed in
 * {@code whatsapp_events} INSIDE the same transaction as the database work, so a duplicate delivery is skipped, and a
 * failure rolls everything back, claim included (nothing is half done and a later delivery can run again). Replies to
 * the parent are sent AFTER the commit; a failed reply is logged, never retried, never undoes the work.
 *
 * <p><b>Error policy:</b> an event the database rejects as malformed (constraint / data errors) is skipped and its claim
 * kept (retrying can never fix it); anything else (database down) propagates so the caller answers 500.
 */
@Service
public class GatewayEventService {

    private static final Logger log = LoggerFactory.getLogger(GatewayEventService.class);

    private static final Pattern PHONE_DIGITS = Pattern.compile("[1-9][0-9]{7,14}");
    private static final int MAX_TEXT = 1000;

    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final ReplyRouter router;
    private final ReplyHandlers handlers;
    private final CircleNotifier notifier;
    private final WhatsAppSender sender;
    private final SchedulerService scheduler;

    public GatewayEventService(TransactionTemplate tx, JdbcTemplate jdbc, ReplyRouter router, ReplyHandlers handlers,
                               CircleNotifier notifier, WhatsAppSender sender, SchedulerService scheduler) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.router = router;
        this.handlers = handlers;
        this.notifier = notifier;
        this.sender = sender;
        this.scheduler = scheduler;
    }

    /** @return a short outcome label (for tests and logs), never anything personal. */
    public String process(JsonNode root) {
        String event = text(root, "event");
        JsonNode data = root.path("data");
        return switch (event == null ? "" : event) {
            case "message.received" -> received(data);
            case "message.status" -> status(data);
            default -> "ignored:event"; // message.sent, connection.update, test, ...
        };
    }

    // ---------------------------------------------------------------- message.received

    private String received(JsonNode d) {
        JsonNode key = d.path("key");
        String id = text(key, "id");
        if (id == null) return "ignored:no-id"; // nothing to deduplicate on

        String remoteJid = text(key, "remoteJid");
        String chatType = text(d, "chatType");
        if (d.path("isGroup").asBoolean(false)
                || (chatType != null && !"PERSONAL".equalsIgnoreCase(chatType))
                || (remoteJid != null && (remoteJid.endsWith("@g.us") || remoteJid.endsWith("@newsletter")
                        || remoteJid.equals("status@broadcast")))) {
            return "ignored:not-one-to-one";
        }
        if (key.path("fromMe").asBoolean(false)) return "ignored:from-me";

        String type = text(d, "type");
        if ("REACTION".equalsIgnoreCase(type)) return reaction(d, id, remoteJid);
        String content = text(d, "content");
        if (type != null && !"TEXT".equalsIgnoreCase(type)) return "ignored:not-text";
        if (content == null) return "ignored:empty";

        String phone = firstPhone(text(d, "from"), remoteJid, d.path("sender").isTextual() ? d.path("sender").asText() : null);
        if (phone == null) {
            // Only a LID ("...@lid") or something unusable: there is no phone number to match a parent with.
            log.warn("inbound message ignored: sender has no phone number (LID only?) id={}", fingerprint(text(d, "from")));
            return "ignored:no-phone";
        }
        if (content.length() > MAX_TEXT) content = content.substring(0, MAX_TEXT);

        String quotedId = text(d.path("quoted").path("key"), "id");
        InboundMessage message = new InboundMessage(id, phone, content, quotedId);
        Intent intent = ReplyClassifier.classify(content);

        List<OutboundReply> replies;
        try {
            replies = tx.execute(status -> {
                if (!claim("gw:in:" + id)) return null; // duplicate delivery
                return handlers.handle(router.route(message, intent), message);
            });
        } catch (DataIntegrityViolationException rejected) {
            // The database refused the data itself: retrying cannot help. Keep the claim, skip the event.
            tx.executeWithoutResult(s -> claim("gw:in:" + id));
            log.warn("skipped an inbound event the database rejected ({})", rejected.getClass().getSimpleName());
            return "skipped:rejected";
        }
        if (replies == null) return "duplicate";
        send(replies);
        // A reply can create a family notification ("call me", STOP): its WhatsApp alert goes out now, not at the next tick.
        // After the commit, best effort, only while the scheduler is on (see SchedulerService.deliverAlertsNow).
        scheduler.deliverAlertsNow();
        log.info("inbound reply handled from {} intent={} replies={}", WhatsAppSender.mask(phone), intent, replies.size());
        return "handled:" + intent;
    }

    // ---------------------------------------------------------------- message.received, type REACTION

    /**
     * A long-press reaction on one of our messages (gateway change F6: {@code type "REACTION"}, {@code reaction.text},
     * {@code reaction.key.id} = the reacted message's id). Only a "yes" emoji ({@link ReplyRouter#reactionMeansYes}) counts,
     * and only for exactly the message it was put on ({@link ReplyRouter#routeReaction}). A removed reaction, any other
     * emoji, or a reaction to a message we do not track is ignored silently: no help text, nothing undone.
     * Same claim ({@code gw:in:<reaction id>}) and transaction as a typed reply.
     */
    private String reaction(JsonNode d, String id, String remoteJid) {
        JsonNode r = d.path("reaction");
        String emoji = text(r, "text");
        if (emoji == null || r.path("removed").asBoolean(false)) return "ignored:reaction-removed";
        String target = text(r.path("key"), "id");
        if (target == null) return "ignored:reaction-no-target";
        if (!ReplyRouter.reactionMeansYes(emoji)) return "ignored:reaction-emoji";

        String phone = firstPhone(text(d, "from"), remoteJid, d.path("sender").isTextual() ? d.path("sender").asText() : null);
        if (phone == null) {
            log.warn("reaction ignored: sender has no phone number (LID only?) id={}", fingerprint(text(d, "from")));
            return "ignored:no-phone";
        }
        InboundMessage message = new InboundMessage(id, phone, emoji, target);
        List<OutboundReply> replies;
        try {
            replies = tx.execute(status -> {
                if (!claim("gw:in:" + id)) return null; // duplicate delivery
                return handlers.handle(router.routeReaction(message), message);
            });
        } catch (DataIntegrityViolationException rejected) {
            tx.executeWithoutResult(s -> claim("gw:in:" + id));
            log.warn("skipped a reaction the database rejected ({})", rejected.getClass().getSimpleName());
            return "skipped:rejected";
        }
        if (replies == null) return "duplicate";
        send(replies);
        scheduler.deliverAlertsNow();
        log.info("reaction handled from {} replies={}", WhatsAppSender.mask(phone), replies.size());
        return "handled:REACTION";
    }

    private void send(List<OutboundReply> replies) {
        for (OutboundReply r : replies) {
            try {
                sender.send(r.phone(), r.text());
            } catch (Exception e) {
                log.warn("could not send a reply to {} ({})", WhatsAppSender.mask(r.phone()), e.getClass().getSimpleName());
            }
        }
    }

    // ---------------------------------------------------------------- message.status

    /**
     * The gateway reports PENDING / SENT / DELIVERED / READ for a sent message ({@code keyId}). Nothing is stored for
     * those today (the Python code only used delivered/read for the "love" feature, which is off). A failure status
     * (FAILED / ERROR) tells the family once, like {@code _handle_failed}; the current gateway never sends one.
     */
    private String status(JsonNode d) {
        String keyId = text(d, "keyId");
        String status = text(d, "status");
        if (keyId == null || status == null) return "ignored:no-id";
        if (!status.equalsIgnoreCase("FAILED") && !status.equalsIgnoreCase("ERROR")) return "ignored:status";

        Boolean done = tx.execute(s -> {
            if (!claim("gw:failed:" + keyId)) return false;
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select de.id dose_id, r.id recipient_id, r.circle_id from dose_events de " +
                            "join medicine_schedules s on s.id = de.schedule_id join medicines m on m.id = s.medicine_id " +
                            "join care_recipients r on r.id = m.recipient_id where de.whatsapp_message_id = ?", keyId);
            java.util.Set<Object> told = new java.util.HashSet<>();
            for (Map<String, Object> row : rows) {
                if (!told.add(row.get("recipient_id"))) continue; // one message can cover several doses: tell once per parent
                notifier.notifyCircle((UUID) row.get("circle_id"), "whatsapp_failed",
                        Map.of("recipient_id", row.get("recipient_id").toString(), "dose_event_id", row.get("dose_id").toString()));
            }
            return true;
        });
        log.warn("WhatsApp delivery failed for message {}", keyId);
        return Boolean.TRUE.equals(done) ? "handled:failed" : "duplicate";
    }

    // ---------------------------------------------------------------- helpers

    /** Claim an event id; false when it was already claimed (a duplicate). Call inside the transaction. */
    boolean claim(String eventId) {
        return jdbc.update("insert into whatsapp_events (id) values (?) on conflict (id) do nothing", eventId) == 1;
    }

    /** Baileys jid ("919876543210@s.whatsapp.net", "...:12@s.whatsapp.net", "...@c.us") to E.164, or null (LID, group, junk). */
    static String phoneFromJid(String jid) {
        if (jid == null) return null;
        int at = jid.indexOf('@');
        if (at <= 0) return null;
        String domain = jid.substring(at + 1);
        if (!domain.equals("s.whatsapp.net") && !domain.equals("c.us")) return null; // @lid, @g.us, ...
        String user = jid.substring(0, at);
        int colon = user.indexOf(':'); // device suffix
        if (colon >= 0) user = user.substring(0, colon);
        return PHONE_DIGITS.matcher(user).matches() ? "+" + user : null;
    }

    private static String firstPhone(String... jids) {
        for (String j : jids) {
            String p = phoneFromJid(j);
            if (p != null) return p;
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (!v.isTextual()) return null;
        String s = v.asText();
        return s.isEmpty() ? null : s;
    }

    /** A short non-reversible tag for logs, so repeated unresolvable senders can be told apart without a number. */
    private static String fingerprint(String value) {
        if (value == null) return "-";
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 4);
        } catch (Exception e) {
            return "?";
        }
    }
}
