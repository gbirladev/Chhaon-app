package org.smartgurukul.chhaon.medicine;

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
import java.util.List;
import java.util.UUID;

/**
 * One scheduled time for a medicine. Maps public.medicine_schedules.
 * {@code daysOfWeek} is a Postgres smallint[] of 0..6 (0 = Sunday); default all seven days.
 */
@Entity
@Table(name = "medicine_schedules")
@Getter
@Setter
public class MedicineSchedule {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "medicine_id", nullable = false, updatable = false)
    private UUID medicineId;

    @Column(name = "time_of_day", nullable = false)
    private LocalTime timeOfDay;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "days_of_week", nullable = false, columnDefinition = "smallint[]")
    private List<Short> daysOfWeek = List.of((short) 0, (short) 1, (short) 2, (short) 3,
            (short) 4, (short) 5, (short) 6);

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
