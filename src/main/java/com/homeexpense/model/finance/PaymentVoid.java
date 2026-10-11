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


/** Marks a mistaken payment as void and links its replacement; the payment itself is kept. */
@Entity
@Table(name = "payment_void")
public class PaymentVoid {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "voided_at", nullable = false)
    private Instant voidedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replacement_payment_id")
    private Payment replacement;

    protected PaymentVoid() {
        // Required by JPA
    }

    @SuppressWarnings("java:S107")
    public PaymentVoid(Payment payment, String reason, Instant voidedAt, Payment replacement) {
        this.payment = payment;
        this.reason = reason;
        this.voidedAt = voidedAt;
        this.replacement = replacement;
    }

    public Long getId() {
        return id;
    }

    public Payment getPayment() {
        return payment;
    }

    public String getReason() {
        return reason;
    }

    public Instant getVoidedAt() {
        return voidedAt;
    }

    public Payment getReplacement() {
        return replacement;
    }
}
