package com.homeexpense.model.finance;

import java.time.Instant;
import java.time.LocalDate;

import com.homeexpense.model.Worker;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** The stable identity of one worker-month settlement (D1). Allocations and recoveries point here. */
@Entity
@Table(name = "settlement")
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private Worker worker;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Settlement() {
        // Required by JPA
    }

    @SuppressWarnings("java:S107")
    public Settlement(Worker worker, LocalDate periodStart, Instant createdAt) {
        this.worker = worker;
        this.periodStart = periodStart;
        this.createdAt = createdAt;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
