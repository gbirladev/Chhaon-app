package org.smartgurukul.chhaon.recipient;

import org.smartgurukul.chhaon.pilot.PilotService;
import org.smartgurukul.chhaon.profile.Profile;
import org.smartgurukul.chhaon.profile.ProfileRepository;
import org.smartgurukul.chhaon.recipient.dto.RecipientDtos.ContactDto;
import org.smartgurukul.chhaon.recipient.dto.RecipientDtos.CreateRecipientRequest;
import org.smartgurukul.chhaon.recipient.dto.RecipientDtos.RecipientResponse;
import org.smartgurukul.chhaon.recipient.dto.RecipientDtos.UpdateRecipientRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.PAYMENT_REQUIRED;

@Service
public class CareRecipientService {

    private static final Set<String> RELATIONS = Set.of(
            "father", "mother", "grandfather", "grandmother", "father_in_law", "mother_in_law",
            "spouse", "uncle", "aunt", "brother", "sister", "other", "self",
            // round 2 (migration 20261105000050): a child, a grandchild, a wife / husband
            "son", "daughter", "grandson", "granddaughter", "wife", "husband");
    private static final Set<String> BLOOD_GROUPS = Set.of(
            "A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-");
    private static final int MAX_LIST_ITEMS = 30;
    private static final int MAX_ITEM_LEN = 80;

    private final CareRecipientRepository recipients;
    private final CareCircleRepository circles;
    private final CareCircleMemberRepository members;
    /** The caller's own number and name, for "send the reminders to me" (reminder_to = caregiver). */
    private final ProfileRepository profiles;
    /** "You're #N in line" while the pilot is full (docs/pilot-cap.md). */
    private final PilotService pilot;

    public CareRecipientService(CareRecipientRepository recipients,
                                CareCircleRepository circles,
                                CareCircleMemberRepository members,
                                ProfileRepository profiles,
                                PilotService pilot) {
        this.recipients = recipients;
        this.circles = circles;
        this.members = members;
        this.profiles = profiles;
        this.pilot = pilot;
    }

