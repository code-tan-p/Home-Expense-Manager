package com.homeexpense.dto;

import java.time.LocalDate;

import com.homeexpense.model.PaymentModel;
import com.homeexpense.model.ScheduleEntry;

/** A scheduled activity on an employed date that has no current work record. It earns nothing by itself. */
public record UnrecordedActivity(LocalDate date, ScheduleEntry entry, PaymentModel model, String description) {

    /** Stable identity used for the confirmation fingerprint. */
    public String key() {
        return date + "#" + entry.getId();
    }
}
