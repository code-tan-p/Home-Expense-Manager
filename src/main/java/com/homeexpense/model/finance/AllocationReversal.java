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


/** Reverses one allocation; the allocation itself is kept. */
@Entity
@Table(name = "allocation_reversal")
public class AllocationReversal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "allocation_id", nullable = false)
    private PaymentAllocation allocation;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "reversed_at", nullable = false)
    private Instant reversedAt;

    protected AllocationReversal() {
        // Required by JPA
    }

    @SuppressWarnings("java:S107")
    public AllocationReversal(PaymentAllocation allocation, String reason, Instant reversedAt) {
        this.allocation = allocation;
        this.reason = reason;
        this.reversedAt = reversedAt;
    }

    public Long getId() {
        return id;
    }

    public PaymentAllocation getAllocation() {
        return allocation;
    }

    public String getReason() {
        return reason;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }
}