    @Transactional(readOnly = true)
    public List<RecipientResponse> list(UUID userId) {
        return recipients.findAllForUser(userId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public RecipientResponse get(UUID userId, UUID id) {
        return toResponse(requireMemberRecipient(userId, id));
    }

    @Transactional
    public RecipientResponse create(UUID userId, CreateRecipientRequest req) {
        boolean isSelf = Boolean.TRUE.equals(req.isSelf());

        // Plan limit (clean message; the DB limit_parents trigger is the backstop).
        if (!isSelf) {
            Integer max = recipients.maxParentsForUser(userId); // null = unlimited
            if (max != null && recipients.countNonSelfForUser(userId) >= max) {
                throw new ResponseStatusException(PAYMENT_REQUIRED,
                        "You've reached your plan's limit of parent profiles. Upgrade to add more.");
            }
        }

        OffsetDateTime now = OffsetDateTime.now();

        // 1) New circle; the DB trigger handle_new_circle adds the caller as owner member.
        CareCircle circle = new CareCircle();
        circle.setId(UUID.randomUUID());
        circle.setCreatedBy(userId);
        circle.setCreatedAt(now);
        circles.saveAndFlush(circle);

        // 2) The recipient.
        CareRecipient r = new CareRecipient();
        r.setId(UUID.randomUUID());
        r.setCircleId(circle.getId());
        r.setSelf(isSelf);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        r.setRelation(isSelf ? "self" : normalizeRelation(req.relation()));
        r.setCallName(isSelf ? null : trimToNull(req.callName()));
        r.setName(req.name().trim());
        r.setAge(req.age() == null ? null : req.age().shortValue());
        r.setDob(req.dob());
        r.setCity(trimToNull(req.city()));
        r.setTimezone(resolveTimezone(req.timezone()));
        r.setLanguage(normalizeLanguage(req.language()));
        r.setConditions(sanitizeList(req.conditions()));
        r.setAllergies(sanitizeList(req.allergies()));
        r.setBloodGroup(validateBloodGroup(req.bloodGroup()));
        r.setWhatsappPhone(resolvePhone(req.whatsappPhone()));
        r.setDoctorContacts(toContacts(req.doctorContacts()));
        r.setEmergencyContacts(toContacts(req.emergencyContacts()));
        if (req.checkinTime() != null) r.setCheckinTime(req.checkinTime());
        if (Boolean.TRUE.equals(req.caregiverAttested())) r.setCaregiverAttestedAt(now);
        applyCareType(r, req.careType(), req.dueDate());
        applyReceiver(r, userId, null, req.reminderTo(), req.reminderName(), req.reminderRelation());
        refuseChildReceiver(r);
        // The daily check-in: a new pregnancy starts without it (gentle by default), everyone else with it.
        r.setCheckinEnabled(req.checkinEnabled() != null ? req.checkinEnabled() : !"pregnant".equals(r.getCareType()));
        applyReminderLanguage(r, req.reminderLanguage());

        try {
            recipients.saveAndFlush(r);
        } catch (DataAccessException e) {
            throw translate(e);
        }
        // TODO(port WhatsApp): if a phone was set, trigger a consent request here.
        return toResponse(r);
    }

    @Transactional
    public RecipientResponse update(UUID userId, UUID id, UpdateRecipientRequest req) {
        CareRecipient r = requireMemberRecipient(userId, id);
        Receiver before = new Receiver(r.getReminderTo(), r.getReminderUserId(), r.getWhatsappPhone());
        // is_self and circle are immutable; relation forced to 'self' for a self profile.
        r.setRelation(r.isSelf() ? "self" : normalizeRelation(req.relation()));
        r.setCallName(r.isSelf() ? null : trimToNull(req.callName()));
        r.setName(req.name().trim());
        r.setAge(req.age() == null ? null : req.age().shortValue());
        r.setDob(req.dob());
        r.setCity(trimToNull(req.city()));
        r.setTimezone(resolveTimezone(req.timezone()));
        r.setLanguage(normalizeLanguage(req.language()));
        r.setConditions(sanitizeList(req.conditions()));
        r.setAllergies(sanitizeList(req.allergies()));
        r.setBloodGroup(validateBloodGroup(req.bloodGroup()));
        r.setWhatsappPhone(resolvePhone(req.whatsappPhone()));
        r.setDoctorContacts(toContacts(req.doctorContacts()));
        r.setEmergencyContacts(toContacts(req.emergencyContacts()));
        if (req.checkinTime() != null) r.setCheckinTime(req.checkinTime());
        if (Boolean.TRUE.equals(req.caregiverAttested()) && r.getCaregiverAttestedAt() == null) {
            r.setCaregiverAttestedAt(OffsetDateTime.now());
        }
        applyCareType(r, req.careType(), req.dueDate());
        applyReceiver(r, userId, before, req.reminderTo(), req.reminderName(), req.reminderRelation());
        refuseChildReceiver(r);
        if (req.checkinEnabled() != null) r.setCheckinEnabled(req.checkinEnabled());
        applyReminderLanguage(r, req.reminderLanguage());
        r.setUpdatedAt(OffsetDateTime.now());
        // DB triggers handle consent reset on phone change and dose clearing on timezone change.
        try {
            recipients.saveAndFlush(r);
        } catch (DataAccessException e) {
            throw translate(e);
        }
        return toResponse(r);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        CareRecipient r = requireMemberRecipient(userId, id);
        // Delete is owner-only.
        var member = members.findByCircleIdAndUserId(r.getCircleId(), userId).orElse(null);
        if (member == null || member.getRole() != MemberRole.owner) {
            throw new ResponseStatusException(NOT_FOUND, "Parent profile not found");
        }
        // TODO(port storage): purge medicine-photo and report files for this recipient first.
        recipients.delete(r); // ON DELETE CASCADE removes medicines, schedules, doses, reports, etc.
    }

    // --- who gets the WhatsApp reminders (docs/reminder-receiver.md) ---

    /** What was stored before an edit (null when creating). */
    private record Receiver(String to, UUID userId, String phone) {}

    /**
     * Sets reminder_to / reminder_name / reminder_relation / reminder_user_id, and for "Me" the number. Runs after the
     * WhatsApp number from the request was applied: that number is the one that RECEIVES the reminders, whoever it belongs to.
     * <ul>
     *   <li>person (default): the person themself, exactly as before.</li>
     *   <li>caregiver ("Me"): the caller. The number typed in the form wins; if none was typed, the caller's own login number,
     *       else their confirmed alert number. Another family member who already gets the reminders keeps them while the
     *       number is unchanged (a sibling saving the form does not take them over).</li>
     *   <li>helper: a name (required, greeted in the messages) and a short relation (app only); the number is required.</li>
     * </ul>
     * {@code to == null} on an edit keeps what is stored (an older app version that does not know the choice).
     * The database resets consent when the number or the receiver role changes, so the new receiver is asked again.
     */
    private void applyReceiver(CareRecipient r, UUID userId, Receiver before, String to, String name, String relation) {
        String mode = to == null || to.isBlank() ? (before == null ? "person" : null) : to.trim();
        if (r.isSelf()) mode = "person"; // your own profile: always your own reminders
        if (mode == null) return;
        switch (mode) {
            case "person" -> setReceiver(r, "person", null, null, null);
            case "helper" -> {
                String helper = trimToNull(name);
                if (helper == null) throw new ResponseStatusException(BAD_REQUEST, "Please enter the name of the person who gets the reminders");
                if (r.getWhatsappPhone() == null) throw new ResponseStatusException(BAD_REQUEST, "Please enter their WhatsApp number");
                setReceiver(r, "helper", limit(helper, 60), limit(trimToNull(relation), 30), null);
            }
            case "caregiver" -> {
                if (before != null && "caregiver".equals(before.to()) && before.userId() != null && !before.userId().equals(userId)
                        && Objects.equals(before.phone(), r.getWhatsappPhone())) {
                    return; // someone else in the family gets them; saving the form does not move them to the caller
                }
                Profile me = profiles.findById(userId).orElse(null);
                if (r.getWhatsappPhone() == null && me != null) {
                    String own = me.getPhone() != null ? me.getPhone() : me.getAlertPhone();
                    r.setWhatsappPhone(own == null ? null : PhoneNormalizer.normalize(own));
                }
                if (r.getWhatsappPhone() == null) throw new ResponseStatusException(BAD_REQUEST, "Please enter your WhatsApp number");
                setReceiver(r, "caregiver", me == null ? null : limit(trimToNull(me.getName()), 60), null, userId);
            }
            default -> throw new ResponseStatusException(BAD_REQUEST, "Unknown reminder receiver: choose person, caregiver or helper");
        }
    }

    /** A child never gets the reminders (docs/use-cases.md): the adult who gives the medicine does (me, or someone else). */
    private static void refuseChildReceiver(CareRecipient r) {
        if ("child".equals(r.getCareType()) && "person".equals(r.getReminderTo())) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "A child does not get the reminders: choose who gives the medicine (you or someone else)");
        }
    }

