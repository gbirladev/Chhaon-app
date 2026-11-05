package org.smartgurukul.chhaon.recipient;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Pregnancy week from the due date the doctor gave (docs/use-cases.md: week = 40 - weeks until due). Counted in completed
 * weeks, like doctors and the usual apps say it: 24 weeks + 3 days is "week 24". The web app does the same sum
 * (web/src/lib/pregnancy.ts); keep the two in step. Never says anything about the baby beyond the week.
 */
public final class Pregnancy {

    /** Days in a pregnancy counted from the first day of the last period to the due date. */
    public static final int TERM_DAYS = 280;

    private Pregnancy() {}

    /**
     * Completed weeks on {@code today} (the PERSON's local date), or null when there is no due date or the number would not
     * make sense to show (before week 1, or after week 42).
     */
    public static Integer week(LocalDate dueDate, LocalDate today) {
        if (dueDate == null || today == null) return null;
        long daysToGo = ChronoUnit.DAYS.between(today, dueDate);
        long gestationDays = TERM_DAYS - daysToGo;
        if (gestationDays < 7 || gestationDays >= 43 * 7) return null;
        return (int) (gestationDays / 7);
    }
}
