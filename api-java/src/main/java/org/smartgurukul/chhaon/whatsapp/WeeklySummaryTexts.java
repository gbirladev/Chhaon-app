package org.smartgurukul.chhaon.whatsapp;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The weekly WhatsApp summary to a CAREGIVER (feature F3, docs/weekly-summary.md): one short message on Sunday morning covering
 * every person they care for. Also the shape of the in-app "This week" card (GET /api/v1/recipients/{id}/weekly-summary), so the
 * message and the card always show the same numbers.
 *
 * <p>Owner's fraud rule, as for every alert: no links, no payments and no phone numbers. Names come from the family's own data and
 * are cleaned ({@link MessageTexts#clean}). Hindi needs a native review (docs/hindi-review.md, Part 10).
 */
public final class WeeklySummaryTexts {

    /** A dose that was not confirmed: the medicine, the day and the part of the day on the PERSON's clock. */
    public record Missed(String medicine, LocalDate day, String part) {}

    /**
     * Who gave / confirmed the doses. kind: you (the caregiver reading it) | person (the person themself) | helper | member (another
     * family member, by first name) | family (marked in the app by someone we cannot name).
     */
    public record Giver(String kind, String name, int count) {}

    public record Refill(String medicine, int left) {}

    public record Visit(String doctor, Instant at) {}

    public record NextVaccine(String en, String hi, String dose, LocalDate due) {}

    /** One person's week. from..to are the 7 full days before "today" on the person's clock. */
    public record Week(UUID recipientId, String name, String zone, LocalDate from, LocalDate to, Integer pregnancyWeek,
                       int confirmed, int total, List<Missed> missed, List<Giver> givers, List<Refill> refills, Visit nextVisit,
                       NextVaccine nextVaccine, int checkinsAnswered, int checkinsTotal) {
        /** Nothing to report: no counted dose and no check-in that week (such a person is left out of the message). */
        public boolean quiet() {
            return total == 0 && checkinsTotal == 0;
        }

        /** The "Given by" line only says something when someone other than the person gave / confirmed a dose. */
        public boolean showGivers() {
            return givers.stream().anyMatch(g -> !"person".equals(g.kind()));
        }
    }

    /** At most this many people in one message; the rest are named as "+N more in the app". */
    public static final int MAX_PEOPLE = 5;
    private static final int MAX_MISSED = 4;
    private static final int MAX_GIVERS = 4;

    private WeeklySummaryTexts() {}

    private static boolean hi(String language) {
        return "hi".equals(language);
    }

    /**
     * The whole message, or null when there is nobody to report on.
     *
     * @param caregiverZone the reader's time zone (a visit time gets "(their time)" when it differs from the person's)
     */
    public static String message(String language, List<Week> weeks, String caregiverZone) {
        List<Week> shown = weeks.stream().filter(w -> !w.quiet()).toList();
        if (shown.isEmpty()) return null;
        boolean h = hi(language);
        String head = h ? "छाँव साप्ताहिक 🗓️" : "Chhaon weekly 🗓️";
        boolean samePeriod = shown.stream().allMatch(w -> w.from().equals(shown.get(0).from()) && w.to().equals(shown.get(0).to()));
        StringBuilder sb = new StringBuilder();
        if (shown.size() == 1) {
            Week w = shown.get(0);
            sb.append(head).append(" · ").append(title(language, w)).append('\n').append(period(language, w.from(), w.to()));
            body(sb, language, w, caregiverZone);
        } else {
            sb.append(head);
            if (samePeriod) sb.append('\n').append(period(language, shown.get(0).from(), shown.get(0).to()));
            for (Week w : shown.subList(0, Math.min(MAX_PEOPLE, shown.size()))) {
                sb.append("\n\n*").append(name(language, w.name())).append('*');
                if (w.pregnancyWeek() != null) sb.append(" · ").append(weekWord(language, w.pregnancyWeek()));
                if (!samePeriod) sb.append(" · ").append(period(language, w.from(), w.to()));
                body(sb, language, w, caregiverZone);
            }
            int more = shown.size() - MAX_PEOPLE;
            if (more > 0) sb.append("\n\n").append(h ? "+" + more + " और, ऐप में देखें।" : "+" + more + " more in the app.");
        }
        sb.append("\n\n").append(h ? "पूरी जानकारी: chhaon.site/app" : "Full details: chhaon.site/app");
        sb.append('\n').append(h ? "ये अलर्ट बंद करने के लिए STOP लिखें।" : "Reply STOP to turn off these alerts.");
        return sb.toString();
    }

    private static String title(String language, Week w) {
        String t = name(language, w.name());
        return w.pregnancyWeek() == null ? t : t + " · " + weekWord(language, w.pregnancyWeek());
    }

    private static String name(String language, String name) {
        String n = MessageTexts.clean(name, 40);
        return n.isEmpty() ? (hi(language) ? "परिवार का एक सदस्य" : AlertTexts.fallbackParent(language)) : n;
    }

    private static String weekWord(String language, int week) {
        return hi(language) ? "हफ़्ता " + week : "Week " + week;
    }

    private static void body(StringBuilder sb, String language, Week w, String caregiverZone) {
        boolean h = hi(language);
        if (w.total() > 0) {
            sb.append('\n').append(h ? "दवा की पुष्टि: " + w.total() + " में से " + w.confirmed()
                    : "Medicines confirmed: " + w.confirmed() + " of " + w.total());
        }
        if (!w.missed().isEmpty()) sb.append('\n').append(h ? "छूटी: " : "Missed: ").append(missedText(language, w.missed()));
        if (w.showGivers()) {
            List<String> parts = new ArrayList<>();
            for (Giver g : w.givers().subList(0, Math.min(MAX_GIVERS, w.givers().size()))) {
                parts.add(giverName(language, g, w.name()) + " " + g.count());
            }
            sb.append('\n').append(h ? "किसने दी: " : "Given by: ").append(String.join(" · ", parts));
        }
        if (w.checkinsTotal() > 0) {
            sb.append('\n').append(h ? "चेक-इन का जवाब: " + w.checkinsTotal() + " में से " + w.checkinsAnswered()
                    : "Check-ins answered: " + w.checkinsAnswered() + " of " + w.checkinsTotal());
        }
        if (!w.refills().isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Refill r : w.refills()) {
                String m = MessageTexts.clean(r.medicine(), 60);
                parts.add(m + " (" + (r.left() <= 0 ? (h ? "ख़त्म" : "none left") : r.left() + (h ? " बची" : " left")) + ")");
            }
            sb.append('\n').append(h ? "जल्द मँगाएँ: " : "Refill soon: ").append(String.join(", ", parts));
        }
        if (w.nextVisit() != null) {
            ZoneId zone = zone(w.zone());
            String doctor = MessageTexts.clean(w.nextVisit().doctor(), 60);
            ZonedDateTime at = w.nextVisit().at().atZone(zone);
            sb.append('\n').append(h ? "अगली मुलाक़ात: " : "Next visit: ").append(doctor.isEmpty() ? "" : doctor + ", ")
                    .append(day(language, at.toLocalDate())).append(", ")
                    .append(AlertTexts.clock(w.nextVisit().at(), w.zone(), caregiverZone, language));
        }
        if (w.nextVaccine() != null) {
            NextVaccine v = w.nextVaccine();
            String n = MessageTexts.clean(h && v.hi() != null && !v.hi().isBlank() ? v.hi() : v.en(), 60);
            String dose = doseLabel(language, v.dose());
            sb.append('\n').append(h ? "अगला टीका: " : "Next vaccine: ").append(n).append(dose.isEmpty() ? "" : " (" + dose + ")")
                    .append(", ").append(day(language, v.due()));
        }
    }

    /** "Metformin Tue night, Thu night; Amlodipine Wed morning", at most {@value MAX_MISSED} times, then "+N more". */
    static String missedText(String language, List<Missed> missed) {
        Map<String, List<String>> byMedicine = new LinkedHashMap<>();
        int shown = 0;
        for (Missed m : missed) {
            if (shown == MAX_MISSED) break;
            byMedicine.computeIfAbsent(MessageTexts.clean(m.medicine(), 60), k -> new ArrayList<>())
                    .add(weekday(language, m.day().getDayOfWeek()) + " " + part(language, m.part()));
            shown++;
        }
        List<String> groups = new ArrayList<>();
        byMedicine.forEach((med, times) -> groups.add(med + " " + String.join(", ", times)));
        String text = String.join("; ", groups);
        int more = missed.size() - shown;
        if (more > 0) text += hi(language) ? " (+" + more + " और)" : " (+" + more + " more)";
        return text;
    }

    private static String giverName(String language, Giver g, String personName) {
        boolean h = hi(language);
        return switch (g.kind()) {
            case "you" -> h ? "आप" : "you";
            case "person" -> name(language, personName);
            case "family" -> h ? "परिवार" : "family";
            default -> {
                String n = MessageTexts.clean(g.name(), 30);
                yield n.isEmpty() ? (h ? "परिवार" : "family") : n;
            }
        };
    }

    /** "Dose 1" / "Booster 1" / "" (a single dose), like the app's vaccine card. */
    private static String doseLabel(String language, String dose) {
        if (dose == null || dose.isBlank()) return "";
        boolean h = hi(language);
        if (dose.matches("B\\d*")) {
            String n = dose.substring(1);
            return (h ? "बूस्टर" : "booster") + (n.isEmpty() ? "" : " " + n);
        }
        return (h ? "ख़ुराक " : "dose ") + MessageTexts.clean(dose, 10);
    }

    /** Morning / afternoon / evening / night of a local hour (same split as AlertTexts.clock). */
    public static String partOfDay(int hour) {
        return hour >= 4 && hour < 12 ? "morning" : hour >= 12 && hour < 16 ? "afternoon" : hour >= 16 && hour < 20 ? "evening" : "night";
    }

    private static String part(String language, String part) {
        if (!hi(language)) return part;
        return switch (part) {
            case "morning" -> "सुबह";
            case "afternoon" -> "दोपहर";
            case "evening" -> "शाम";
            default -> "रात";
        };
    }

    private static final String[] EN_DAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    private static final String[] HI_DAYS = {"सोम", "मंगल", "बुध", "गुरु", "शुक्र", "शनि", "रवि"};
    private static final String[] EN_MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    private static final String[] HI_MONTHS = {"जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर"};

    private static String weekday(String language, DayOfWeek d) {
        return (hi(language) ? HI_DAYS : EN_DAYS)[d.getValue() - 1];
    }

    /** "30 Oct" / "30 अक्टू". */
    static String date(String language, LocalDate d) {
        return d.getDayOfMonth() + " " + (hi(language) ? HI_MONTHS : EN_MONTHS)[d.getMonthValue() - 1];
    }

    /** "Tue 11 Nov". */
    static String day(String language, LocalDate d) {
        return weekday(language, d.getDayOfWeek()) + " " + date(language, d);
    }

    /** "30 Oct – 5 Nov". */
    static String period(String language, LocalDate from, LocalDate to) {
        return date(language, from) + " – " + date(language, to);
    }

    private static ZoneId zone(String id) {
        try {
            return ZoneId.of(id == null || id.isBlank() ? "Asia/Kolkata" : id);
        } catch (Exception e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }
}
