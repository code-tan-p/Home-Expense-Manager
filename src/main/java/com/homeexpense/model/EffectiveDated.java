package com.homeexpense.model;

import java.time.LocalDate;

/** A record that applies from {@code effectiveFrom} to {@code effectiveTo}, both inclusive; a null end is open-ended. */
public interface EffectiveDated {

    LocalDate getEffectiveFrom();

    LocalDate getEffectiveTo();

    /** Shortens this version so that it ends on {@code lastDay}. */
    void endOn(LocalDate lastDay);

    default boolean covers(LocalDate date) {
        return !date.isBefore(getEffectiveFrom()) && (getEffectiveTo() == null || !date.isAfter(getEffectiveTo()));
    }

    default boolean overlaps(LocalDate from, LocalDate to) {
        boolean startsBeforeOtherEnds = to == null || !getEffectiveFrom().isAfter(to);
        boolean otherStartsBeforeThisEnds = getEffectiveTo() == null || !from.isAfter(getEffectiveTo());
        return startsBeforeOtherEnds && otherStartsBeforeThisEnds;
    }
}
