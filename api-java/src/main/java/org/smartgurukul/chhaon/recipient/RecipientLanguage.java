package org.smartgurukul.chhaon.recipient;

import java.util.Map;
import java.util.Set;

/**
 * Which language a WhatsApp message about a person is written in (migration 20261105000050, docs/reminder-receiver.md).
 * <ul>
 *   <li>The person gets the reminders themself (reminder_to = person): the person's own {@code language}.</li>
 *   <li>A helper or a family member gets them: their {@code reminder_language} when set, else the person's {@code language}
 *       (a maid who reads Hindi while Papa's profile says English).</li>
 * </ul>
 * Reads a JDBC row defensively, so it works on rows selected before the column exists: select it in SQL as
 * {@code to_jsonb(r)->>'reminder_language' reminder_language} (plus {@code r.reminder_to} and {@code r.language}).
 * The same in SQL: {@code case when r.reminder_to <> 'person' then coalesce(to_jsonb(r)->>'reminder_language', r.language)
 * else r.language end}.
 */
public final class RecipientLanguage {

    /** Languages the messages are written in. Anything else falls back to English. */
    public static final Set<String> SUPPORTED = Set.of("en", "hi");

    private RecipientLanguage() {}

    /** "en" | "hi" for whoever receives the reminders of the person in this JDBC row. */
    public static String forReceiver(Map<String, Object> row) {
        if (row == null) return "en";
        return forReceiver(str(row.get("reminder_to")), str(row.get("reminder_language")), str(row.get("language")));
    }

    /** The same, from the entity. */
    public static String forReceiver(CareRecipient r) {
        return r == null ? "en" : forReceiver(r.getReminderTo(), r.getReminderLanguage(), r.getLanguage());
    }

    /** The same, from the three values (any may be null). */
    public static String forReceiver(String reminderTo, String reminderLanguage, String personLanguage) {
        boolean someoneElse = reminderTo != null && !"person".equals(reminderTo);
        if (someoneElse && reminderLanguage != null && SUPPORTED.contains(reminderLanguage)) return reminderLanguage;
        return "hi".equals(personLanguage) ? "hi" : "en";
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
