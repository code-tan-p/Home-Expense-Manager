package com.homeexpense.repository;

import java.util.List;
import java.util.Optional;

import com.homeexpense.model.earnings.EarningReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EarningReviewRepository extends JpaRepository<EarningReview, Long> {

    @Query("select r from EarningReview r where r.worker.id = :workerId and r.supersededAt is null")
    List<EarningReview> findCurrentForWorker(Long workerId);

    @Query("select r from EarningReview r where r.worker.id = :workerId and r.reviewKey = :reviewKey and r.supersededAt is null")
    Optional<EarningReview> findCurrent(Long workerId, String reviewKey);
}
