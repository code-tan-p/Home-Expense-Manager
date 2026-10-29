package com.homeexpense.dto;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * One expected weekly activity. Give a task, a shift, or both. Start/end times override the
 * shift's defaults; durationMinutes is only for entries that have no times at all.
 * expectedQuantityMilli is in thousandths of a unit (1.5 litres = 1500).
 */
public record NewScheduleEntry(DayOfWeek dayOfWeek, Long taskId, Long shiftId, LocalTime startTime,
                               LocalTime endTime, Integer durationMinutes, Long expectedQuantityMilli) {

    public static NewScheduleEntry task(DayOfWeek day, Long taskId) {
        return new NewScheduleEntry(day, taskId, null, null, null, null, null);
    }

    public static NewScheduleEntry shift(DayOfWeek day, Long shiftId) {
        return new NewScheduleEntry(day, null, shiftId, null, null, null, null);
    }
}
