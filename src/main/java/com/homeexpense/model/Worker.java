package com.homeexpense.model;

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

/**
 * A household worker. Rows are never deleted; whether a worker is active on a date is
 * derived from their {@link EmploymentPeriod}s.
 */
@Entity
@Table(name = "worker")
public class Worker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_type_id", nullable = false)
    private WorkerType workerType;

    private String phone;

    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Worker() {
        // Required by JPA
    }

    public Worker(String name, WorkerType workerType, String phone, String notes, Instant createdAt) {
        this.name = name;
        this.workerType = workerType;
        this.phone = phone;
        this.notes = notes;
        this.createdAt = createdAt;
    }

    /** Changes the contact details. The job (and so how pay works) stays as it was. */
    public void updateDetails(String name, String phone, String notes) {
        this.name = name;
        this.phone = phone;
        this.notes = notes;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public WorkerType getWorkerType() {
        return workerType;
    }

    public String getPhone() {
        return phone;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
