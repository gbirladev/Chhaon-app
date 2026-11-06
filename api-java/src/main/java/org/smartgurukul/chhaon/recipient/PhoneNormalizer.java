package org.smartgurukul.chhaon.recipient;

import java.util.regex.Pattern;

/**
 * Normalises a WhatsApp phone number to E.164, mirroring the web client's normalizePhone
 * (web/src/lib/api.ts). Returns null when the (non-blank) input cannot be made valid.
 * The DB additionally enforces {@code whatsapp_phone ~ '^\+[1-9][0-9]{7,14}$'}.
 */
public final class PhoneNormalizer {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final Pattern INDIA = Pattern.compile("^\\+91[6-9]\\d{9}$");
    /** Any 10-digit Indian number: mobiles (6-9) and landlines with their area code (e.g. 011 9999 0001 -> +911199990001). */
    private static final Pattern INDIA_CALLABLE = Pattern.compile("^\\+91[1-9]\\d{9}$");

    private PhoneNormalizer() {}

    /** Convert Devanagari / Arabic-Indic / Persian / fullwidth digits to ASCII. */
    private static String asciiDigits(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '०' && c <= '९') c = (char) ('0' + (c - '०')); // Devanagari
            else if (c >= '٠' && c <= '٩') c = (char) ('0' + (c - '٠')); // Arabic-Indic
            else if (c >= '۰' && c <= '۹') c = (char) ('0' + (c - '۰')); // Persian
            else if (c >= '０' && c <= '９') c = (char) ('0' + (c - '０')); // fullwidth
            out.append(c);
        }
        return out.toString();
    }

    /** @return normalised E.164 string, or null if the input is not a valid number. India: mobiles only (WhatsApp). */
    public static String normalize(String raw) {
        return normalize(raw, INDIA);
    }

    /**
     * For numbers that are only ever CALLED, never sent WhatsApp (a doctor's clinic): Indian landlines are accepted too.
     * @return normalised E.164 string, or null if the input is not a valid number.
     */
    public static String normalizeCallable(String raw) {
        return normalize(raw, INDIA_CALLABLE);
    }

    private static String normalize(String raw, Pattern india) {
        if (raw == null) return null;
        String s = asciiDigits(raw).trim();
        if (s.isEmpty()) return null;
        // Reject anything with letters.
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) return null;
        }
        boolean hadPlus = s.startsWith("+");
        // Strip everything that isn't a digit.
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;

        String candidate;
        if (hadPlus) {
            candidate = "+" + digits;
        } else if (s.startsWith("00")) {
            candidate = "+" + digits.substring(2);
        } else if (digits.length() == 12 && digits.startsWith("91")) {
            candidate = "+" + digits;
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            candidate = "+91" + digits.substring(1);
        } else {
            candidate = "+91" + digits; // assume India
        }

        if (!E164.matcher(candidate).matches()) return null;
        if (candidate.startsWith("+91") && !india.matcher(candidate).matches()) return null;
        return candidate;
    }
}