    /**
     * The language of the messages for the receiver when that is a helper or a family member (migration 20261105000050).
     * null = keep what is stored (update) / none (create); "" = none (use the person's language); otherwise en | hi.
     * When the person gets the reminders themself it is always cleared: their own "Language" field applies.
     */
    private static void applyReminderLanguage(CareRecipient r, String in) {
        if ("person".equals(r.getReminderTo())) { r.setReminderLanguage(null); return; }
        if (in == null) return;
        String v = in.trim();
        if (v.isEmpty()) { r.setReminderLanguage(null); return; }
        if (!RecipientLanguage.SUPPORTED.contains(v)) {
            throw new ResponseStatusException(BAD_REQUEST, "Unknown language for the reminder receiver: choose en or hi");
        }
        r.setReminderLanguage(v);
    }

    private static void setReceiver(CareRecipient r, String to, String name, String relation, UUID userId) {
        r.setReminderTo(to);
        r.setReminderName(name);
        r.setReminderRelation(relation);
        r.setReminderUserId(userId);
    }

    private static String limit(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max).trim();
    }

    // --- helpers ---

    /** Care types the app offers. A child's date of birth and the guardian's confirmation are saved through ChildController. */
    private static final Set<String> OFFERED_CARE_TYPES = Set.of("older", "pregnant", "child");
    /** A due date the family types must be plausible: up to about 10 months ahead, at most about 2 months past. */
    static final int DUE_DATE_MAX_AHEAD_DAYS = 300;
    static final int DUE_DATE_MAX_PAST_DAYS = 60;

    /**
     * Care type and due date, after the other fields (the time zone decides "today" for the due-date window).
     * careType null = keep (new profile: older). dueDate null = keep, "" = clear, "YYYY-MM-DD" = set (window checked only when
     * it changes, so an old date never blocks editing something else). Not a pregnancy = no due date: switching away from
     * pregnant clears it. A self profile ("Me") stays older.
     */
    private static void applyCareType(CareRecipient r, String careTypeIn, String dueDateIn) {
        String type = careTypeIn == null ? (r.getCareType() == null ? "older" : r.getCareType()) : careTypeIn.trim();
        if (!OFFERED_CARE_TYPES.contains(type)) {
            throw new ResponseStatusException(BAD_REQUEST, "Unknown care type: " + clip(type));
        }
        if (r.isSelf() && !"older".equals(type)) {
            throw new ResponseStatusException(BAD_REQUEST, "Your own profile cannot have a care type");
        }
        r.setCareType(type);
        if (!"pregnant".equals(type)) {
            r.setDueDate(null);
            return;
        }
        if (dueDateIn == null) return;   // keep
        if (dueDateIn.isBlank()) { r.setDueDate(null); return; }
        LocalDate due;
        try {
            due = LocalDate.parse(dueDateIn.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Due date must look like 2027-03-14");
        }
        if (due.equals(r.getDueDate())) return;
        LocalDate today = LocalDate.now(ZoneId.of(r.getTimezone()));
        if (due.isAfter(today.plusDays(DUE_DATE_MAX_AHEAD_DAYS)) || due.isBefore(today.minusDays(DUE_DATE_MAX_PAST_DAYS))) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "The due date should be within the next 10 months (or at most 2 months ago)");
        }
        r.setDueDate(due);
    }

    private static String clip(String s) {
        return s.length() > 20 ? s.substring(0, 20) : s;
    }

    private CareRecipient requireMemberRecipient(UUID userId, UUID id) {
        return recipients.findForUser(id, userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Parent profile not found"));
    }

    private static String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String normalizeRelation(String relation) {
        if (relation == null || relation.isBlank()) return "other";
        String r = relation.trim();
        return RELATIONS.contains(r) && !r.equals("self") ? r : "other";
    }

    private static String normalizeLanguage(String language) {
        return "hi".equals(language) ? "hi" : "en";
    }

    private static String resolveTimezone(String tz) {
        String zone = (tz == null || tz.isBlank()) ? "Asia/Kolkata" : tz.trim();
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Unknown timezone: " + zone);
        }
        return zone;
    }

    private static String validateBloodGroup(String bg) {
        String v = trimToNull(bg);
        if (v != null && !BLOOD_GROUPS.contains(v)) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid blood group: " + v);
        }
        return v;
    }

    private static String resolvePhone(String raw) {
        if (raw == null || raw.isBlank()) return null; // phone is optional
        String normalized = PhoneNormalizer.normalize(raw);
        if (normalized == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid WhatsApp phone number");
        }
        return normalized;
    }

    private static List<String> sanitizeList(List<String> in) {
        if (in == null) return List.of();
        List<String> cleaned = in.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .toList();
        if (cleaned.size() > MAX_LIST_ITEMS) {
            throw new ResponseStatusException(BAD_REQUEST, "At most " + MAX_LIST_ITEMS + " items allowed");
        }
        for (String s : cleaned) {
            if (s.length() > MAX_ITEM_LEN) {
                throw new ResponseStatusException(BAD_REQUEST, "Each item must be ≤ " + MAX_ITEM_LEN + " chars");
            }
        }
        return cleaned;
    }

    private static List<Contact> toContacts(List<ContactDto> in) {
        if (in == null) return List.of();
        return in.stream()
                .filter(c -> c != null && ((c.name() != null && !c.name().isBlank())
                        || (c.phone() != null && !c.phone().isBlank())))
                .map(c -> new Contact(c.name() == null ? null : c.name().trim(), c.phone()))
                .collect(Collectors.toList());
    }

    private static List<ContactDto> fromContacts(List<Contact> in) {
        if (in == null) return List.of();
        return in.stream().map(c -> new ContactDto(c.getName(), c.getPhone())).toList();
    }

    /** Map known Postgres trigger/constraint errors to clean HTTP responses. */
    private RuntimeException translate(DataAccessException e) {
        String msg = rootMessage(e).toLowerCase();
        if (msg.contains("plan_parents")) {
            return new ResponseStatusException(PAYMENT_REQUIRED,
                    "You've reached your plan's limit of parent profiles.");
        }
        if (msg.contains("self_number_in_use")) {
            return new ResponseStatusException(CONFLICT,
                    "This number is already used by your own profile.");
        }
        if (msg.contains("self_exists")) {
            return new ResponseStatusException(CONFLICT, "You already have a self profile.");
        }
        if (msg.contains("self_circle")) {
            return new ResponseStatusException(CONFLICT, "A self profile cannot be shared.");
        }
        if (msg.contains("care_recipients_reminder_receiver_valid") || msg.contains("care_recipients_reminder_to_known")) {
            return new ResponseStatusException(BAD_REQUEST, "Invalid reminder receiver");
        }
        if (msg.contains("care_recipients_reminder_language_known")) {
            return new ResponseStatusException(BAD_REQUEST, "Unknown language for the reminder receiver");
        }
        if (msg.contains("care_recipients_whatsapp_phone_check")) {
            return new ResponseStatusException(BAD_REQUEST, "Invalid WhatsApp phone number");
        }
        if (msg.contains("timezone")) {
            return new ResponseStatusException(BAD_REQUEST, "Unknown timezone");
        }
        return e; // unknown -> 500
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? "" : t.getMessage();
    }

    private RecipientResponse toResponse(CareRecipient r) {
        return new RecipientResponse(
                r.getId().toString(),
                r.getCircleId().toString(),
                r.getName(),
                r.getRelation(),
                r.getCallName(),
                r.getAge() == null ? null : r.getAge().intValue(),
                r.getDob(),
                r.getCity(),
                r.getWhatsappPhone(),
                r.getTimezone(),
                r.getLanguage(),
                r.getConditions(),
                r.getAllergies(),
                r.getBloodGroup(),
                fromContacts(r.getDoctorContacts()),
                fromContacts(r.getEmergencyContacts()),
                r.getCheckinTime(),
                r.isSelf(),
                r.getCareType(),
                r.getDueDate(),
                r.getCaregiverAttestedAt(),
                r.getConsentAt(),
                r.getOptedOutAt(),
                r.getConsentRequestedAt(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getReminderTo(),
                r.getReminderName(),
                r.getReminderRelation(),
                r.getReminderUserId() == null ? null : r.getReminderUserId().toString(),
                r.isCheckinEnabled(),
                r.getReminderLanguage(),
                // only someone who could be waiting is looked up (no number / agreed / stopped = never in line)
                r.getWhatsappPhone() != null && r.getConsentAt() == null && r.getOptedOutAt() == null
                        ? pilot.position(r.getId()) : null);
    }
}
