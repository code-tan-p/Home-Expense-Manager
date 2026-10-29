package com.homeexpense.model.earnings;

import com.homeexpense.model.WorkRecord;
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

/** How one current work record was treated in a calculation; exactly one per record per run. */
@Entity
@Table(name = "earning_record_treatment")
public class EarningRecordTreatment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private EarningRun run;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_record_id", nullable = false)
    private WorkRecord workRecord;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TreatmentType treatment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_id")
    private EarningLine line;

    @Column(name = "review_key")
    private String reviewKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    private ReviewStatus reviewStatus;

    @Column(name = "suggested_value")
    private String suggestedValue;

    @Column(name = "max_value")
    private String maxValue;

    @Column(name = "applied_value")
    private String appliedValue;

    @Column(nullable = false)
    private String detail;

    protected EarningRecordTreatment() {
        // Required by JPA
    }

    @SuppressWarnings("java:S107")
    public EarningRecordTreatment(EarningRun run, WorkRecord workRecord, TreatmentType treatment, EarningLine line,
                                  String reviewKey, ReviewStatus reviewStatus, String suggestedValue, String maxValue,
                                  String appliedValue, String detail) {
        this.run = run;
        this.workRecord = workRecord;
        this.treatment = treatment;
        this.line = line;
        this.reviewKey = reviewKey;
        this.reviewStatus = reviewStatus;
        this.suggestedValue = suggestedValue;
        this.maxValue = maxValue;
        this.appliedValue = appliedValue;
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public EarningRun getRun() {
        return run;
    }

    public WorkRecord getWorkRecord() {
        return workRecord;
    }

    public TreatmentType getTreatment() {
        return treatment;
    }

    public EarningLine getLine() {
        return line;
    }

    public String getReviewKey() {
        return reviewKey;
    }

    public ReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public String getSuggestedValue() {
        return suggestedValue;
    }

    public String getMaxValue() {
        return maxValue;
    }

    public String getAppliedValue() {
        return appliedValue;
    }

    public String getDetail() {
        return detail;
    }
}
