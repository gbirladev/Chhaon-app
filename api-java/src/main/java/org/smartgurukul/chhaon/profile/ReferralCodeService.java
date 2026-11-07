package org.smartgurukul.chhaon.profile;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Referral codes every caregiver can share (migration 0061, docs/referral-codes.md): a default code made from the first name
 * (ANANYA27), the person's own choice instead (4-15 letters and digits), at most 3 changes, old codes kept as aliases so
 * shared links keep counting. Campaign codes (doctor D001 / IND001, creator CRE022, anything with symbols) are not in the
 * table and are accepted at sign-up exactly as before. Without the table (Sydney not migrated yet) everything behaves as
 * before: no codes, every format-valid code accepted.
 */
@Service
public class ReferralCodeService {

    /** A user code: 4-15 capital letters and digits. */
    static final Pattern USER_CODE = Pattern.compile("^[A-Z0-9]{4,15}$");
    /** The campaign pattern (doctor and creator codes): never a user code, so the two can never collide. */
    static final Pattern CAMPAIGN = Pattern.compile("^[A-Z]{1,4}[0-9]{3,4}$");
    static final Set<String> RESERVED = Set.of("CHHAON", "ADMIN", "FREE", "DOCTOR", "SUPPORT", "HELP", "TEST", "NULL");
    /** The first code plus 3 changes. */
    static final int MAX_CODES = 4;
    static final String LINK_BASE = "https://chhaon.site/?ref=";

    public enum Status { AVAILABLE, YOURS, TAKEN, RESERVED, FORMAT }

    /** At sign-up: is this code acceptable for the person entering it? */
    public enum Use { OK, UNKNOWN, OWN }

    public record Check(String code, Status status, List<String> suggestions) {}

    public record Mine(boolean installed, String code, String link, long families, int changesLeft) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private volatile boolean installed;   // once seen, the table stays (a migration is never rolled back)

