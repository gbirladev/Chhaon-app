package org.smartgurukul.chhaon.admin;

import org.springframework.stereotype.Component;

/** Dashboard only for now: problems are recorded in admin_alerts and shown on the Monitor, nothing is sent anywhere. */
@Component
public class NoopAdminAlertNotifier implements AdminAlertNotifier {

    @Override
    public void opened(HealthCheck check) {
        // nothing: the Monitor shows open alerts
    }

    @Override
    public void resolved(String checkKey, String lastDetail) {
        // nothing
    }
}
