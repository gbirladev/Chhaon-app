package org.smartgurukul.chhaon.child;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The child vaccination timetable, read once from {@code vaccines/iap-acvip-2023.json} (the ONE data file; source, edition and the
 * rows left out are in its header). MUST BE REVIEWED BY A PAEDIATRICIAN BEFORE RELEASE.
 *
 * <p>Only turns a date of birth into SUGGESTED dates (date of birth + the age in the table). It never decides whether a vaccine is
 * needed and never knows a dose amount.
 */
@Component
public class VaccineSchedule {

    public static final String RESOURCE = "vaccines/iap-acvip-2023.json";

    /** One dose of the table. {@code order} = position in the file (the order inside an age group). */
    public record Dose(String key, String vaccine, String dose, String group, int years, int months, int weeks, int days,
                       String note, int order) {
        public LocalDate dueFrom(LocalDate dob) {
            return dob.plusYears(years).plusMonths(months).plusWeeks(weeks).plusDays(days);
        }
    }

    public record Name(String en, String hi) {}

    private final String edition;
    private final String source;
    private final boolean reviewed;
    private final List<Dose> doses;
    private final Map<String, Dose> byKey;
    private final Map<String, Name> names;

    public VaccineSchedule(ObjectMapper json) {
        JsonNode root;
        try (InputStream in = VaccineSchedule.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            root = json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.edition = root.path("edition").asText();
        this.source = root.path("source").asText();
        this.reviewed = root.path("reviewedByPaediatrician").asBoolean(false);
        Map<String, Name> n = new LinkedHashMap<>();
        root.path("vaccines").fields().forEachRemaining(e ->
                n.put(e.getKey(), new Name(e.getValue().path("en").asText(), e.getValue().path("hi").asText())));
        List<Dose> list = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int i = 0;
        for (JsonNode d : root.path("doses")) {
            JsonNode age = d.path("age");
            Dose dose = new Dose(d.path("key").asText(), d.path("vaccine").asText(), d.path("dose").asText(""),
                    d.path("group").asText(), age.path("years").asInt(0), age.path("months").asInt(0),
                    age.path("weeks").asInt(0), age.path("days").asInt(0),
                    d.hasNonNull("note") ? d.path("note").asText() : null, i++);
            if (!dose.key().matches("^[a-z0-9-]{1,40}$") || !keys.add(dose.key()) || !n.containsKey(dose.vaccine())) {
                throw new IllegalStateException("bad vaccine schedule row: " + dose.key());
            }
            list.add(dose);
        }
        if (edition.isBlank() || list.isEmpty()) throw new IllegalStateException("empty vaccine schedule");
        this.doses = Collections.unmodifiableList(list);
        Map<String, Dose> k = new LinkedHashMap<>();
        for (Dose d : list) k.put(d.key(), d);
        this.byKey = Collections.unmodifiableMap(k);
        this.names = Collections.unmodifiableMap(n);
    }

    public String edition() { return edition; }
    public String source() { return source; }
    public boolean reviewedByPaediatrician() { return reviewed; }
    public List<Dose> doses() { return doses; }
    public Dose dose(String key) { return byKey.get(key); }

    /** The vaccine's display name; an unknown code (a row from another edition) shows its code. */
    public Name name(String vaccineCode) {
        Name n = names.get(vaccineCode);
        return n != null ? n : new Name(vaccineCode, vaccineCode);
    }
}
