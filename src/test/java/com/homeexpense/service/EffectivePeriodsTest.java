package com.homeexpense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.EffectiveDated;
import org.junit.jupiter.api.Test;

class EffectivePeriodsTest {

    private static final class Version implements EffectiveDated {
        private final LocalDate from;
        private LocalDate to;

        Version(String from, String to) {
            this.from = LocalDate.parse(from);
            this.to = to == null ? null : LocalDate.parse(to);
        }

        @Override
        public LocalDate getEffectiveFrom() {
            return from;
        }

        @Override
        public LocalDate getEffectiveTo() {
            return to;
        }

        @Override
        public void endOn(LocalDate lastDay) {
            this.to = lastDay;
        }
    }

    private static LocalDate d(String text) {
        return LocalDate.parse(text);
    }

    @Test
    void firstVersionIsOpenEnded() {
        var placement = EffectivePeriods.place(List.<Version>of(), d("2026-01-01"), "Version");

        assertThat(placement.versionToShorten()).isNull();
        assertThat(placement.newVersionEnd()).isNull();
    }

    @Test
    void laterChangeShortensTheOpenVersion() {
        Version current = new Version("2026-01-01", null);

        var placement = EffectivePeriods.place(List.of(current), d("2026-04-01"), "Version");

        assertThat(placement.versionToShorten()).isSameAs(current);
        assertThat(placement.newVersionEnd()).isNull();
    }

    @Test
    void pastChangeBetweenVersionsFitsWithoutOverlap() {
        Version first = new Version("2026-01-01", "2026-02-28");
        Version second = new Version("2026-03-01", null);

        var placement = EffectivePeriods.place(List.of(first, second), d("2026-02-01"), "Version");

        assertThat(placement.versionToShorten()).isSameAs(first);
        assertThat(placement.newVersionEnd()).isEqualTo(d("2026-02-28"));
    }

    @Test
    void changeBeforeAllVersionsEndsBeforeTheFirstOne() {
        Version first = new Version("2026-03-01", null);

        var placement = EffectivePeriods.place(List.of(first), d("2026-01-15"), "Version");

        assertThat(placement.versionToShorten()).isNull();
        assertThat(placement.newVersionEnd()).isEqualTo(d("2026-02-28"));
    }

    @Test
    void changeInsideAClosedVersionKeepsItsEnd() {
        Version ended = new Version("2026-01-01", "2026-06-30");

        var placement = EffectivePeriods.place(List.of(ended), d("2026-04-01"), "Version");

        assertThat(placement.versionToShorten()).isSameAs(ended);
        assertThat(placement.newVersionEnd()).isEqualTo(d("2026-06-30"));
    }

    @Test
    void changeInAGapAfterAnEndedVersionIsOpenEnded() {
        Version ended = new Version("2026-01-01", "2026-03-31");

        var placement = EffectivePeriods.place(List.of(ended), d("2026-05-01"), "Version");

        assertThat(placement.versionToShorten()).isNull();
        assertThat(placement.newVersionEnd()).isNull();
    }

    @Test
    void changeOnTheLastDayOfAVersionShortensItByOneDay() {
        Version ended = new Version("2026-01-01", "2026-03-31");

        var placement = EffectivePeriods.place(List.of(ended), d("2026-03-31"), "Version");

        assertThat(placement.versionToShorten()).isSameAs(ended);
        assertThat(placement.newVersionEnd()).isEqualTo(d("2026-03-31"));
    }

    @Test
    void sameStartDateIsRejectedRatherThanReplaced() {
        Version current = new Version("2026-01-01", null);

        assertThatThrownBy(() -> EffectivePeriods.place(List.of(current), d("2026-01-01"), "A schedule version"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already starts on 2026-01-01")
                .hasMessageContaining("not replaced in place");
        assertThat(current.getEffectiveTo()).isNull();
    }
}
