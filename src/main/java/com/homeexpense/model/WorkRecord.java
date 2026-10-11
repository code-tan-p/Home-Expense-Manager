package com.homeexpense.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

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
 * A confirmed record of one activity on one date: either the answer to an expected
 * (scheduled) activity, or unscheduled work. Recorded facts never change; a correction voids
 * this record and inserts a replacement that points back to it. Only the void fields and a
 * pending deduction decision may change, which the database also enforces.
 */
@Entity
@DynamicUpdate
@Table(name = "work_record")
public class WorkRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private Worker worker;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_entry_id")
    private ScheduleEntry scheduleEntry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id")
    private Shift shift;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "absence_reason")
    private AbsenceReason absenceReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "deduction_decision")
    private DeductionDecision deductionDecision;

    @Column(name = "expected_minutes")
    private Integer expectedMinutes;

    @Column(name = "expected_quantity_milli")
    private Long expectedQuantityMilli;

    @Column(name = "actual_start")
    private LocalTime actualStart;

    @Column(name = "actual_end")
    private LocalTime actualEnd;

    @Column(name = "actual_minutes")
    private Integer actualMinutes;

    @Column(name = "actual_quantity_milli")
    private Long actualQuantityMilli;

    private String note;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaces_record_id")
    private WorkRecord replaces;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "void_reason")
    private String voidReason;

    protected WorkRecord() {
        // Required by JPA
    }

    @SuppressWarnings("java:S107")
    public WorkRecord(Worker worker, LocalDate workDate, ScheduleEntry scheduleEntry, Task task, Shift shift,
                      WorkOutcome outcome, AbsenceReason absenceReason, Integer expectedMinutes,
                      Long expectedQuantityMilli, LocalTime actualStart, LocalTime actualEnd, Integer actualMinutes,
                      Long actualQuantityMilli, String note, Instant recordedAt, WorkRecord replaces) {
        this.worker = worker;
        this.workDate = workDate;
        this.scheduleEntry = scheduleEntry;
        this.task = task;
        this.shift = shift;
        this.outcome = outcome;
        this.absenceReason = absenceReason;
        this.deductionDecision = absenceReason == AbsenceReason.UNAUTHORIZED_ABSENCE ? DeductionDecision.PENDING : null;
        this.expectedMinutes = expectedMinutes;
        this.expectedQuantityMilli = expectedQuantityMilli;
        this.actualStart = actualStart;
        this.actualEnd = actualEnd;
        this.actualMinutes = actualMinutes;
        this.actualQuantityMilli = actualQuantityMilli;
        this.note = note;
        this.recordedAt = recordedAt;
        this.replaces = replaces;
    }

    public boolean isCurrent() {
        return voidedAt == null;
    }

    public boolean isScheduled() {
        return scheduleEntry != null;
    }

    public void voidRecord(Instant when, String reason) {
        if (voidedAt != null) {
            throw new IllegalStateException("Work record " + id + " is already void");
        }
        this.voidedAt = when;
        this.voidReason = reason;
    }

    public void decideDeduction(DeductionDecision decision) {
        this.deductionDecision = decision;
    }

    public Long getId() {
        return id;
    }

    public Worker getWorker() {
        return worker;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public ScheduleEntry getScheduleEntry() {
        return scheduleEntry;
    }

    public Task getTask() {
        return task;
    }

    public Shift getShift() {
        return shift;
    }

    public WorkOutcome getOutcome() {
        return outcome;
    }

    public AbsenceReason getAbsenceReason() {
        return absenceReason;
    }

    public DeductionDecision getDeductionDecision() {
        return deductionDecision;
    }

    public Integer getExpectedMinutes() {
        return expectedMinutes;
    }

    public Long getExpectedQuantityMilli() {
        return expectedQuantityMilli;
    }

    public LocalTime getActualStart() {
        return actualStart;
    }

    public LocalTime getActualEnd() {
        return actualEnd;
    }

    public Integer getActualMinutes() {
        return actualMinutes;
    }

    public Long getActualQuantityMilli() {
        return actualQuantityMilli;
    }

    public String getNote() {
        return note;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public WorkRecord getReplaces() {
        return replaces;
    }

    public Instant getVoidedAt() {
        return voidedAt;
    }

    public String getVoidReason() {
        return voidReason;
    }
}
