package org.smartgurukul.chhaon.medicine;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public interface MedicineScheduleRepository extends JpaRepository<MedicineSchedule, UUID> {

    List<MedicineSchedule> findByMedicineId(UUID medicineId);

    @Query("select s.timeOfDay from MedicineSchedule s where s.medicineId = :medicineId order by s.timeOfDay")
    List<LocalTime> findTimesByMedicineId(@Param("medicineId") UUID medicineId);
}
