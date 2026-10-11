package com.homeexpense.model;

import java.time.LocalDate;

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

/** The worker's single primary payment model over an effective-dated period. */
@Entity
@Table(name = "pay_arrangement")
public class PayArrangement implements EffectiveDated {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private Worker worker;

    @Enumerated(EnumType.STRING)
    @Column(name = "primary_model", nullable = false)
    private PaymentModel primaryModel;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    protected PayArrangement() {
        // Required by JPA
    }

    public PayArrangement(Worker worker, PaymentModel primaryModel, LocalDate effectiveFrom, LocalDate effectiveTo) {
        this.worker = worker;
        this.primaryModel = primaryModel;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
    }

    @Override
    public void endOn(LocalDate lastDay) {
        this.effectiveTo = lastDay;
    }

    public Long getId() {
        return id;
    }

    public Worker getWorker() {
        return worker;
    }

    public PaymentModel getPrimaryModel() {
        return primaryModel;
    }

    @Override
    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    @Override
    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }
}
