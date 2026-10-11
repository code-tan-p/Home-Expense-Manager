package com.homeexpense.repository;

import java.util.List;

import com.homeexpense.model.earnings.EarningRecordTreatment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EarningRecordTreatmentRepository extends JpaRepository<EarningRecordTreatment, Long> {

    @Query("select t from EarningRecordTreatment t where t.run.id = :runId order by t.workRecord.workDate, t.workRecord.id")
    List<EarningRecordTreatment> findByRunId(Long runId);
}
