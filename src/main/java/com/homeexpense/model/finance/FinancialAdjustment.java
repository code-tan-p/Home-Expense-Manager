package com.homeexpense.model.finance;

import java.time.Instant;
import java.time.LocalDate;

import com.homeexpense.model.Worker;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import org.hibernate.type.NumericBooleanConverter;

/** An immutable, reasoned financial correction. Carries and corrections move an amount out of a source settlement into a target month. */
@Entity
@Table(name = "financial_adjustment")
public class FinancialAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private Worker worker;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private AdjustmentKind kind;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "adjustment_date", nullable = false)
    private LocalDate adjustmentDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_settlement_id")
    private Settlement sourceSettlement;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_version_id")
    private SettlementVersion sourceVersion;

    @Column(name = "target_period_start")
    private LocalDate targetPeriodStart;

    @Convert(converter = NumericBooleanConverter.class)
    @Column(name = "money_owed_by_worker")
    private Boolean moneyOwedByWorker;

    @Convert(converter = NumericBooleanConverter.class)
    @Column(name = "affects_source_cover", nullable = false)
    private boolean affectsSourceCover;

    @Convert(converter = NumericBooleanConverter.class)
    @Column(name = "affects_source_shortfall", nullable = false)
    private boolean affectsSourceShortfall;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reverses_id")
    private FinancialAdjustment reverses;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FinancialAdjustment() {
        // Required by JPA
    }

    @SuppressWarnings("java:S107")
    public FinancialAdjustment(Worker worker, AdjustmentKind kind, long amountPaise, String reason, LocalDate adjustmentDate, Settlement sourceSettlement, SettlementVersion sourceVersion, LocalDate targetPeriodStart, Boolean moneyOwedByWorker, boolean affectsSourceCover, boolean affectsSourceShortfall, FinancialAdjustment reverses, Instant createdAt) {
        this.worker = worker;
        this.kind = kind;
        this.amountPaise = amountPaise;
        this.reason = reason;
        this.adjustmentDate = adjustmentDate;
        this.sourceSettlement = sourceSettlement;
        this.sourceVersion = sourceVersion;
        this.targetPeriodStart = targetPeriodStart;
        this.moneyOwedByWorker = moneyOwedByWorker;
        this.affectsSourceCover = affectsSourceCover;
        this.affectsSourceShortfall = affectsSourceShortfall;
        this.reverses = reverses;
        this.createdAt = createdAt;
    }

    /** The original adjustment of a reversal chain (itself, unless this is a reversal). */
    public FinancialAdjustment root() {
        return reverses == null ? this : reverses;
    }

    public Long getId() {
        return id;
    }

    public Worker getWorker() {
        return worker;
    }

    public AdjustmentKind getKind() {
        return kind;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getReason() {
        return reason;
    }

    public LocalDate getAdjustmentDate() {
        return adjustmentDate;
    }

    public Settlement getSourceSettlement() {
        return sourceSettlement;
    }

    public SettlementVersion getSourceVersion() {
        return sourceVersion;
    }

    public LocalDate getTargetPeriodStart() {
        return targetPeriodStart;
    }

    public Boolean getMoneyOwedByWorker() {
        return moneyOwedByWorker;
    }

    public boolean isAffectsSourceCover() {
        return affectsSourceCover;
    }

    public boolean isAffectsSourceShortfall() {
        return affectsSourceShortfall;
    }

    public FinancialAdjustment getReverses() {
        return reverses;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
