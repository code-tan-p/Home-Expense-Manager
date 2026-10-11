package com.homeexpense.model.finance;

import java.time.Instant;

import com.homeexpense.model.earnings.EarningRun;
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
import org.hibernate.annotations.DynamicUpdate;

/**
 * One snapshot of a settlement, tied to one finalized earnings run. While OPEN its figures
 * are recalculated; once SETTLED it is frozen and can only be superseded by reopening.
 */
@Entity
@DynamicUpdate
@Table(name = "settlement_version")
public class SettlementVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "settlement_id", nullable = false)
    private Settlement settlement;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "earning_run_id", nullable = false)
    private EarningRun earningRun;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SettlementStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "rounding_mode", nullable = false)
    private SettlementRounding roundingMode;

    @Column(name = "earnings_paise", nullable = false)
    private long earningsPaise;

    @Column(name = "negative_adjustments_paise", nullable = false)
    private long negativeAdjustmentsPaise;

    @Column(name = "shortfall_paise", nullable = false)
    private long shortfallPaise;

    @Column(name = "positive_adjustments_paise", nullable = false)
    private long positiveAdjustmentsPaise;

    @Column(name = "recovery_paise", nullable = false)
    private long recoveryPaise;

    @Column(name = "rounding_paise", nullable = false)
    private long roundingPaise;

    @Column(name = "net_payable_paise", nullable = false)
    private long netPayablePaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "shortfall_treatment", nullable = false)
    private ShortfallTreatment shortfallTreatment;

    @Enumerated(EnumType.STRING)
    @Column(name = "settle_treatment", nullable = false)
    private SettleTreatment settleTreatment;

    @Column(name = "outstanding_at_settle_paise")
    private Long outstandingAtSettlePaise;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "superseded_reason")
    private String supersededReason;

    protected SettlementVersion() {
        // Required by JPA
    }

    public SettlementVersion(Settlement settlement, int versionNo, EarningRun earningRun, Instant calculatedAt) {
        this.settlement = settlement;
        this.versionNo = versionNo;
        this.earningRun = earningRun;
        this.status = SettlementStatus.OPEN;
        this.roundingMode = SettlementRounding.EXACT;
        this.shortfallTreatment = ShortfallTreatment.NONE;
        this.settleTreatment = SettleTreatment.NONE;
        this.calculatedAt = calculatedAt;
    }

    /** Records freshly calculated figures on an OPEN version. */
    @SuppressWarnings("java:S107")
    public void recordFigures(long earnings, long negativeAdjustments, long shortfall, long positiveAdjustments, long recovery,
                              long rounding, long netPayable, Instant when) {
        this.earningsPaise = earnings;
        this.negativeAdjustmentsPaise = negativeAdjustments;
        this.shortfallPaise = shortfall;
        this.positiveAdjustmentsPaise = positiveAdjustments;
        this.recoveryPaise = recovery;
        this.roundingPaise = rounding;
        this.netPayablePaise = netPayable;
        this.calculatedAt = when;
    }

    public void useRounding(SettlementRounding mode) {
        this.roundingMode = mode;
    }

    public void leaveShortfallPending() {
        this.shortfallTreatment = ShortfallTreatment.PENDING;
    }

    public void settle(SettleTreatment treatment, long outstandingPaise, Instant when) {
        this.status = SettlementStatus.SETTLED;
        this.settleTreatment = treatment;
        this.outstandingAtSettlePaise = outstandingPaise;
        this.settledAt = when;
    }

    public void supersede(String reason, Instant when) {
        this.status = SettlementStatus.SUPERSEDED;
        this.supersededReason = reason;
        this.supersededAt = when;
    }

    public boolean isOpen() {
        return status == SettlementStatus.OPEN;
    }

    public Long getId() {
        return id;
    }

    public Settlement getSettlement() {
        return settlement;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public EarningRun getEarningRun() {
        return earningRun;
    }

    public SettlementStatus getStatus() {
        return status;
    }

    public SettlementRounding getRoundingMode() {
        return roundingMode;
    }

    public long getEarningsPaise() {
        return earningsPaise;
    }

    public long getNegativeAdjustmentsPaise() {
        return negativeAdjustmentsPaise;
    }

    public long getShortfallPaise() {
        return shortfallPaise;
    }

    public long getPositiveAdjustmentsPaise() {
        return positiveAdjustmentsPaise;
    }

    public long getRecoveryPaise() {
        return recoveryPaise;
    }

    public long getRoundingPaise() {
        return roundingPaise;
    }

    public long getNetPayablePaise() {
        return netPayablePaise;
    }

    public ShortfallTreatment getShortfallTreatment() {
        return shortfallTreatment;
    }

    public SettleTreatment getSettleTreatment() {
        return settleTreatment;
    }

    public Long getOutstandingAtSettlePaise() {
        return outstandingAtSettlePaise;
    }

    public Instant getCalculatedAt() {
        return calculatedAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }

    public String getSupersededReason() {
        return supersededReason;
    }
}
