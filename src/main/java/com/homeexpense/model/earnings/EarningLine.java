package com.homeexpense.model.earnings;

import java.time.LocalDate;

import com.homeexpense.model.PayRate;
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

/**
 * One explained amount in an earnings calculation. {@code exactPaise} is the unrounded
 * fraction; {@code amountPaise} is that value rounded HALF_UP once (or the reviewed amount),
 * and is null while the line needs review.
 */
@Entity
@Table(name = "earning_line")
public class EarningLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private EarningRun run;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_type", nullable = false)
    private EarningLineType lineType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EarningBasis basis;

    @Column(name = "work_date")
    private LocalDate workDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_record_id")
    private WorkRecord workRecord;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pay_rate_id")
    private PayRate payRate;

    @Column(name = "rate_amount_paise")
    private Long rateAmountPaise;

    @Column(name = "unit_label")
    private String unitLabel;

    @Column(name = "quantity_milli")
    private Long quantityMilli;

    private Integer minutes;

    @Column(name = "paid_day_units")
    private String paidDayUnits;

    @Column(name = "scheduled_day_units")
    private Integer scheduledDayUnits;

    @Column(name = "exact_paise")
    private String exactPaise;

    @Column(name = "suggested_amount_paise")
    private Long suggestedAmountPaise;

    @Column(name = "amount_paise")
    private Long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    private ReviewStatus reviewStatus;

    @Column(name = "review_key")
    private String reviewKey;

    @Column(nullable = false)
    private String explanation;

    protected EarningLine() {
        // Required by JPA
    }

    @SuppressWarnings("java:S107")
    public EarningLine(EarningRun run, int lineNo, EarningLineType lineType, EarningBasis basis, LocalDate workDate,
                       WorkRecord workRecord, PayRate payRate, String unitLabel, Long quantityMilli, Integer minutes,
                       String paidDayUnits, Integer scheduledDayUnits, String exactPaise, Long suggestedAmountPaise,
                       Long amountPaise, ReviewStatus reviewStatus, String reviewKey, String explanation) {
        this.run = run;
        this.lineNo = lineNo;
        this.lineType = lineType;
        this.basis = basis;
        this.workDate = workDate;
        this.workRecord = workRecord;
        this.payRate = payRate;
        this.rateAmountPaise = payRate == null ? null : payRate.getAmountPaise();
        this.unitLabel = unitLabel;
        this.quantityMilli = quantityMilli;
        this.minutes = minutes;
        this.paidDayUnits = paidDayUnits;
        this.scheduledDayUnits = scheduledDayUnits;
        this.exactPaise = exactPaise;
        this.suggestedAmountPaise = suggestedAmountPaise;
        this.amountPaise = amountPaise;
        this.reviewStatus = reviewStatus;
        this.reviewKey = reviewKey;
        this.explanation = explanation;
    }

    public Long getId() {
        return id;
    }

    public EarningRun getRun() {
        return run;
    }

    public int getLineNo() {
        return lineNo;
    }

    public EarningLineType getLineType() {
        return lineType;
    }

    public EarningBasis getBasis() {
        return basis;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public WorkRecord getWorkRecord() {
        return workRecord;
    }

    public PayRate getPayRate() {
        return payRate;
    }

    public Long getRateAmountPaise() {
        return rateAmountPaise;
    }

    public String getUnitLabel() {
        return unitLabel;
    }

    public Long getQuantityMilli() {
        return quantityMilli;
    }

    public Integer getMinutes() {
        return minutes;
    }

    public String getPaidDayUnits() {
        return paidDayUnits;
    }

    public Integer getScheduledDayUnits() {
        return scheduledDayUnits;
    }

    public String getExactPaise() {
        return exactPaise;
    }

    public Long getSuggestedAmountPaise() {
        return suggestedAmountPaise;
    }

    public Long getAmountPaise() {
        return amountPaise;
    }

    public ReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public String getReviewKey() {
        return reviewKey;
    }

    public String getExplanation() {
        return explanation;
    }
}
