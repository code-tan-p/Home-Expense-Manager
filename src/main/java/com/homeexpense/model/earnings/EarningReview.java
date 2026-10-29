package com.homeexpense.model.earnings;

import java.time.Instant;

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
import org.hibernate.annotations.DynamicUpdate;

/**
 * A household decision about a suggested or manual amount. It applies only while its review
 * key (which names the exact work records) and the suggestion it was made against are unchanged.
 */
@Entity
@DynamicUpdate
@Table(name = "earning_review")
public class EarningReview {

    /** Stored in place of a suggestion when there was none (manual review). */
    public static final String NO_SUGGESTION = "-";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private Worker worker;

    @Column(name = "review_key", nullable = false)
    private String reviewKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewDecisionKind decision;

    @Column(name = "amount_paise")
    private Long amountPaise;

    @Column(name = "unpaid_day_units")
    private String unpaidDayUnits;

    @Column(name = "suggestion_snapshot", nullable = false)
    private String suggestionSnapshot;

    private String reason;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    protected EarningReview() {
        // Required by JPA
    }

    @SuppressWarnings("java:S107")
    public EarningReview(Worker worker, String reviewKey, ReviewDecisionKind decision, Long amountPaise,
                         String unpaidDayUnits, String suggestionSnapshot, String reason, Instant decidedAt) {
        this.worker = worker;
        this.reviewKey = reviewKey;
        this.decision = decision;
        this.amountPaise = amountPaise;
        this.unpaidDayUnits = unpaidDayUnits;
        this.suggestionSnapshot = suggestionSnapshot;
        this.reason = reason;
        this.decidedAt = decidedAt;
    }

    public void supersede(Instant when) {
        this.supersededAt = when;
    }

    public boolean appliesTo(String currentSuggestion) {
        return suggestionSnapshot.equals(currentSuggestion == null ? NO_SUGGESTION : currentSuggestion);
    }

    public Long getId() {
        return id;
    }

    public String getReviewKey() {
        return reviewKey;
    }

    public ReviewDecisionKind getDecision() {
        return decision;
    }

    public Long getAmountPaise() {
        return amountPaise;
    }

    public String getUnpaidDayUnits() {
        return unpaidDayUnits;
    }

    public String getSuggestionSnapshot() {
        return suggestionSnapshot;
    }

    public String getReason() {
        return reason;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }
}
