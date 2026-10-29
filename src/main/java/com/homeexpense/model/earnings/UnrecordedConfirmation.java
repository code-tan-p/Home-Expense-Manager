package com.homeexpense.model.earnings;

import java.time.Instant;
import java.time.LocalDate;

import com.homeexpense.model.Worker;
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
 * The household's explicit statement about scheduled activities left unrecorded in a month.
 * Valid only while its fingerprint matches the current set of unrecorded dates and activities.
 */
@Entity
@DynamicUpdate
@Table(name = "earning_unrecorded_confirmation")
public class UnrecordedConfirmation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private Worker worker;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(nullable = false)
    private String items;

    @Column(nullable = false)
    private String fingerprint;

    @Column(nullable = false)
    private String statement;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    protected UnrecordedConfirmation() {
        // Required by JPA
    }

    public UnrecordedConfirmation(Worker worker, LocalDate periodStart, String items, String fingerprint,
                                  String statement, Instant confirmedAt) {
        this.worker = worker;
        this.periodStart = periodStart;
        this.items = items;
        this.fingerprint = fingerprint;
        this.statement = statement;
        this.confirmedAt = confirmedAt;
    }

    public void supersede(Instant when) {
        this.supersededAt = when;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public String getItems() {
        return items;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getStatement() {
        return statement;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }
}
