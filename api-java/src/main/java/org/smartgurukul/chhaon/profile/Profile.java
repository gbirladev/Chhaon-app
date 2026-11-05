package org.smartgurukul.chhaon.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A caregiver (child) user. Maps the existing public.profiles table.
 *
 * Historically profiles.id equalled the Supabase auth.users id. Under custom JWT auth we own
 * the identity: we generate the UUID ourselves and store the password in {@link UserCredential}
 * (keyed by this id). The old FK to auth.users is dropped in migration V1.
 */
@Entity
@Table(name = "profiles")
@Getter
@Setter
public class Profile {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name")
    private String name;

    @Column(name = "email")
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "country")
    private String country;

    @Column(name = "timezone", nullable = false)
    private String timezone = "Asia/Kolkata";

    @Column(name = "language", nullable = false)
    private String language = "en";

    @Column(name = "plan", nullable = false)
    private String plan = "free";

    @Column(name = "trial_ends_at", nullable = false)
    private OffsetDateTime trialEndsAt;

    @Column(name = "is_admin", nullable = false)
    private boolean admin = false;

    /** Which way today's medicines are drawn (null = default). Checked by the DB against the allowed names. */
    @Column(name = "today_layout")
    private String todayLayout;

    @Column(name = "privacy_accepted_at")
    private OffsetDateTime privacyAcceptedAt;

    @Column(name = "policy_version")
    private String policyVersion;

    // Caregiver WhatsApp-alert settings (see migration 0015).
    @Column(name = "alert_phone")
    private String alertPhone;

    @Column(name = "alert_requested_at")
    private OffsetDateTime alertRequestedAt;

    @Column(name = "alert_confirmed_at")
    private OffsetDateTime alertConfirmedAt;

    @Column(name = "alert_consent_at")
    private OffsetDateTime alertConsentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
