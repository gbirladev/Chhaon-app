package org.smartgurukul.chhaon.admin;

/**
 * Tells the owner that a Monitor problem opened or was resolved. Today only the dashboard shows it ({@link NoopAdminAlertNotifier});
 * an e-mail version (Resend: RESEND_API_KEY + ADMIN_ALERT_EMAIL, see docs/TODO.md) can be added as another bean marked
 * {@code @Primary}. Implementations must never throw into the checker and must not put phone numbers or health data in messages.
 */
public interface AdminAlertNotifier {

    void opened(HealthCheck check);

    void resolved(String checkKey, String lastDetail);
}
