package org.smartgurukul.chhaon.admin;

import org.smartgurukul.chhaon.recipient.PhoneNormalizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Demo and test families the Monitor's "not said yes" counts leave out (the Google Play review family sat in the consent check
 * for days). Nothing is changed or deleted, and they are NOT the canary (docs/canary.md has its own behaviour). A person is a
 * demo family when:
 * <ul>
 *   <li>their WhatsApp number is in the fake test range +91 99999 xxxxx, or</li>
 *   <li>an owner of their circle signs in with the store review phone ({@code app.otp.review-phone}, env OTP_REVIEW_PHONE).</li>
 * </ul>
 * Real families are counted exactly as before.
 */
@Component
public class DemoFamilies {

    /** The fake test range: +91 99999 xxxxx. */
    static final String FAKE_PREFIX = "+9199999";
    private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{7,14}");

    /** The normalised review phone, or null when none is configured. Only digits after "+", so it is safe inside SQL text. */
    private final String reviewPhone;

    public DemoFamilies(@Value("${app.otp.review-phone:}") String reviewPhone) {
        String n = PhoneNormalizer.normalize(reviewPhone);
        this.reviewPhone = n != null && E164.matcher(n).matches() ? n : null;
    }

    /** SQL that is true when care recipient {@code r} (a table alias) is a demo / test family. */
    public String isDemo(String r) {
        String fake = r + ".whatsapp_phone like '" + FAKE_PREFIX + "%'";
        if (reviewPhone == null) return "(" + fake + ")";
        return "(" + fake + " or exists (select 1 from care_circle_members dm join profiles dp on dp.id = dm.user_id " +
                "where dm.circle_id = " + r + ".circle_id and dm.role = 'owner' and dp.phone = '" + reviewPhone + "'))";
    }
}
