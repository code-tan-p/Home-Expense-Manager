package com.homeexpense.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The worked-out lines read as plain sentences on screen. */
class PlainWordingTest {

    @Test
    void rateNumbersTechnicalNotesAndIsoDatesAreRemoved() {
        assertThat(EarningsService.plain("Day rate for 2026-10-05 (scheduled work done): per day rate ₹300.00 (standard rate #2) x 1 = ₹300.00 (one day line per date)."))
                .isEqualTo("Day rate for 5 Oct: per day rate ₹300.00 x 1 = ₹300.00.");
        assertThat(EarningsService.plain("Monthly salary ₹5000.00 (rate #1) x 27 paid of 27 scheduled day-units in 2026-10 = ₹5000.00."))
                .isEqualTo("Monthly salary ₹5000.00 x 27 paid of 27 scheduled days in October 2026 = ₹5000.00.");
        assertThat(EarningsService.plain(null)).isNull();
    }
}
