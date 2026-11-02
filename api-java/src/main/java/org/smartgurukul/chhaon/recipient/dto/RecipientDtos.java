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
            Boolean isSelf) {
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
            Boolean caregiverAttested) {
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
            OffsetDateTime caregiverAttestedAt,
            OffsetDateTime consentAt,
            OffsetDateTime optedOutAt,
            OffsetDateTime consentRequestedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }
}
