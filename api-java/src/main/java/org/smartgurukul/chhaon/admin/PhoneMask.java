package org.smartgurukul.chhaon.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hides phone numbers before they leave the admin Monitor (docs/admin-monitor.md): only the country code and the last 4 digits are
 * sent to the browser, e.g. "+919999900001" becomes "+91 ••••• •0001". Names stay (the owner needs them to help a family); every
 * family view is audited (admin_audit).
 */
public final class PhoneMask {

    static final char DOT = '•';
    /** What a number too short to show any part of becomes. */
    static final String HIDDEN = "••••";

    private PhoneMask() {}

    /**
     * One number. Null stays null, text without digits stays as it is (nothing to hide). With a leading "+": country code (by the
     * E.164 prefix rules), then dots, then the last 4 digits. Without "+": dots and the last 4 digits. Fewer than 8 digits: all hidden.
     */
    public static String mask(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return phone;
        String trimmed = phone.strip();
        boolean plus = trimmed.startsWith("+") || trimmed.startsWith("00");   // "0091..." is "+91..." dialled from abroad
        if (trimmed.startsWith("00")) digits = digits.substring(2);
        if (digits.length() < 8 || (plus && digits.charAt(0) == '0')) return HIDDEN;
        String cc = plus ? digits.substring(0, countryCodeLength(digits)) : "";
        String national = digits.substring(cc.length());
        String last4 = national.substring(national.length() - 4);
        int hidden = national.length() - 4;
        int first = Math.min(5, hidden);   // "••••• •0001", like a written Indian number
        String dots = String.valueOf(DOT).repeat(first) + " " + String.valueOf(DOT).repeat(hidden - first);
        return (plus ? "+" + cc + " " : "") + dots + last4;
    }

    /** Length of the E.164 country code at the start of {@code digits}: 1 for 1 and 7, 2 for the two-digit codes, else 3. */
    static int countryCodeLength(String digits) {
        char a = digits.charAt(0);
        if (a == '1' || a == '7') return 1;
        int two = Integer.parseInt(digits.substring(0, 2));
        boolean twoDigit = switch (two) {
            case 20, 27, 30, 31, 32, 33, 34, 36, 39, 40, 41, 43, 44, 45, 46, 47, 48, 49, 51, 52, 53, 54, 55, 56, 57, 58,
                 60, 61, 62, 63, 64, 65, 66, 81, 82, 84, 86, 90, 91, 92, 93, 94, 95, 98 -> true;
            default -> false;
        };
        return twoDigit ? 2 : 3;
    }

    /** Is this field name a phone number field (phone, whatsapp_phone, alert_phone, reminder_user_phone, ...)? */
    static boolean isPhoneKey(String key) {
        return key != null && key.toLowerCase().endsWith("phone");
    }

    /**
     * A copy of a JSON-ready value (maps, lists, Jackson trees, as {@link Rows} makes them) with every text value under a phone
     * field masked, at any depth (caregivers[].phone, emergency_contacts[].phone, doctor_contacts[].phone, ...).
     */
    public static Object maskFields(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<Object, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(k, k instanceof String s && isPhoneKey(s) && x instanceof String t ? mask(t) : maskFields(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object x : l) out.add(maskFields(x));
            return out;
        }
        if (v instanceof JsonNode n) return maskTree(n);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> maskRow(Map<String, Object> row) {
        return (Map<String, Object>) maskFields(row);
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> maskRows(List<Map<String, Object>> rows) {
        return (List<Map<String, Object>>) maskFields(rows);
    }

    static JsonNode maskTree(JsonNode n) {
        if (n == null) return null;
        if (n.isObject()) {
            ObjectNode out = ((ObjectNode) n).deepCopy();
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode x = e.getValue();
                out.set(e.getKey(), isPhoneKey(e.getKey()) && x.isTextual() ? TextNode.valueOf(mask(x.asText())) : maskTree(x));
            }
            return out;
        }
        if (n.isArray()) {
            ArrayNode out = ((ArrayNode) n).arrayNode();
            for (JsonNode x : n) out.add(maskTree(x));
            return out;
        }
        return n;
    }
}
