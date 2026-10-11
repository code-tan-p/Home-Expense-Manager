package com.homeexpense.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.EffectiveDated;

/**
 * Decides where a new version starting on a given date fits among existing versions,
 * without ever creating an overlap or rewriting an existing version's contents.
 *
 * <ul>
 *   <li>If a version already starts on that date, the change is rejected: replacing it
 *   would rewrite history in place.</li>
 *   <li>The version covering that date (if any) is shortened to end the day before.</li>
 *   <li>The new version ends where the shortened version ended, or the day before the
 *   next version starts, or is open-ended.</li>
 * </ul>
 */
public final class EffectivePeriods {

    /** Stand-in for "no end date" in range queries. ISO text for this date sorts after every real date. */
    public static final LocalDate FAR_FUTURE = LocalDate.of(9999, 12, 31);

    /**
     * @param versionToShorten existing version that must end the day before the new one, or null
     * @param newVersionEnd    inclusive end of the new version, or null for open-ended
     */
    public record Placement<T extends EffectiveDated>(T versionToShorten, LocalDate newVersionEnd) {
    }

    private EffectivePeriods() {
    }

    public static <T extends EffectiveDated> Placement<T> place(List<T> versions, LocalDate from, String description) {
        for (T version : versions) {
            if (version.getEffectiveFrom().equals(from)) {
                throw new BusinessRuleException(description + " already starts on " + from
                        + ". Existing versions are not replaced in place; choose a different start date.");
            }
        }
        T containing = versions.stream()
                .filter(version -> version.getEffectiveFrom().isBefore(from) && version.covers(from))
                .findFirst()
                .orElse(null);
        if (containing != null) {
            return new Placement<>(containing, containing.getEffectiveTo());
        }
        LocalDate nextStart = versions.stream()
                .map(EffectiveDated::getEffectiveFrom)
                .filter(start -> start.isAfter(from))
                .min(Comparator.naturalOrder())
                .orElse(null);
        return new Placement<>(null, nextStart == null ? null : nextStart.minusDays(1));
    }
}
