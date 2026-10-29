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

/** Money actually handed to a worker. Never changed or deleted (D7). */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private Worker worker;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false)
    private PaymentMethod method;

    @Column(name = "reference")
    private String reference;

    @Column(name = "note")
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_mode", nullable = false)
    private AllocationMode allocationMode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaces_payment_id")
    private Payment replaces;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected Payment() {
        // Required by JPA
    }

    @SuppressWarnings("java:S107")
    public Payment(Worker worker, LocalDate paidOn, long amountPaise, PaymentMethod method, String reference, String note, AllocationMode allocationMode, Payment replaces, Instant recordedAt) {
        this.worker = worker;
        this.paidOn = paidOn;
        this.amountPaise = amountPaise;
        this.method = method;
        this.reference = reference;
        this.note = note;
        this.allocationMode = allocationMode;
        this.replaces = replaces;
        this.recordedAt = recordedAt;
    }

    public Long getId() {
        return id;
    }

    public Worker getWorker() {
        return worker;
    }

    public LocalDate getPaidOn() {
        return paidOn;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public String getReference() {
        return reference;
    }

    public String getNote() {
        return note;
    }

    public AllocationMode getAllocationMode() {
        return allocationMode;
    }

    public Payment getReplaces() {
        return replaces;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
