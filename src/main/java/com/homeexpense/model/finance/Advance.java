package com.homeexpense.model.finance;

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

/** Money the worker owes back, recovered from later earnings. */
@Entity
@Table(name = "advance")
public class Advance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private Worker worker;

    @Column(name = "given_on", nullable = false)
    private LocalDate givenOn;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private AdvanceSource source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_settlement_id")
    private Settlement sourceSettlement;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_adjustment_id")
    private FinancialAdjustment sourceAdjustment;

    @Column(name = "note")
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Advance() {
        // Required by JPA
    }

    @SuppressWarnings("java:S107")
    public Advance(Worker worker, LocalDate givenOn, long amountPaise, AdvanceSource source, Settlement sourceSettlement, FinancialAdjustment sourceAdjustment, String note, Instant createdAt) {
        this.worker = worker;
        this.givenOn = givenOn;
        this.amountPaise = amountPaise;
        this.source = source;
        this.sourceSettlement = sourceSettlement;
        this.sourceAdjustment = sourceAdjustment;
        this.note = note;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Worker getWorker() {
        return worker;
    }

    public LocalDate getGivenOn() {
        return givenOn;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public AdvanceSource getSource() {
        return source;
    }

    public Settlement getSourceSettlement() {
        return sourceSettlement;
    }

    public FinancialAdjustment getSourceAdjustment() {
        return sourceAdjustment;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
