package com.homeexpense.model;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;

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
 * One expected activity in a weekly schedule version: a task and/or shift on a day of the week.
 * Its own start/end times override the shift's default times.
 */
@Entity
@Table(name = "schedule_entry")
public class ScheduleEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    private WorkSchedule schedule;

    @Column(name = "day_of_week", nullable = false)
    private DayOfWeek dayOfWeek;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id")
    private Shift shift;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "expected_quantity_milli")
    private Long expectedQuantityMilli;

    protected ScheduleEntry() {
        // Required by JPA
    }

    public ScheduleEntry(WorkSchedule schedule, DayOfWeek dayOfWeek, Task task, Shift shift,
                         LocalTime startTime, LocalTime endTime, Integer durationMinutes,
                         Long expectedQuantityMilli) {
        this.schedule = schedule;
        this.dayOfWeek = dayOfWeek;
        this.task = task;
        this.shift = shift;
        this.startTime = startTime;
        this.endTime = endTime;
        this.durationMinutes = durationMinutes;
        this.expectedQuantityMilli = expectedQuantityMilli;
        schedule.addEntry(this);
    }

    /** The entry's own start time, or the shift's default when the entry does not override it. */
    public LocalTime effectiveStartTime() {
        if (startTime != null) {
            return startTime;
        }
        return shift == null ? null : shift.getDefaultStart();
    }

    public LocalTime effectiveEndTime() {
        if (endTime != null) {
            return endTime;
        }
        return shift == null ? null : shift.getDefaultEnd();
    }

    /** Usual scheduled minutes: from the effective times, else the stated duration, else null. */
    public Integer scheduledMinutes() {
        LocalTime start = effectiveStartTime();
        LocalTime end = effectiveEndTime();
        if (start != null && end != null) {
            return (int) Duration.between(start, end).toMinutes();
        }
        return durationMinutes;
    }

    public Long getId() {
        return id;
    }

    public WorkSchedule getSchedule() {
        return schedule;
    }

    public DayOfWeek getDayOfWeek() {
        return dayOfWeek;
    }

    public Task getTask() {
        return task;
    }

    public Shift getShift() {
        return shift;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public Long getExpectedQuantityMilli() {
        return expectedQuantityMilli;
    }
}
