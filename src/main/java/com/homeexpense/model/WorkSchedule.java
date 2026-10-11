package com.homeexpense.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * One version of a worker's weekly schedule. A schedule change adds a new version; the
 * entries of an existing version are never edited, so history is preserved.
 */
@Entity
@Table(name = "work_schedule")
public class WorkSchedule implements EffectiveDated {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private Worker worker;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    private String note;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.PERSIST)
    @OrderBy("dayOfWeek, id")
    private List<ScheduleEntry> entries = new ArrayList<>();

    protected WorkSchedule() {
        // Required by JPA
    }

    public WorkSchedule(Worker worker, LocalDate effectiveFrom, LocalDate effectiveTo, String note) {
        this.worker = worker;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.note = note;
    }

    void addEntry(ScheduleEntry entry) {
        entries.add(entry);
    }

    public List<ScheduleEntry> entriesOn(DayOfWeek day) {
        return entries.stream().filter(entry -> entry.getDayOfWeek() == day).toList();
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

    public List<ScheduleEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}
