package com.homeexpense.model.earnings;

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
import org.hibernate.annotations.DynamicUpdate;

/**
 * Records that a finalized earnings run was reopened (D8). The run itself is never changed;
 * it simply stops being active. The replacement run is linked once it is created.
 */
@Entity
@DynamicUpdate
@Table(name = "earning_run_supersession")
public class EarningRunSupersession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "superseded_run_id", nullable = false)
    private EarningRun supersededRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replacement_run_id")
    private EarningRun replacementRun;

    @Column(nullable = false)
    private String reason;

    @Column(name = "reopened_at", nullable = false)
    private Instant reopenedAt;

    protected EarningRunSupersession() {
        // Required by JPA
    }

    public EarningRunSupersession(EarningRun supersededRun, String reason, Instant reopenedAt) {
        this.supersededRun = supersededRun;
        this.reason = reason;
        this.reopenedAt = reopenedAt;
    }

    public void linkReplacement(EarningRun replacement) {
        this.replacementRun = replacement;
    }

    public Long getId() {
        return id;
    }

    public EarningRun getSupersededRun() {
        return supersededRun;
    }

    public EarningRun getReplacementRun() {
        return replacementRun;
    }

    public String getReason() {
        return reason;
    }

    public Instant getReopenedAt() {
        return reopenedAt;
    }
}
