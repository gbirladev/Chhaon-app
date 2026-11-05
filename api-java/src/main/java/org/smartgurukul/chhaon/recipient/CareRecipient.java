package org.smartgurukul.chhaon.recipient;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The aging parent (or a "self" profile) a caregiver manages. Maps public.care_recipients. */
@Entity
@Table(name = "care_recipients")
@Getter
@Setter
public class CareRecipient {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "circle_id", nullable = false, updatable = false)
    private UUID circleId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "relation", nullable = false)
    private String relation = "other";

    @Column(name = "call_name")
    private String callName;

    @Column(name = "age")
    private Short age;

    @Column(name = "dob")
    private LocalDate dob;

    @Column(name = "city")
    private String city;

    @Column(name = "whatsapp_phone")
    private String whatsappPhone;

    @Column(name = "timezone", nullable = false)
    private String timezone = "Asia/Kolkata";

    @Column(name = "language", nullable = false)
    private String language = "en";

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "conditions", nullable = false, columnDefinition = "text[]")
    private List<String> conditions = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allergies", nullable = false, columnDefinition = "text[]")
    private List<String> allergies = new ArrayList<>();

    @Column(name = "blood_group")
    private String bloodGroup;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "doctor_contacts", nullable = false, columnDefinition = "jsonb")
    private List<Contact> doctorContacts = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "emergency_contacts", nullable = false, columnDefinition = "jsonb")
    private List<Contact> emergencyContacts = new ArrayList<>();

    @Column(name = "checkin_time", nullable = false)
    private LocalTime checkinTime = LocalTime.of(8, 0);

    @Column(name = "is_self", nullable = false)
    private boolean self = false;

    @Column(name = "caregiver_attested_at")
    private OffsetDateTime caregiverAttestedAt;

    // --- System-managed consent fields: NEVER set from a client request ---
    @Column(name = "consent_at", insertable = false, updatable = false)
    private OffsetDateTime consentAt;

    @Column(name = "opted_out_at", insertable = false, updatable = false)
    private OffsetDateTime optedOutAt;

    @Column(name = "consent_requested_at", insertable = false, updatable = false)
    private OffsetDateTime consentRequestedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // --- Who gets the WhatsApp reminders on whatsapp_phone (migration 20261105000044, docs/reminder-receiver.md) ---
    /** person (default) | caregiver | helper. */
    @Column(name = "reminder_to", nullable = false)
    private String reminderTo = "person";

    /** The name the receiver is greeted with (helper, or the caregiver's own name); null for the person themself. */
    @Column(name = "reminder_name")
    private String reminderName;

    /** A helper's relation in the family's words ("maid", "Nani"); app only, never sent on WhatsApp. */
    @Column(name = "reminder_relation")
    private String reminderRelation;

    /** The family member who gets the reminders when reminderTo = caregiver. */
    @Column(name = "reminder_user_id")
    private UUID reminderUserId;
}
