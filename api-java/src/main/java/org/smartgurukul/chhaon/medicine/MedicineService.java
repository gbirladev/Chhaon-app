package org.smartgurukul.chhaon.medicine;

import org.smartgurukul.chhaon.medicine.dto.MedicineDtos.CreateMedicineRequest;
import org.smartgurukul.chhaon.medicine.dto.MedicineDtos.MedicineFlagsRequest;
import org.smartgurukul.chhaon.medicine.dto.MedicineDtos.MedicineResponse;
import org.smartgurukul.chhaon.medicine.dto.MedicineDtos.StockAdjustRequest;
import org.smartgurukul.chhaon.medicine.dto.MedicineDtos.StockResponse;
import org.smartgurukul.chhaon.medicine.dto.MedicineDtos.UpdateMedicineRequest;
import org.smartgurukul.chhaon.recipient.CareRecipient;
import org.smartgurukul.chhaon.recipient.CareRecipientRepository;
import org.smartgurukul.chhaon.scheduler.SchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class MedicineService {

    private static final Logger log = LoggerFactory.getLogger(MedicineService.class);
    private static final Set<String> INSTRUCTIONS = Set.of("after_food", "before_food", "with_food");
    /** Fixed lists, the same as the check constraints of migration 0042 and web/src/lib/pillLook.ts. */
    static final Set<String> PILL_COLOURS =
            Set.of("white", "yellow", "orange", "pink", "red", "blue", "green", "brown", "purple");
    static final Set<String> PILL_SHAPES = Set.of("round", "oval", "long", "capsule");
    private static final int MAX_TIMES = 12;
    private static final int MAX_MEDICINES = 30;
    private static final int MAX_PHOTO_LEN = 300;

    private final MedicineRepository medicines;
    private final MedicineScheduleRepository schedules;
    private final CareRecipientRepository recipients;
    private final SchedulerService scheduler;

    public MedicineService(MedicineRepository medicines,
                           MedicineScheduleRepository schedules,
                           CareRecipientRepository recipients,
                           SchedulerService scheduler) {
        this.medicines = medicines;
        this.schedules = schedules;
        this.recipients = recipients;
        this.scheduler = scheduler;
    }

    /** After the save is committed, make this parent's upcoming doses at once (see SchedulerService.generateNow). */
    private void dosesAfterCommit(UUID recipientId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dosesNow(recipientId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dosesNow(recipientId);
            }
        });
    }

    /** Best effort: the medicine is already saved, so nothing here may turn the save into an error; the next tick catches up. */
    private void dosesNow(UUID recipientId) {
        try {
            scheduler.generateNow(recipientId);
        } catch (RuntimeException e) {
            log.warn("doses right after a medicine save failed ({}); the next scheduler tick makes them", e.getClass().getSimpleName());
        }
    }

    @Transactional(readOnly = true)
    public List<MedicineResponse> list(UUID userId, UUID recipientId) {
        requireRecipient(userId, recipientId);
        return medicines.findByRecipientIdOrderByCreatedAtAsc(recipientId).stream()
                .map(this::toResponse).toList();
    }

    @Transactional
    public MedicineResponse create(UUID userId, UUID recipientId, CreateMedicineRequest req) {
        CareRecipient recipient = requireRecipient(userId, recipientId);
        String name = requireClean(req.name(), 100, "name");
        String dosage = cleanOptional(req.dosage(), 60, "dosage");
        String instructions = validInstructions(req.instructions());
        String photo = validPhotoPath(req.photoPath(), recipientId);
        List<LocalTime> times = distinctTimes(req.times());
        String pillColour = validLook(req.pillColour(), PILL_COLOURS, "pill colour");
        String pillShape = validLook(req.pillShape(), PILL_SHAPES, "pill shape");
        Integer stock = validStock(req.stock());

        if (medicines.countByRecipientIdAndArchivedAtIsNull(recipientId) >= MAX_MEDICINES) {   // archived ones do not count
            throw new ResponseStatusException(BAD_REQUEST, "This parent already has the maximum number of medicines.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        LocalDate start = req.startDate() != null ? req.startDate() : localToday(recipient);

        Medicine m = new Medicine();
        m.setId(UUID.randomUUID());
        m.setRecipientId(recipientId);
        m.setName(name);
        m.setDosage(dosage);
        m.setInstructions(instructions);
        m.setPhotoUrl(photo);
        m.setStockCount(stock);   // null = not tracked: no "out of stock" for a family that skipped the count
        m.setRefillThreshold(req.refillThreshold());
        m.setPillColour(pillColour);
        m.setPillShape(pillShape);
        m.setActive(true);
        m.setCritical(false);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        try {
            medicines.saveAndFlush(m);
            for (LocalTime t : times) {
                insertSchedule(m.getId(), t, start, req.endDate(), now);
            }
            schedules.flush();
        } catch (DataAccessException e) {
            throw translate(e);
        }
        dosesAfterCommit(recipientId);
        return toResponse(m);
    }

    @Transactional
    public MedicineResponse update(UUID userId, UUID medicineId, UpdateMedicineRequest req) {
        Medicine m = requireMedicine(userId, medicineId);
        CareRecipient recipient = recipients.findById(m.getRecipientId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Medicine not found"));

        m.setName(requireClean(req.name(), 100, "name"));
        m.setDosage(cleanOptional(req.dosage(), 60, "dosage"));
        m.setInstructions(validInstructions(req.instructions()));
        m.setRefillThreshold(req.refillThreshold());
        if (req.photoPath() != null) { // null keeps the existing photo
            m.setPhotoUrl(validPhotoPath(req.photoPath(), m.getRecipientId()));
        }
        // Colour and shape: null (or left out, as an older app version does) keeps the saved value,
        // "" clears it, a listed value sets it. So an old cached app never wipes what a newer one saved.
        if (req.pillColour() != null) m.setPillColour(validLook(req.pillColour(), PILL_COLOURS, "pill colour"));
        if (req.pillShape() != null) m.setPillShape(validLook(req.pillShape(), PILL_SHAPES, "pill shape"));
        m.setUpdatedAt(OffsetDateTime.now());
        // Note: stock and active are NOT changed here (separate endpoints), matching update_medicine.

        List<LocalTime> desired = distinctTimes(req.times());
        reconcileSchedules(m.getId(), desired, localToday(recipient));
        dosesAfterCommit(m.getRecipientId());

        try {
            medicines.saveAndFlush(m);
        } catch (DataAccessException e) {
            throw translate(e);
        }
        return toResponse(m);
    }

    @Transactional
    public StockResponse adjustStock(UUID userId, UUID medicineId, Integer delta) {
        return adjustStock(userId, medicineId, new StockAdjustRequest(delta));
    }

    /**
     * Stock changes from the medicine screen. A delta never creates a count from "not tracked" (null stays null, so a taken dose
     * or a tap on minus can never make a medicine look "out of stock"); an exact count starts tracking; untrack stops it.
     */
    @Transactional
    public StockResponse adjustStock(UUID userId, UUID medicineId, StockAdjustRequest req) {
        if (req == null) throw new ResponseStatusException(BAD_REQUEST, "delta is required");
        boolean untrack = Boolean.TRUE.equals(req.untrack());
        int given = (req.delta() != null ? 1 : 0) + (req.count() != null ? 1 : 0) + (untrack ? 1 : 0);
        if (given == 0) throw new ResponseStatusException(BAD_REQUEST, "delta is required");
        if (given > 1) throw new ResponseStatusException(BAD_REQUEST, "send only one of delta, count, untrack");
        Medicine m = requireMedicine(userId, medicineId);
        Integer next;
        if (untrack) {
            next = null;
        } else if (req.count() != null) {
            next = validStock(req.count());
        } else {
            if (m.getStockCount() == null) {
                return new StockResponse(null); // not tracked -> no-op, matching adjust_stock
            }
            next = (int) Math.min(Math.max((long) m.getStockCount() + req.delta(), 0L), MAX_STOCK);
        }
        if (Objects.equals(next, m.getStockCount())) return new StockResponse(next);
        m.setStockCount(next);
        m.setUpdatedAt(OffsetDateTime.now());
        medicines.saveAndFlush(m); // DB trigger notify_low_stock fires on stock_count change (never for null)
        return new StockResponse(next);
    }

    /** Most tablets a stock can hold (the app's limit too). */
    static final int MAX_STOCK = 100_000;

    /** null (not tracked) or a whole number 0..100000. */
    private static Integer validStock(Integer stock) {
        if (stock == null) return null;
        if (stock < 0 || stock > MAX_STOCK) throw new ResponseStatusException(BAD_REQUEST, "stock must be between 0 and " + MAX_STOCK);
        return stock;
    }

    @Transactional
    public MedicineResponse setFlags(UUID userId, UUID medicineId, MedicineFlagsRequest req) {
        Medicine m = requireMedicine(userId, medicineId);
        if (Boolean.TRUE.equals(req.active()) && m.getArchivedAt() != null) {
            throw new ResponseStatusException(CONFLICT, "This medicine is archived. Restore it first.");
        }
        if (req.active() != null) m.setActive(req.active());
        if (req.critical() != null) m.setCritical(req.critical());
        m.setUpdatedAt(OffsetDateTime.now());
        medicines.saveAndFlush(m);
        if (req.active() != null) dosesAfterCommit(m.getRecipientId());   // resumed: today's upcoming doses again; paused: its waiting doses go
        return toResponse(m);
    }

    /**
     * "Stopped taking it": hidden from the active list and Today, no reminders, no new doses, no stock alerts, but the dose
     * history stays (doctor summary, activity). The database removes its waiting doses at once (trigger, migration 0056).
     * Archiving an archived medicine again changes nothing.
     */
    @Transactional
    public MedicineResponse archive(UUID userId, UUID medicineId) {
        Medicine m = requireMedicine(userId, medicineId);
        if (m.getArchivedAt() != null) return toResponse(m);
        OffsetDateTime now = OffsetDateTime.now();
        m.setArchivedAt(now);
        m.setActive(false);
        m.setUpdatedAt(now);
        try {
            medicines.saveAndFlush(m);
        } catch (DataAccessException e) {
            throw translate(e);
        }
        dosesAfterCommit(m.getRecipientId());
        return toResponse(m);
    }

    /** Back on the list and active again (reminders resume). Refused (400) when the plan's medicine limit is already reached. */
    @Transactional
    public MedicineResponse restore(UUID userId, UUID medicineId) {
        Medicine m = requireMedicine(userId, medicineId);
        if (m.getArchivedAt() == null) return toResponse(m);
        m.setArchivedAt(null);
        m.setActive(true);
        m.setUpdatedAt(OffsetDateTime.now());
        try {
            medicines.saveAndFlush(m);   // trigger medicines_restore_limit checks the plan limit
        } catch (DataAccessException e) {
            throw translate(e);
        }
        dosesAfterCommit(m.getRecipientId());
        return toResponse(m);
    }

    /** Only for a medicine added by mistake: the dose history goes too (cascade). Archive keeps it. */
    @Transactional
    public void delete(UUID userId, UUID medicineId) {
        Medicine m = requireMedicine(userId, medicineId);
        // TODO(port storage): remove the medicine-photos object at m.getPhotoUrl().
        medicines.delete(m); // cascade removes schedules + dose_events
    }

    // --- helpers ---

    private CareRecipient requireRecipient(UUID userId, UUID recipientId) {
        return recipients.findForUser(recipientId, userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Parent profile not found"));
    }

    private Medicine requireMedicine(UUID userId, UUID medicineId) {
        return medicines.findForUser(medicineId, userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Medicine not found"));
    }

    private void insertSchedule(UUID medicineId, LocalTime time, LocalDate start, LocalDate end,
                                OffsetDateTime now) {
        MedicineSchedule s = new MedicineSchedule();
        s.setId(UUID.randomUUID());
        s.setMedicineId(medicineId);
        s.setTimeOfDay(time);
        s.setStartDate(start);
        s.setEndDate(end);
        s.setCreatedAt(now);
        schedules.save(s);
    }

    /** Keep schedule history: drop times no longer wanted, add new ones (matching update_medicine). */
    private void reconcileSchedules(UUID medicineId, List<LocalTime> desired, LocalDate start) {
        List<MedicineSchedule> existing = schedules.findByMedicineId(medicineId);
        Set<LocalTime> desiredSet = new LinkedHashSet<>(desired);
        Set<LocalTime> existingTimes = new LinkedHashSet<>();
        OffsetDateTime now = OffsetDateTime.now();
        for (MedicineSchedule s : existing) {
            if (!desiredSet.contains(s.getTimeOfDay())) {
                schedules.delete(s);
            } else {
                existingTimes.add(s.getTimeOfDay());
            }
        }
        for (LocalTime t : desiredSet) {
            if (!existingTimes.contains(t)) {
                insertSchedule(medicineId, t, start, null, now);
            }
        }
    }

    private static LocalDate localToday(CareRecipient recipient) {
        return LocalDate.now(ZoneId.of(recipient.getTimezone()));
    }

    private static List<LocalTime> distinctTimes(List<LocalTime> times) {
        if (times == null || times.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "At least one time is required");
        }
        List<LocalTime> distinct = new ArrayList<>(new LinkedHashSet<>(times));
        if (distinct.size() > MAX_TIMES) {
            throw new ResponseStatusException(BAD_REQUEST, "At most " + MAX_TIMES + " times per medicine");
        }
        return distinct;
    }

    private static String requireClean(String s, int max, String field) {
        String v = s == null ? "" : s.trim();
        if (v.isEmpty() || v.length() > max || hasControlChars(v)) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid " + field);
        }
        return v;
    }

    private static String cleanOptional(String s, int max, String field) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        if (v.length() > max || hasControlChars(v)) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid " + field);
        }
        return v;
    }

    private static String validInstructions(String s) {
        if (s == null || s.isBlank()) return null;
        if (!INSTRUCTIONS.contains(s)) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid instructions");
        }
        return s;
    }

    /** null or "" = not set; otherwise it must be one of the fixed values (anything else is a 400). */
    private static String validLook(String value, Set<String> allowed, String field) {
        if (value == null || value.isEmpty()) return null;
        if (!allowed.contains(value)) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid " + field);
        }
        return value;
    }

    private static String validPhotoPath(String path, UUID recipientId) {
        if (path == null || path.isBlank()) return null;
        if (path.length() > MAX_PHOTO_LEN
                || path.contains("..") || path.contains("//")
                || !path.startsWith(recipientId + "/")) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid photo path");
        }
        return path;
    }

    private static boolean hasControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) return true;
        }
        return false;
    }

    private RuntimeException translate(DataAccessException e) {
        String msg = rootMessage(e).toLowerCase();
        if (msg.contains("too_many_times")) {
            return new ResponseStatusException(BAD_REQUEST, "At most " + MAX_TIMES + " times per medicine");
        }
        if (msg.contains("too_many_medicines")) {
            return new ResponseStatusException(BAD_REQUEST, "This parent already has the maximum number of medicines.");
        }
        if (msg.contains("bad_name") || msg.contains("medicines_name_clean")) {
            return new ResponseStatusException(BAD_REQUEST, "Invalid name");
        }
        if (msg.contains("bad_dosage") || msg.contains("medicines_dosage_clean")) {
            return new ResponseStatusException(BAD_REQUEST, "Invalid dosage");
        }
        if (msg.contains("medicines_pill_colour") || msg.contains("medicines_pill_shape")) {
            return new ResponseStatusException(BAD_REQUEST, "Invalid pill look");
        }
        if (msg.contains("bad_photo")) {
            return new ResponseStatusException(BAD_REQUEST, "Invalid photo path");
        }
        return e;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? "" : t.getMessage();
    }

    private MedicineResponse toResponse(Medicine m) {
        return new MedicineResponse(
                m.getId().toString(),
                m.getName(),
                m.getDosage() == null ? "" : m.getDosage(),
                schedules.findTimesByMedicineId(m.getId()),
                m.getStockCount(),
                m.getRefillThreshold(),
                m.getInstructions(),
                m.isActive(),
                m.getPhotoUrl(),
                m.isCritical(),
                m.getCreatedAt(),
                m.getPillColour(),
                m.getPillShape(),
                m.getArchivedAt());
    }
}