    public ReferralCodeService(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    boolean installed() {
        if (installed) return true;
        Boolean present = jdbc.queryForObject("select to_regclass('public.referral_codes') is not null", Boolean.class);
        installed = Boolean.TRUE.equals(present);
        return installed;
    }

    public static String normalise(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    /** True for a code that could belong to a person (and so must be in the table at sign-up). */
    static boolean userShaped(String code) {
        return USER_CODE.matcher(code).matches() && !CAMPAIGN.matcher(code).matches();
    }

    static boolean reserved(String code) {
        return RESERVED.contains(code) || code.contains("CHHAON");
    }

    // ---------------------------------------------------------------- the person's own code

    /** The person's code, created from their first name the first time. */
    public Mine mine(UUID owner, String name) {
        if (!installed()) return new Mine(false, null, null, 0, 0);
        String code = primary(owner);
        if (code == null) code = createDefault(owner, name);
        return new Mine(true, code, LINK_BASE + code, families(owner), changesLeft(owner));
    }

    String primary(UUID owner) {
        List<String> rows = jdbc.queryForList("select code from referral_codes where owner = ? and is_primary", String.class, owner);
        return rows.isEmpty() ? null : rows.get(0);
    }

    int changesLeft(UUID owner) {
        Integer n = jdbc.queryForObject("select count(*) from referral_codes where owner = ?", Integer.class, owner);
        return Math.max(0, MAX_CODES - (n == null ? 0 : n));
    }

    /** Families (other real caregivers) who joined with any of this person's codes, old ones included. */
    public long families(UUID owner) {
        Long n = jdbc.queryForObject("select count(*) from profiles p where not p.is_canary and p.id <> ? and p.referred_by in " +
                "(select code from referral_codes where owner = ?)", Long.class, owner, owner);
        return n == null ? 0 : n;
    }

    private String createDefault(UUID owner, String name) {
        String base = firstNameLetters(name);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 40; attempt++) {
            String code = base + String.format("%02d", rnd.nextInt(10, 100));
            if (attempt >= 30) code = base + (char) ('A' + rnd.nextInt(26)) + String.format("%02d", rnd.nextInt(10, 100));
            if (!userShaped(code) || reserved(code)) continue;
            try {
                jdbc.update("insert into referral_codes (code, owner, is_primary) values (?, ?, true)", code, owner);
                return code;
            } catch (DuplicateKeyException e) {
                String already = primary(owner);   // two screens opened at once: the other one made it
                if (already != null) return already;
            }
        }
        throw new IllegalStateException("could not make a referral code");
    }

    /** First name, ASCII letters only, upper case, at most 13 letters; FRIEND when it has fewer than 2. */
    static String firstNameLetters(String name) {
        String first = name == null ? "" : name.trim().split("\\s+")[0];
        String ascii = Normalizer.normalize(first, Normalizer.Form.NFD).replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
        if (ascii.length() < 2) ascii = "FRIEND";
        return ascii.length() > 13 ? ascii.substring(0, 13) : ascii;
    }

    // ---------------------------------------------------------------- checking and changing

    public Check check(UUID owner, String raw, String name) {
        String code = normalise(raw);
        if (!installed()) return new Check(code, Status.FORMAT, List.of());
        if (!USER_CODE.matcher(code).matches() || CAMPAIGN.matcher(code).matches()) {
            return new Check(code, Status.FORMAT, suggestions(code, name));
        }
        if (reserved(code)) return new Check(code, Status.RESERVED, suggestions(code, name));
        List<String> owners = jdbc.queryForList("select owner::text from referral_codes where code = ?", String.class, code);
        if (owners.isEmpty()) return new Check(code, Status.AVAILABLE, List.of());
        if (owners.get(0).equals(owner.toString())) return new Check(code, Status.YOURS, List.of());
        return new Check(code, Status.TAKEN, suggestions(code, name));
    }

    /** Result of a change: the new state, or why not (with suggestions). */
    public record Change(Check check, Mine mine, boolean tooMany) {}

    public Change change(UUID owner, String raw, String name) {
        Check c = check(owner, raw, name);
        if (c.status() == Status.YOURS) {
            if (!c.code().equals(primary(owner))) {
                tx.executeWithoutResult(s -> {
                    jdbc.update("update referral_codes set is_primary = false where owner = ? and is_primary", owner);
                    jdbc.update("update referral_codes set is_primary = true where owner = ? and code = ?", owner, c.code());
                });
            }
            return new Change(c, mine(owner, name), false);
        }
        if (c.status() != Status.AVAILABLE) return new Change(c, null, false);
        if (primary(owner) != null && changesLeft(owner) == 0) return new Change(c, null, true);
        try {
            tx.executeWithoutResult(s -> {
                jdbc.update("update referral_codes set is_primary = false where owner = ? and is_primary", owner);
                jdbc.update("insert into referral_codes (code, owner, is_primary) values (?, ?, true)", c.code(), owner);
            });
        } catch (DuplicateKeyException e) {   // taken a moment ago by someone else
            return new Change(new Check(c.code(), Status.TAKEN, suggestions(c.code(), name)), null, false);
        }
        return new Change(c, mine(owner, name), false);
    }

    /** Up to 3 free codes close to what the person wanted: WANTED + a digit, WANTED + surname initial, FIT/MY/TEAM + WANTED. */
    List<String> suggestions(String wanted, String name) {
        String w = wanted.replaceAll("[^A-Z0-9]", "");
        if (w.isEmpty()) w = firstNameLetters(name);
        String w14 = w.length() > 14 ? w.substring(0, 14) : w;
        String w13 = w.length() > 13 ? w.substring(0, 13) : w;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<String> digits = new ArrayList<>();
        for (int d = 1; d <= 9; d++) digits.add(w14 + d);
        Collections.shuffle(digits, rnd);
        List<String> groups = new ArrayList<>();
        groups.addAll(digits.subList(0, 3));
        String surname = surnameInitial(name);
        if (surname != null) groups.add(w14 + surname);
        for (String prefix : List.of("FIT", "MY", "TEAM")) {
            if (w.startsWith(prefix)) { groups.add(w14 + (char) ('A' + rnd.nextInt(26))); continue; }   // no FITFIT...
            String s = prefix + w;
            groups.add(s.length() > 15 ? s.substring(0, 15) : s);
        }
        for (int i = 0; i < 4; i++) groups.add(w13 + String.format("%02d", rnd.nextInt(10, 100)));
        // Variety first: one with a digit, one with the surname letter, one with a prefix; then the rest.
        List<String> ordered = new ArrayList<>();
        ordered.add(digits.get(0));
        if (surname != null) ordered.add(w14 + surname);
        ordered.add(groups.get(groups.size() - 7 + rnd.nextInt(3)));   // one of the FIT / MY / TEAM forms
        ordered.addAll(groups);
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (String s : ordered) if (userShaped(s) && !reserved(s) && !s.equals(wanted)) candidates.add(s);
        if (candidates.isEmpty()) return List.of();
        List<String> list = new ArrayList<>(candidates);
        List<String> taken = jdbc.queryForList("select code from referral_codes where code = any(string_to_array(?, ','))",
                String.class, String.join(",", list));   // codes are A-Z0-9 only: no comma inside
        List<String> free = new ArrayList<>();
        for (String s : list) if (!taken.contains(s) && free.size() < 3) free.add(s);
        return free;
    }

    static String surnameInitial(String name) {
        if (name == null) return null;
        String[] parts = name.trim().split("\\s+");
        if (parts.length < 2) return null;
        String last = Normalizer.normalize(parts[parts.length - 1], Normalizer.Form.NFD).replaceAll("[^A-Za-z]", "");
        return last.isEmpty() ? null : last.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- at sign-up

    /** Whether a code typed or carried in at sign-up may be saved for this person. */
    public Use forSignup(UUID person, String code) {
        if (!userShaped(code) || !installed()) return Use.OK;   // campaign codes and symbols: as before
        List<String> owners = jdbc.queryForList("select owner::text from referral_codes where code = ?", String.class, code);
        if (owners.isEmpty()) return Use.UNKNOWN;
        return owners.get(0).equals(person.toString()) ? Use.OWN : Use.OK;
    }
}
