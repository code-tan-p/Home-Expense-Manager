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


/** Reverses one advance recovery (after reopening); the recovery itself is kept. */
@Entity
@Table(name = "recovery_reversal")
public class RecoveryReversal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recovery_id", nullable = false)
    private AdvanceRecovery recovery;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "reversed_at", nullable = false)
    private Instant reversedAt;

    protected RecoveryReversal() {
        // Required by JPA
    }

    @SuppressWarnings("java:S107")
    public RecoveryReversal(AdvanceRecovery recovery, String reason, Instant reversedAt) {
        this.recovery = recovery;
        this.reason = reason;
        this.reversedAt = reversedAt;
    }

    public Long getId() {
        return id;
    }

    public AdvanceRecovery getRecovery() {
        return recovery;
    }

    public String getReason() {
        return reason;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }
}
