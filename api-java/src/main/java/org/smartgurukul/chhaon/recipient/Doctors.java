package org.smartgurukul.chhaon.recipient;

import org.smartgurukul.chhaon.recipient.dto.RecipientDtos.DoctorDto;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * "Your doctors" (2026-11-06): the family's own list of the person's doctors (name, speciality, clinic or hospital, phone,
 * address), kept in {@code care_recipients.doctor_contacts}. One tap to call or get directions; the first doctor is the
 * starting "Prepared for" on the doctor summary, and name + speciality + phone are on the public emergency card.
 * Only what the family types: Chhaon never suggests, ranks or recommends a doctor.
 * Error messages never repeat the phone number (it must not end up in logs).
 */
public final class Doctors {

    public static final int MAX = 5;
    static final int NAME_MAX = 60;
    static final int SPECIALITY_MAX = 40;
    static final int CLINIC_MAX = 80;
    static final int ADDRESS_MAX = 200;

    /** Control characters and the invisible marks that can reorder or hide text (ZWJ/ZWNJ stay: Hindi needs them). */
    private static final Pattern HIDDEN = Pattern.compile("[\\p{Cc}\\u200B\\u200E\\u200F\\u202A-\\u202E\\u2060\\u2066-\\u2069\\uFEFF\\u00AD]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private Doctors() {}

    /** The list to store: blank rows dropped, text cleaned, phone in E.164. 400 when something cannot be right. */
    public static List<Contact> clean(List<DoctorDto> in) {
        if (in == null) return List.of();
        List<Contact> out = new ArrayList<>();
        for (DoctorDto d : in) {
            if (d == null) continue;
            String name = text(d.name(), NAME_MAX, "name");
            String speciality = text(d.speciality(), SPECIALITY_MAX, "speciality");
            String clinic = text(d.clinic(), CLINIC_MAX, "clinic or hospital");
            String address = text(d.address(), ADDRESS_MAX, "address");
            String phone = phone(d.phone());
            if (name == null && speciality == null && clinic == null && address == null && phone == null) continue;
            if (name == null) throw new ResponseStatusException(BAD_REQUEST, "Please enter the doctor's name");
            out.add(new Contact(name, phone, speciality, clinic, address));
        }
        if (out.size() > MAX) {
            throw new ResponseStatusException(BAD_REQUEST, "At most " + MAX + " doctors");
        }
        return out;
    }

    /** What the API returns for a stored list (an older row may have only a name, or only a phone). */
    public static List<DoctorDto> toDtos(List<Contact> in) {
        if (in == null) return List.of();
        return in.stream()
                .map(c -> new DoctorDto(c.getName(), c.getPhone(), c.getSpeciality(), c.getClinic(), c.getAddress()))
                .toList();
    }

    private static String text(String raw, int max, String field) {
        if (raw == null) return null;
        String v = SPACES.matcher(HIDDEN.matcher(raw).replaceAll(" ")).replaceAll(" ").trim();
        if (v.isEmpty()) return null;
        if (v.codePointCount(0, v.length()) > max) {
            throw new ResponseStatusException(BAD_REQUEST, "The doctor's " + field + " is too long (at most " + max + " characters)");
        }
        return v;
    }

    private static String phone(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String normalized = PhoneNormalizer.normalizeCallable(raw);   // landlines too: a doctor is only called
        if (normalized == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Please check the doctor's phone number");
        }
        return normalized;
    }
}
