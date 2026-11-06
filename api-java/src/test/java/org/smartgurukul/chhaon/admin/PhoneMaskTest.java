package org.smartgurukul.chhaon.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The Monitor's phone masking: country code + last 4 digits, safe for short, odd and missing numbers. Fake numbers only. */
class PhoneMaskTest {

    @Test
    void anIndianNumberKeepsTheCountryCodeAndTheLastFourDigits() {
        assertThat(PhoneMask.mask("+919999900001")).isEqualTo("+91 ••••• •0001");
        assertThat(PhoneMask.mask("+91 99999 00001")).isEqualTo("+91 ••••• •0001");
        assertThat(PhoneMask.mask("+91-99999-00001")).isEqualTo("+91 ••••• •0001");
        assertThat(PhoneMask.mask("00919999900001")).isEqualTo("+91 ••••• •0001");
    }

    @Test
    void otherCountryCodesAreRecognised() {
        assertThat(PhoneMask.mask("+19999900001")).isEqualTo("+1 ••••• •0001");     // US / Canada: 1 digit
        assertThat(PhoneMask.mask("+449999900001")).isEqualTo("+44 ••••• •0001");   // UK: 2 digits
        assertThat(PhoneMask.mask("+971999990001")).isEqualTo("+971 ••••• 0001");   // UAE: 3 digits, 9-digit national number
        assertThat(PhoneMask.mask("+61999990001")).isEqualTo("+61 ••••• 0001");     // Australia
    }

    @Test
    void withoutACountryCodeOnlyTheLastFourDigitsStay() {
        assertThat(PhoneMask.mask("9999900001")).isEqualTo("••••• •0001");
    }

    @Test
    void shortOddAndMissingNumbersAreSafe() {
        assertThat(PhoneMask.mask(null)).isNull();
        assertThat(PhoneMask.mask("")).isEmpty();
        assertThat(PhoneMask.mask("–")).isEqualTo("–");            // no digits: nothing to hide
        assertThat(PhoneMask.mask("12345")).isEqualTo("••••");
        assertThat(PhoneMask.mask("+9199")).isEqualTo("••••");
        assertThat(PhoneMask.mask("+0999990001")).isEqualTo("••••"); // not a real country code: hide it all
        assertThat(PhoneMask.mask("+91 9999900001 ext 2")).doesNotContain("99999");
    }

    @Test
    void phoneFieldsAreMaskedAtAnyDepthAndEverythingElseIsKept() throws Exception {
        JsonNode contacts = new ObjectMapper().readTree("[{\"name\":\"Uncle\",\"phone\":\"+919999900002\"}]");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "Rajesh");
        row.put("whatsapp_phone", "+919999900001");
        row.put("reminder_user_phone", null);
        row.put("phone_opted_out", false);
        row.put("emergency_contacts", contacts);
        row.put("caregivers", List.of(Map.of("name", "Ananya", "phone", "+919999900003")));
        Map<String, Object> out = PhoneMask.maskRow(row);
        assertThat(out.get("name")).isEqualTo("Rajesh");
        assertThat(out.get("whatsapp_phone")).isEqualTo("+91 ••••• •0001");
        assertThat(out.get("reminder_user_phone")).isNull();
        assertThat(out.get("phone_opted_out")).isEqualTo(false);
        assertThat(out.get("emergency_contacts").toString()).contains("+91 ••••• •0002").contains("Uncle").doesNotContain("9999900002");
        assertThat(out.get("caregivers").toString()).contains("+91 ••••• •0003").contains("Ananya");
        assertThat(contacts.toString()).contains("+919999900002");   // the input itself is not changed
    }
}
