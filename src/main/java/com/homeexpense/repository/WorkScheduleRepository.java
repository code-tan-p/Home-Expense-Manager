package com.homeexpense.repository;

import java.util.List;

import com.homeexpense.model.WorkSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkScheduleRepository extends JpaRepository<WorkSchedule, Long> {

    List<WorkSchedule> findByWorkerIdOrderByEffectiveFromAsc(Long workerId);
}
