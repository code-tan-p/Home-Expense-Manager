package com.homeexpense.model.finance;

import java.time.Instant;

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

/** Part of a payment applied to one settlement or one advance. Undone only by an AllocationReversal. */
@Entity
@Table(name = "payment_allocation")
public class PaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_id")
    private Settlement settlement;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "advance_id")
    private Advance advance;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private AllocationKind kind;

    @Column(name = "allocated_at", nullable = false)
    private Instant allocatedAt;

    protected PaymentAllocation() {
        // Required by JPA
    }

    @SuppressWarnings("java:S107")
    public PaymentAllocation(Payment payment, Settlement settlement, Advance advance, long amountPaise, AllocationKind kind, Instant allocatedAt) {
        this.payment = payment;
        this.settlement = settlement;
        this.advance = advance;
        this.amountPaise = amountPaise;
        this.kind = kind;
        this.allocatedAt = allocatedAt;
    }

    public Long getId() {
        return id;
    }

    public Payment getPayment() {
        return payment;
    }

    public Settlement getSettlement() {
        return settlement;
    }

    public Advance getAdvance() {
        return advance;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public AllocationKind getKind() {
        return kind;
    }

    public Instant getAllocatedAt() {
        return allocatedAt;
    }
}
