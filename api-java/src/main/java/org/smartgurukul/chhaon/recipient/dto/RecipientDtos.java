package org.smartgurukul.chhaon.recipient.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

/** Request/response payloads for care recipients (parent profiles). */
public final class RecipientDtos {

    private RecipientDtos() {}

    public record ContactDto(String name, String phone) {}

    /*
     * careType: "older" | "pregnant" | "child" (a child never has reminderTo = person: 400). Left out: "older" on create, unchanged on update.
     * dueDate: "YYYY-MM-DD", pregnant only. Left out or null: unchanged on update; "" clears it. A profile that is not a
     * pregnancy never keeps a due date (switching away from pregnant clears it).
     */

    /** Create a parent profile. Mirrors the web form + create_care_recipient RPC payload. */
    public record CreateRecipientRequest(
            @NotBlank @Size(max = 100) String name,
            String relation,
            @Size(max = 40) String callName,
            @Min(0) @Max(130) Integer age,
            LocalDate dob,
            String city,
            String whatsappPhone,
            String timezone,
            String language,
            List<String> conditions,
            List<String> allergies,
            String bloodGroup,
            List<ContactDto> doctorContacts,
            List<ContactDto> emergencyContacts,
            LocalTime checkinTime,
            Boolean caregiverAttested,
            Boolean isSelf,
            String careType,
            String dueDate,
            // who gets the WhatsApp reminders (docs/reminder-receiver.md): person (default) | caregiver | helper
            String reminderTo,
            @Size(max = 60) String reminderName,
            @Size(max = 30) String reminderRelation,
            // daily check-in on/off; null = default (create: off for a pregnancy, else on) / keep (update)
            Boolean checkinEnabled,
            // en | hi for a helper / family member who gets the reminders; null or "" = the person's language
            String reminderLanguage) {
    }

    /** Update a parent profile. Editable fields only; is_self and circle are immutable. */
    public record UpdateRecipientRequest(
            @NotBlank @Size(max = 100) String name,
            String relation,
            @Size(max = 40) String callName,
            @Min(0) @Max(130) Integer age,
            LocalDate dob,
            String city,
            String whatsappPhone,
            String timezone,
            String language,
            List<String> conditions,
            List<String> allergies,
            String bloodGroup,
            List<ContactDto> doctorContacts,
            List<ContactDto> emergencyContacts,
            LocalTime checkinTime,
            Boolean caregiverAttested,
            String careType,
            String dueDate,
            // who gets the WhatsApp reminders; null = keep what is stored (an older app version)
            String reminderTo,
            @Size(max = 60) String reminderName,
            @Size(max = 30) String reminderRelation,
            // daily check-in on/off; null = default (create: off for a pregnancy, else on) / keep (update)
            Boolean checkinEnabled,
            // en | hi for a helper / family member who gets the reminders; null or "" = the person's language
            String reminderLanguage) {
    }

    public record RecipientResponse(
            String id,
            String circleId,
            String name,
            String relation,
            String callName,
            Integer age,
            LocalDate dob,
            String city,
            String whatsappPhone,
            String timezone,
            String language,
            List<String> conditions,
            List<String> allergies,
            String bloodGroup,
            List<ContactDto> doctorContacts,
            List<ContactDto> emergencyContacts,
            LocalTime checkinTime,
            boolean isSelf,
            String careType,
            LocalDate dueDate,
            OffsetDateTime caregiverAttestedAt,
            OffsetDateTime consentAt,
            OffsetDateTime optedOutAt,
            OffsetDateTime consentRequestedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String reminderTo,
            String reminderName,
            String reminderRelation,
            String reminderUserId,
            boolean checkinEnabled,
            String reminderLanguage,
            // place in the pilot line (docs/pilot-cap.md); null when not waiting
            Integer pilotPosition) {
    }
}
