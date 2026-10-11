package com.homeexpense.model.finance;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;


/** Part of an advance recovered in a settlement. Created only when the settlement is settled (Q8). */
@Entity
@Table(name = "advance_recovery")
public class AdvanceRecovery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "advance_id", nullable = false)
    private Advance advance;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "settlement_id", nullable = false)
    private Settlement settlement;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "settlement_version_id", nullable = false)
    private SettlementVersion version;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "recovered_at", nullable = false)
    private Instant recoveredAt;

    protected AdvanceRecovery() {
        // Required by JPA
    }

    @SuppressWarnings("java:S107")
    public AdvanceRecovery(Advance advance, Settlement settlement, SettlementVersion version, long amountPaise, Instant recoveredAt) {
        this.advance = advance;
        this.settlement = settlement;
        this.version = version;
        this.amountPaise = amountPaise;
        this.recoveredAt = recoveredAt;
    }

    public Long getId() {
        return id;
    }

    public Advance getAdvance() {
        return advance;
    }

    public Settlement getSettlement() {
        return settlement;
    }

    public SettlementVersion getVersion() {
        return version;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public Instant getRecoveredAt() {
        return recoveredAt;
    }
}
