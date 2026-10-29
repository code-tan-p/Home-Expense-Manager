package com.homeexpense.model;

import java.time.LocalDate;
import java.util.Objects;

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

/**
 * An effective-dated pay rate in integer paise. A rate with no task and no shift is the
 * worker's standard rate for its type; a task- or shift-specific rate takes precedence.
 */
@Entity
@Table(name = "pay_rate")
public class PayRate implements EffectiveDated {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private Worker worker;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_type", nullable = false)
    private RateType rateType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id")
    private Shift shift;

    @Column(name = "unit_label")
    private String unitLabel;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    private String note;

    protected PayRate() {
        // Required by JPA
    }

    public PayRate(Worker worker, RateType rateType, Task task, Shift shift, String unitLabel,
                   long amountPaise, LocalDate effectiveFrom, LocalDate effectiveTo, String note) {
        this.worker = worker;
        this.rateType = rateType;
        this.task = task;
        this.shift = shift;
        this.unitLabel = unitLabel;
        this.amountPaise = amountPaise;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.note = note;
    }

    /** True if this rate has the same selection key: rate type, task and shift. */
    public boolean hasSameKey(RateType type, Long taskId, Long shiftId) {
        return rateType == type && Objects.equals(taskId(), taskId) && Objects.equals(shiftId(), shiftId);
    }

    public boolean isStandard() {
        return task == null && shift == null;
    }

    public Long taskId() {
        return task == null ? null : task.getId();
    }

    public Long shiftId() {
        return shift == null ? null : shift.getId();
    }

    /** Corrects a mistaken amount in place. Callers must check that no earnings use this rate. */
    public void correctAmount(long newAmountPaise) {
        this.amountPaise = newAmountPaise;
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

    public RateType getRateType() {
        return rateType;
    }

    public Task getTask() {
        return task;
    }

    public Shift getShift() {
        return shift;
    }

    public String getUnitLabel() {
        return unitLabel;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    @Override
    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    @Override
    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public String getNote() {
        return note;
    }
}
