package com.homeexpense.model.earnings;

import java.time.Instant;
import java.time.LocalDate;

import com.homeexpense.model.Worker;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** The earnings calculation for one worker and one calendar month. */
@Entity
@Table(name = "earning_run")
public class EarningRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private Worker worker;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EarningRunStatus status;

    @Column(name = "engine_version", nullable = false)
    private int engineVersion;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "total_paise", nullable = false)
    private long totalPaise;

    @Column(name = "needs_review_count", nullable = false)
    private int needsReviewCount;

    @Column(name = "blocker_count", nullable = false)
    private int blockerCount;

    protected EarningRun() {
        // Required by JPA
    }

    public EarningRun(Worker worker, LocalDate periodStart, LocalDate periodEnd, int engineVersion, Instant calculatedAt) {
        this.worker = worker;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.status = EarningRunStatus.DRAFT;
        this.engineVersion = engineVersion;
        this.calculatedAt = calculatedAt;
    }

    public void recordCalculation(Instant when, int version, long total, int needsReview, int blockers) {
        this.calculatedAt = when;
        this.engineVersion = version;
        this.totalPaise = total;
        this.needsReviewCount = needsReview;
        this.blockerCount = blockers;
    }

    public void finalizeRun(Instant when) {
        this.status = EarningRunStatus.FINALIZED;
        this.finalizedAt = when;
    }

    public boolean isDraft() {
        return status == EarningRunStatus.DRAFT;
    }

    public Long getId() {
        return id;
    }

    public Worker getWorker() {
        return worker;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public EarningRunStatus getStatus() {
        return status;
    }

    public int getEngineVersion() {
        return engineVersion;
    }

    public Instant getCalculatedAt() {
        return calculatedAt;
    }

    public Instant getFinalizedAt() {
        return finalizedAt;
    }

    public long getTotalPaise() {
        return totalPaise;
    }

    public int getNeedsReviewCount() {
        return needsReviewCount;
    }

    public int getBlockerCount() {
        return blockerCount;
    }
}
