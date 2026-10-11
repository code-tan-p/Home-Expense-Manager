package com.homeexpense.dto;

import java.time.LocalTime;

import com.homeexpense.model.AbsenceReason;
import com.homeexpense.model.WorkOutcome;

/**
 * What actually happened for one activity. Times are optional; when given, actual minutes
 * are worked out from them. Quantities are in thousandths of a unit (1.5 litres = 1500).
 */
public record WorkDetails(WorkOutcome outcome, AbsenceReason absenceReason, LocalTime actualStart, LocalTime actualEnd,
                          Integer actualMinutes, Long actualQuantityMilli, String note) {

    public static WorkDetails completed() {
        return new WorkDetails(WorkOutcome.COMPLETED, null, null, null, null, null, null);
    }

    public static WorkDetails completedMinutes(int minutes) {
        return new WorkDetails(WorkOutcome.COMPLETED, null, null, null, minutes, null, null);
    }

    public static WorkDetails completedQuantity(long quantityMilli) {
        return new WorkDetails(WorkOutcome.COMPLETED, null, null, null, null, quantityMilli, null);
    }

    public static WorkDetails partialMinutes(int minutes) {
        return new WorkDetails(WorkOutcome.PARTIAL, null, null, null, minutes, null, null);
    }

    public static WorkDetails partialQuantity(long quantityMilli) {
        return new WorkDetails(WorkOutcome.PARTIAL, null, null, null, null, quantityMilli, null);
    }

    public static WorkDetails notDone(AbsenceReason reason) {
        return new WorkDetails(WorkOutcome.NOT_DONE, reason, null, null, null, null, null);
    }

    public WorkDetails withNote(String newNote) {
        return new WorkDetails(outcome, absenceReason, actualStart, actualEnd, actualMinutes, actualQuantityMilli, newNote);
    }
}
