package org.smartgurukul.chhaon.whatsapp;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The dose reminder for ONE time slot (every medicine of one person due at the same moment = one message), as a text
 * (typed answer "1") or as a WhatsApp poll (one tap). Also the check-in poll and the poll option labels.
 *
 * <p>Text (owner-approved wording, 2026-11-05): one question, only "1", the medicine name bold on its own line:
 * <pre>
 * नमस्ते Shivani 🙏
 * रात 8:00 की दवाएं:
 * • *Argipreg* · 1 पाउच
 * • *Metformin 500* · 1 गोली · 🔵 नीली गोल गोली
 *
 * सब ले लीं? जवाब में बस *1* लिखें
 * </pre>
 * One medicine: "दवा का समय: *Ecosprin 150*\n1 गोली · ...\n\nदवा ले ली? जवाब में बस *1* लिखें".
 *
 * <p>Poll: no formatting (WhatsApp shows poll text as typed), at most {@value #POLL_QUESTION_MAX} characters (the list is
 * shortened with "+2 और" / "+2 more"), and the option labels below. <b>Votes are matched by index AND label</b>
 * ({@link #doseVote}, {@link #checkinVote}), so never change a label without keeping the old one in those lists: a vote on a
 * poll sent before the change still arrives with the old label.
 *
 * <p>Who reads it: the person (reminder_to = person; a pregnancy gets the time-of-day greeting and, once a week, the week line),
 * a helper ("Papa को दवा दे दी?", answers ✅ दे दी) or a family member (caregiver: "Papa ने दवा ले ली?"). Names are cleaned with
 * {@link MessageTexts#clean}. Hindi needs a native review (docs/hindi-review.md).
 */
public final class ReminderTexts {

    private ReminderTexts() {}

    /** WhatsApp's limit for a poll question is about 255 characters; we stay at or under it. */
    public static final int POLL_QUESTION_MAX = 255;
    /** At most this many medicines are listed one per line in a text reminder; the rest become "+2 more". */
    static final int MAX_TEXT_LINES = 10;

    // ---------------------------------------------------------------- poll option labels (ONE place; votes match on them)

    public static final String TAKEN_EN = "✅ Taken";
    public static final String TAKEN_HI = "✅ ले ली";
    public static final String GIVEN_EN = "✅ Given";
    public static final String GIVEN_HI = "✅ दे दी";
    public static final String LATER_EN = "⏰ Later";
    public static final String LATER_HI = "⏰ थोड़ी देर में";
    public static final String FINE_EN = "😊 I'm fine";
    public static final String FINE_HI = "😊 ठीक हूँ";
    public static final String CALL_EN = "📞 Please call me";
    public static final String CALL_HI = "📞 फ़ोन कीजिए";

    /** Option 0 = yes (taken / given / I am fine), option 1 = later / call me. */
    public static final int YES = 0;
    public static final int SECOND = 1;

    private static final List<String> DOSE_YES = List.of(TAKEN_EN, TAKEN_HI, GIVEN_EN, GIVEN_HI);
    private static final List<String> DOSE_LATER = List.of(LATER_EN, LATER_HI);
    private static final List<String> CHECKIN_FINE = List.of(FINE_EN, FINE_HI);
    private static final List<String> CHECKIN_CALL = List.of(CALL_EN, CALL_HI);

    public record Poll(String question, List<String> options) {}

    /** One medicine of the slot (values as stored; cleaned here). */
    public record Item(String medicine, String dosage, String pillColour, String pillShape) {}

    /**
     * Who reads the reminder and how to greet them.
     *
     * @param reminderTo   person | caregiver | helper (null = person)
     * @param receiverName the helper / caregiver's name (receivers only)
     * @param person       what the receiver calls the person ("Papa"; receivers only)
     * @param callName     the name the person is greeted with (person only)
     * @param pregnant     time-of-day greeting and the week line
     * @param newWeek      the pregnancy week to announce in this reminder, or null
     * @param localHour    the hour on the person's clock (pregnancy greeting)
     */
    public record Reader(String language, String reminderTo, String receiverName, String person, String callName,
                         boolean pregnant, Integer newWeek, int localHour) {
        public static Reader person(String language, String callName) {
            return new Reader(language, ReceiverTexts.PERSON, null, null, callName, false, null, 8);
        }

        public static Reader pregnancy(String language, String callName, Integer newWeek, int localHour) {
            return new Reader(language, ReceiverTexts.PERSON, null, null, callName, true, newWeek, localHour);
        }

        public static Reader receiver(String language, String reminderTo, String receiverName, String person) {
            return new Reader(language, reminderTo, receiverName, person, null, false, null, 8);
        }

        boolean hi() {
            return "hi".equals(language);
        }

        boolean helper() {
            return ReceiverTexts.HELPER.equals(reminderTo);
        }

        boolean caregiver() {
            return ReceiverTexts.CAREGIVER.equals(reminderTo);
        }

        String p() {
            return MessageTexts.clean(person, 40);
        }
    }

    // ---------------------------------------------------------------- options

    public static List<String> doseOptions(String language, String reminderTo) {
        boolean hi = "hi".equals(language);
        boolean helper = ReceiverTexts.HELPER.equals(reminderTo);
        String yes = helper ? (hi ? GIVEN_HI : GIVEN_EN) : (hi ? TAKEN_HI : TAKEN_EN);
        return List.of(yes, hi ? LATER_HI : LATER_EN);
    }

    public static List<String> checkinOptions(String language) {
        return "hi".equals(language) ? List.of(FINE_HI, CALL_HI) : List.of(FINE_EN, CALL_EN);
    }

    /**
     * Which option of a DOSE poll a vote chose: 0 (taken / given), 1 (later), or -1 when it cannot be told. The label decides
     * when it is one of ours (either language, so a language change between send and vote does not matter); the index alone
     * is used only when no label came; a label and an index that contradict each other are refused (-1).
     */
    public static int doseVote(String label, Integer index) {
        return vote(label, index, DOSE_YES, DOSE_LATER);
    }

    /** Same for the check-in poll: 0 = I am fine, 1 = please call me, -1 = unknown. */
    public static int checkinVote(String label, Integer index) {
        return vote(label, index, CHECKIN_FINE, CHECKIN_CALL);
    }

    private static int vote(String label, Integer index, List<String> first, List<String> second) {
        String l = label == null ? "" : java.text.Normalizer.normalize(label.strip(), java.text.Normalizer.Form.NFC);
        int byLabel = first.contains(l) ? YES : second.contains(l) ? SECOND : -1;
        int byIndex = index != null && (index == YES || index == SECOND) ? index : -1;
        if (byLabel >= 0) return byIndex >= 0 && byIndex != byLabel ? -1 : byLabel;
        return l.isEmpty() ? byIndex : -1;   // an unknown label never counts, whatever its index says
    }

    // ---------------------------------------------------------------- the slot time ("रात 8:00" / "8:00 pm")

    /** "सुबह 8:00", "दोपहर 1:30", "शाम 6:00", "रात 8:00" / "8:00 am", "8:00 pm" (the person's clock). */
    public static String slotTime(String language, LocalTime t) {
        int h = t.getHour();
        String clock = (h % 12 == 0 ? 12 : h % 12) + ":" + String.format(Locale.ROOT, "%02d", t.getMinute());
        if ("hi".equals(language)) {
            String part = h >= 4 && h < 12 ? "सुबह" : h >= 12 && h < 16 ? "दोपहर" : h >= 16 && h < 19 ? "शाम" : "रात";
            return part + " " + clock;
        }
        return clock + (h < 12 ? " am" : " pm");
    }

    // ---------------------------------------------------------------- the text reminder

    /**
     * The typed-answer reminder for one slot. {@code slotTime} (from {@link #slotTime}) is used when there are several medicines.
     */
    public static String doseText(Reader r, List<Item> items, String slotTime) {
        List<Item> list = items == null ? List.of() : items.stream().filter(Objects::nonNull).toList();
        boolean hi = r.hi();
        String p = r.p();
        StringBuilder sb = new StringBuilder(head(r));
        if (list.size() <= 1) {
            Item it = list.isEmpty() ? new Item(null, null, null, null) : list.get(0);
            String med = MessageTexts.clean(it.medicine(), 60);
            String receiverIntro = receiverSingleIntro(r, p);
            if (med.isEmpty()) {
                sb.append('\n').append(receiverIntro.isEmpty() ? (hi ? "दवा का समय हो गया है 💊" : "It's medicine time 💊")
                        : receiverIntro + " 💊");
            } else {
                sb.append('\n').append(receiverIntro.isEmpty() ? (hi ? "दवा का समय" : "Medicine time") : receiverIntro)
                        .append(": *").append(med).append('*');
            }
            String detail = details(r.language(), it, " · ");
            if (!detail.isEmpty()) sb.append('\n').append(detail);
            sb.append("\n\n").append(textAsk(r, p, false));
            return sb.toString();
        }
        sb.append('\n').append(listIntro(r, p, slotTime)).append(':');
        int shown = Math.min(list.size(), MAX_TEXT_LINES);
        for (int i = 0; i < shown; i++) {
            Item it = list.get(i);
            String med = MessageTexts.clean(it.medicine(), 60);
            sb.append("\n• ").append(med.isEmpty() ? (hi ? "दवा" : "Medicine") : "*" + med + "*");
            String detail = details(r.language(), it, " · ");
            if (!detail.isEmpty()) sb.append(" · ").append(detail);
        }
        if (list.size() > shown) sb.append("\n• ").append(more(hi, list.size() - shown));
        sb.append("\n\n").append(textAsk(r, p, true));
        return sb.toString();
    }

    /** The daily check-in as a text: one question, *1* = fine, *2* = call me. */
    public static String checkinText(Reader r) {
        if (r.hi()) {
            return checkinHead(r) + "\n" + checkinQuestion(r) + "\n\nठीक हैं तो *1* लिखें 😊\nफ़ोन चाहिए तो *2* लिखें 📞";
        }
        return checkinHead(r) + "\n" + checkinQuestion(r) + "\n\nReply *1* if you are fine 😊\nReply *2* if you want a call 📞";
    }

    // ---------------------------------------------------------------- polls

    /** The dose reminder as a poll (no formatting; at most {@value #POLL_QUESTION_MAX} characters). */
    public static Poll dosePoll(Reader r, List<Item> items, String slotTime) {
        List<Item> list = items == null ? List.of() : items.stream().filter(Objects::nonNull).toList();
        boolean hi = r.hi();
        String p = r.p();
        String head = head(r);
        boolean many = list.size() > 1;
        String intro = "💊 " + (many ? listIntro(r, p, slotTime) : singleIntroPlain(r, p)) + ": ";
        String ask = pollAsk(r, p, many);
        int budget = POLL_QUESTION_MAX - head.length() - intro.length() - ask.length() - 2;
        String what = many ? fitList(hi, list, budget) : fitOne(r.language(), list.isEmpty() ? null : list.get(0), budget);
        String question = head + "\n" + intro + what + "\n" + ask;
        if (question.length() > POLL_QUESTION_MAX) question = question.substring(0, POLL_QUESTION_MAX - 1) + "…";
        return new Poll(question, doseOptions(r.language(), r.reminderTo()));
    }

    /** The daily check-in as a poll: 😊 ठीक हूँ / 📞 फ़ोन कीजिए. */
    public static Poll checkinPoll(Reader r) {
        String q = checkinHead(r) + "\n" + checkinQuestion(r);
        if (q.length() > POLL_QUESTION_MAX) q = q.substring(0, POLL_QUESTION_MAX - 1) + "…";
        return new Poll(q, checkinOptions(r.language()));
    }

    /** The caption of the medicine photo sent just before a poll: "💊 *Ecosprin 150*" (the poll itself asks the question). */
    public static String photoCaption(String medicine) {
        String med = MessageTexts.clean(medicine, 60);
        return med.isEmpty() ? "💊" : "💊 *" + med + "*";
    }

    // ---------------------------------------------------------------- pieces

    private static String head(Reader r) {
        if (ReceiverTexts.notThePerson(r.reminderTo())) {
            return (r.hi() ? "नमस्ते" : "Namaste") + ReceiverTexts.greet(r.language(), r.reminderTo(), r.receiverName()) + " 🙏";
        }
        if (r.pregnant()) return MessageTexts.greeting(r.language(), r.callName(), r.localHour()) + MessageTexts.weekLine(r.language(), r.newWeek());
        return (r.hi() ? "नमस्ते" : "Namaste") + MessageTexts.who(r.callName()) + " 🙏";
    }

    private static String checkinHead(Reader r) {
        if (r.pregnant()) return MessageTexts.greeting(r.language(), r.callName(), 8);
        return (r.hi() ? "सुप्रभात" : "Good morning") + MessageTexts.who(r.callName()) + " ☀️🙏";
    }

    private static String checkinQuestion(Reader r) {
        if (r.pregnant()) return r.hi() ? "आज आप कैसा महसूस कर रही हैं?" : "How are you feeling today?";
        return r.hi() ? "आज आप कैसे हैं?" : "How are you today?";
    }

    /** For a receiver: "Papa की दवा का समय" / "Time for Papa's medicine"; for the person "". */
    private static String receiverSingleIntro(Reader r, String p) {
        if (!ReceiverTexts.notThePerson(r.reminderTo())) return "";
        if (r.hi()) return (p.isEmpty() ? "" : p + " की ") + "दवा का समय";
        return "Time for " + possessive(p) + " medicine";
    }

    private static String singleIntroPlain(Reader r, String p) {
        String s = receiverSingleIntro(r, p);
        if (!s.isEmpty()) return s;
        return r.hi() ? "दवा का समय" : "Medicine time";
    }

    /** "रात 8:00 की दवाएं" / "Your 8:00 pm medicines" / "Papa की रात 8:00 की दवाएं" / "Papa's 8:00 pm medicines". */
    private static String listIntro(Reader r, String p, String slotTime) {
        String t = slotTime == null || slotTime.isBlank() ? "" : slotTime.trim();
        boolean receiver = ReceiverTexts.notThePerson(r.reminderTo());
        if (r.hi()) {
            String whose = receiver && !p.isEmpty() ? p + " की " : "";
            return whose + (t.isEmpty() ? "दवाएं" : t + " की दवाएं");
        }
        String whose = receiver ? (p.isEmpty() ? "The" : p + "'s") : "Your";
        return whose + (t.isEmpty() ? "" : " " + t) + " medicines";
    }

    /** The one question at the end of a text reminder, with "just reply 1". */
    private static String textAsk(Reader r, String p, boolean many) {
        String q;
        if (r.hi()) {
            if (r.helper()) q = (p.isEmpty() ? "" : p + " को ") + (many ? "सब दवाएं दे दीं?" : "दवा दे दी?");
            else if (r.caregiver()) q = (p.isEmpty() ? "" : p + " ने ") + (many ? "सब दवाएं ले लीं?" : "दवा ले ली?");
            else q = many ? "सब ले लीं?" : "दवा ले ली?";
            return q + " जवाब में बस *1* लिखें";
        }
        if (r.helper()) q = many ? "Given them all" + (p.isEmpty() ? "" : " to " + p) + "?" : "Given it" + (p.isEmpty() ? "" : " to " + p) + "?";
        else if (r.caregiver()) q = p.isEmpty() ? (many ? "All taken?" : "Taken?") : "Has " + p + (many ? " taken them all?" : " taken it?");
        else q = many ? "Taken them all?" : "Taken it?";
        return q + " Just reply *1*";
    }

    /** The question of a poll (the options are the answers). */
    private static String pollAsk(Reader r, String p, boolean many) {
        if (r.hi()) {
            if (r.helper()) return (p.isEmpty() ? "" : p + " को ") + (many ? "दवाएं दे दीं?" : "दवा दे दी?");
            if (r.caregiver()) return (p.isEmpty() ? "" : p + " ने ") + (many ? "दवाएं ले लीं?" : "दवा ले ली?");
            return many ? "ले लीं?" : "ले ली?";
        }
        if (r.helper()) return (many ? "Given them" : "Given it") + (p.isEmpty() ? "" : " to " + p) + "?";
        if (r.caregiver()) return p.isEmpty() ? "Taken?" : "Has " + p + (many ? " taken them?" : " taken it?");
        return many ? "Taken them?" : "Taken it?";
    }

    /** "1 गोली · 🔵 नीली गोल गोली" (dose as stored, unit word translated; then the look), or "". */
    private static String details(String language, Item it, String sep) {
        List<String> parts = new ArrayList<>(2);
        String dose = MessageTexts.localizeDose(language, it.dosage());
        if (!dose.isEmpty()) parts.add(dose);
        String look = MessageTexts.pillLook(language, it.pillColour(), it.pillShape());
        if (!look.isEmpty()) parts.add(look);
        return String.join(sep, parts);
    }

    /** "Ecosprin 150 (1 गोली, 🔵 नीली गोल गोली)", shortened to {@code budget}. */
    private static String fitOne(String language, Item it, int budget) {
        if (it == null) return "";
        String med = MessageTexts.clean(it.medicine(), 60);
        String detail = details(language, it, ", ");
        String full = detail.isEmpty() ? med : med + " (" + detail + ")";
        if (full.length() <= budget) return full;
        String dose = MessageTexts.localizeDose(language, it.dosage());
        String shorter = dose.isEmpty() ? med : med + " (" + dose + ")";
        if (shorter.length() <= budget) return shorter;
        return cut(med, budget);
    }

    /** "Argipreg, Metformin 500 (1 गोली)" with "+2 और" / "+2 more" when it does not fit in {@code budget}. */
    private static String fitList(boolean hi, List<Item> items, int budget) {
        String language = hi ? "hi" : "en";
        List<String> names = new ArrayList<>();
        for (Item it : items) {
            String med = MessageTexts.clean(it.medicine(), 40);
            if (med.isEmpty()) med = hi ? "दवा" : "medicine";
            String dose = MessageTexts.localizeDose(language, it.dosage());
            names.add(dose.isEmpty() ? med : med + " (" + dose + ")");
        }
        for (int shown = names.size(); shown >= 1; shown--) {
            String s = String.join(", ", names.subList(0, shown));
            if (shown < names.size()) s += " " + more(hi, names.size() - shown);
            if (s.length() <= budget) return s;
        }
        String rest = " " + more(hi, names.size() - 1);
        return cut(names.get(0), Math.max(1, budget - rest.length())) + rest;
    }

    private static String more(boolean hi, int n) {
        return hi ? "+" + n + " और" : "+" + n + " more";
    }

    private static String cut(String s, int max) {
        if (max <= 1) return "…";
        return s.length() <= max ? s : s.substring(0, max - 1).trim() + "…";
    }

    private static String possessive(String person) {
        return person.isEmpty() ? "the" : person + "'s";
    }
}
