package com.homeexpense.repository;

import java.util.List;

import com.homeexpense.model.earnings.EarningRunNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EarningRunNoteRepository extends JpaRepository<EarningRunNote, Long> {

    @Query("select n from EarningRunNote n where n.run.id = :runId order by n.ordinal")
    List<EarningRunNote> findByRunId(Long runId);
}
