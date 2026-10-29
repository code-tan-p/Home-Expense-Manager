package com.homeexpense.model;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A continuous period of employment. Both dates are inclusive; a null end date means still employed. */
@Entity
@Table(name = "employment_period")
public class EmploymentPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private Worker worker;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "end_reason")
    private String endReason;

    protected EmploymentPeriod() {
        // Required by JPA
    }

    public EmploymentPeriod(Worker worker, LocalDate startDate) {
        this.worker = worker;
        this.startDate = startDate;
    }

    public boolean isOpen() {
        return endDate == null;
    }

    public boolean covers(LocalDate date) {
        return !date.isBefore(startDate) && (endDate == null || !date.isAfter(endDate));
    }

    public void close(LocalDate lastWorkingDay, String reason) {
        this.endDate = lastWorkingDay;
        this.endReason = reason;
    }

    public Long getId() {
        return id;
    }

    public Worker getWorker() {
        return worker;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public String getEndReason() {
        return endReason;
    }
}
