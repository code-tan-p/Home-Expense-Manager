package com.homeexpense.dto;

import java.time.LocalDate;
import java.util.List;

import com.homeexpense.model.ScheduleEntry;
import com.homeexpense.model.WorkRecord;
import com.homeexpense.model.Worker;

/**
 * Everything known about one date: for each employed worker, what was expected and what has
 * actually been recorded. Expected activities without a record are "not recorded yet" and are
 * never treated as done.
 */
public record DayView(LocalDate date, List<WorkerDay> workers) {

    public enum DayType {
        /** The worker's schedule has activities on this date. */
        SCHEDULED_DAY,
        /** The worker's schedule has no activities on this date. */
        REGULAR_DAY_OFF
    }

    public record ExpectedActivity(ScheduleEntry entry, WorkRecord record) {

        public boolean isRecorded() {
            return record != null;
        }
    }

    public record WorkerDay(Worker worker, DayType dayType, List<ExpectedActivity> expected,
                            List<WorkRecord> unscheduled, List<String> flags) {

        /** How unscheduled work on this date is classified; paying it is decided later. */
        public String unscheduledWorkLabel() {
            return dayType == DayType.REGULAR_DAY_OFF ? "Work on a regular day off" : "Additional activity";
        }

        public long notRecordedCount() {
            return expected.stream().filter(activity -> !activity.isRecorded()).count();
        }
    }
}
