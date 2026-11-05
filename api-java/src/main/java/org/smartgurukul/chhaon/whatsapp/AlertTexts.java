package org.smartgurukul.chhaon.whatsapp;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * WhatsApp alerts to a CAREGIVER (a family member who turned "Get alerts on WhatsApp" on and confirmed it by typing 1).
 * Port of the Python alert templates ({@code api/app/whatsapp/templates.py} ALERT_TEMPLATES, wording in
 * docs/whatsapp-templates.md), rewritten as plain text for the self-hosted gateway: a short header, what happened, what
 * the family may want to do, and, at the end, how to reply (STOP turns the alerts off).
 *
 * <p>Owner's fraud rule: no links, no payments and no phone numbers in any text. Names come from the family's own data and
 * are cleaned like every other name ({@link MessageTexts#clean}). Hindi needs a native review (docs/hindi-review.md).
 */
public final class AlertTexts {

    /** Notification types that also go to a caregiver on WhatsApp (the ones the Java backend creates today). */
    public static final Set<String> TYPES = Set.of(
            "dose_missed", "checkin_missed", "checkin_call_requested", "refill_needed", "out_of_stock",
            "reminder_not_sent", "parent_stopped", "parent_declined");

    private AlertTexts() {}

    private static boolean hi(String language) {
        return "hi".equals(language);
    }

    /** What a missing piece is called in the text. */
    public static String fallbackParent(String language) {
        return hi(language) ? "आपके माता-पिता" : "your parent";
    }

    /**
     * The full alert text, or null for a type we do not send.
     *
     * @param parent   what the family calls the parent (call name, else name); blank = "your parent"
     * @param medicine one medicine name, or null
     * @param count    how many medicines went unconfirmed together (dose_missed), or null / 1
     * @param when     the due time already worded ({@link #clock}), or null
     */
    public static String alert(String type, String language, String parent, String medicine, Integer count, String when) {
        boolean h = hi(language);
        String p = MessageTexts.clean(parent, 40);
        if (p.isEmpty()) p = fallbackParent(language);
        String m = MessageTexts.clean(medicine, 60);
        String med = m.isEmpty() ? (h ? "एक दवा" : "a medicine") : "*" + m + "*";
        boolean many = count != null && count > 1 && count <= 50;
        String w = when == null || when.isBlank() ? null : MessageTexts.clean(when, 60);
        String body = switch (type) {
            case "dose_missed" -> h
                    ? p + " ने " + (w == null ? "आज " : w + " की ") + (many ? count + " दवाइयाँ" : "दवा " + med)
                        + " लेने की पुष्टि नहीं की है।\nएक बार फ़ोन करके हाल पूछ लें।"
                    : p + " has not confirmed " + (many ? count + " medicines" : med) + (w == null ? " today" : " (due " + w + ")")
                        + ".\nYou may want to give them a call.";
            case "checkin_missed" -> h
                    ? p + " ने आज सुबह के चेक-इन का जवाब नहीं दिया है।\nएक बार फ़ोन करके पूछ लें कि वे ठीक हैं।"
                    : p + " has not replied to today's morning check-in.\nYou may want to call to make sure they are okay.";
            case "checkin_call_requested" -> h
                    ? p + " ने आज के चेक-इन में फ़ोन करने को कहा है।\nजब हो सके, उन्हें फ़ोन कर लें।"
                    : p + " asked for a call in today's check-in.\nPlease phone them when you can.";
            case "refill_needed" -> h
                    ? p + " की दवा " + med + " कम बची है।\nदोबारा मँगाने का समय है।"
                    : p + "'s medicine " + med + " is running low.\nTime to arrange a refill.";
            case "out_of_stock" -> h
                    ? p + " की दवा " + med + " ख़त्म हो गई है।\nआज ही मँगा लें, ताकि कोई ख़ुराक न छूटे।"
                    : p + "'s medicine " + med + " has run out.\nPlease arrange it today so that no dose is missed.";
            case "reminder_not_sent" -> h
                    ? "हम " + p + " को " + med + " का रिमाइंडर नहीं भेज पाए।\nकृपया उन्हें ख़ुद याद दिला दें या फ़ोन कर लें।"
                    : "We could not send the reminder for " + med + " to " + p + ".\nPlease remind them yourself or give them a call.";
            case "parent_stopped" -> h
                    ? p + " ने WhatsApp पर छाँव के संदेश बंद कर दिए हैं, इसलिए रिमाइंडर और चेक-इन रुके हुए हैं।"
                    : p + " chose to stop Chhaon messages on WhatsApp, so reminders and check-ins are paused.";
            case "parent_declined" -> h
                    ? p + " ने WhatsApp पर छाँव के रिमाइंडर लेने से मना किया है, इसलिए कोई रिमाइंडर नहीं भेजा जाएगा।"
                    : p + " said no to Chhaon reminders on WhatsApp, so no reminders will be sent.";
            default -> null;
        };
        if (body == null) return null;
        if (!h) body = Character.toUpperCase(body.charAt(0)) + body.substring(1); // "your parent has ..." starts a sentence
        return (h ? "छाँव अलर्ट 🔔\n" : "Chhaon alert 🔔\n") + body
                + (h ? "\n\nये अलर्ट बंद करने के लिए STOP लिखें।" : "\n\nReply STOP to turn off these alerts.");
    }

    /**
     * The alert when the reminders go to someone other than the person (a helper or a family member,
     * docs/reminder-receiver.md): a missed dose names who was reminded, and a STOP / "no" is said to come from that receiver,
     * not from the parent. {@code receiver} comes from {@link ReceiverTexts#receiverLabel}; null = the person themself, which is
     * exactly {@link #alert(String, String, String, String, Integer, String)}.
     */
    public static String alert(String type, String language, String parent, String medicine, Integer count, String when, String receiver) {
        if (receiver == null || receiver.isBlank()) return alert(type, language, parent, medicine, count, when);
        boolean h = hi(language);
        String p = MessageTexts.clean(parent, 40);
        if (p.isEmpty()) p = fallbackParent(language);
        String m = MessageTexts.clean(medicine, 60);
        String med = m.isEmpty() ? (h ? "एक दवा" : "a medicine") : "*" + m + "*";
        boolean many = count != null && count > 1 && count <= 50;
        String w = when == null || when.isBlank() ? null : MessageTexts.clean(when, 60);
        boolean you = "you".equals(receiver) || "आप".equals(receiver);
        String toR = h ? (you ? "आपको" : receiver + " को") : receiver;
        String body = switch (type) {
            case "dose_missed" -> h
                    ? p + " की " + (many ? count + " दवाइयों" : "दवा " + med) + (w == null ? " (आज)" : " (" + w + ")")
                        + " की पुष्टि नहीं हुई। रिमाइंडर " + toR + " भेजा गया था।\nएक बार फ़ोन करके पूछ लें।"
                    : p + "'s " + (many ? count + " medicines" : "medicine " + med) + (w == null ? " today" : " (due " + w + ")")
                        + (many ? " were" : " was") + " not confirmed. The reminder went to " + toR + ".\nYou may want to give them a call.";
            case "reminder_not_sent" -> h
                    ? "हम " + p + " की दवा " + med + " का रिमाइंडर " + toR + " नहीं भेज पाए।\nकृपया ख़ुद याद दिला दें या फ़ोन कर लें।"
                    : "We could not send the reminder for " + p + "'s medicine " + med + " to " + toR + ".\nPlease make sure it is given, or give them a call.";
            case "parent_stopped" -> h
                    ? (you ? "आपने" : p + " के रिमाइंडर पाने वाले " + receiver + " ने") + " WhatsApp पर छाँव के संदेश बंद कर दिए हैं, इसलिए "
                        + p + " के रिमाइंडर रुके हुए हैं।"
                    : (you ? "you" : receiver + ", who gets " + p + "'s reminders,") + " stopped Chhaon messages on WhatsApp, so "
                        + p + "'s reminders are paused.";
            case "parent_declined" -> h
                    ? (you ? "आपने" : receiver + " ने") + " WhatsApp पर " + p + " की दवा के रिमाइंडर लेने से मना किया है, इसलिए कोई रिमाइंडर नहीं भेजा जाएगा।"
                    : (you ? "you" : receiver) + " said no to getting " + p + "'s medicine reminders on WhatsApp, so no reminders will be sent.";
            default -> null;
        };
        if (body == null) return alert(type, language, parent, medicine, count, when); // refills, check-ins: as before
        if (!h) body = Character.toUpperCase(body.charAt(0)) + body.substring(1);
        return (h ? "छाँव अलर्ट 🔔\n" : "Chhaon alert 🔔\n") + body
                + (h ? "\n\nये अलर्ट बंद करने के लिए STOP लिखें।" : "\n\nReply STOP to turn off these alerts.");
    }

    /**
     * A due time as the family reads it, in the PARENT's time zone: "8:00 AM" / "सुबह 8:00". When the caregiver lives in a
     * different zone, "(their time)" / "(उनके समय से)" is added so nobody misreads it as their own clock.
     */
    public static String clock(Instant at, String parentZone, String caregiverZone, String language) {
        ZoneId zone = zone(parentZone);
        ZonedDateTime local = at.atZone(zone);
        int hour = local.getHour();
        String time = (hour % 12 == 0 ? 12 : hour % 12) + ":" + String.format("%02d", local.getMinute());
        String text;
        if (hi(language)) {
            String part = hour >= 4 && hour < 12 ? "सुबह" : hour >= 12 && hour < 16 ? "दोपहर" : hour >= 16 && hour < 20 ? "शाम" : "रात";
            text = part + " " + time;
        } else {
            text = time + (hour < 12 ? " AM" : " PM");
        }
        if (caregiverZone != null && !caregiverZone.isBlank()) {
            ZoneId theirs = zone(caregiverZone);
            if (!theirs.getRules().getOffset(at).equals(zone.getRules().getOffset(at))) {
                text += hi(language) ? " (उनके समय से)" : " (their time)";
            }
        }
        return text;
    }

    private static ZoneId zone(String id) {
        try {
            return ZoneId.of(id == null || id.isBlank() ? "Asia/Kolkata" : id);
        } catch (Exception e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }
}
