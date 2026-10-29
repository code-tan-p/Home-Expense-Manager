package com.homeexpense.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FractionTest {

    @Test
    void reducesAndNormalisesSign() {
        assertThat(Fraction.of(6, 4)).isEqualTo(Fraction.of(3, 2));
        assertThat(Fraction.of(3, -4).toString()).isEqualTo("-3/4");
        assertThat(Fraction.of(0, 7)).isEqualTo(Fraction.ZERO);
        assertThat(Fraction.of(10, 5).toString()).isEqualTo("2");
    }

    @Test
    void arithmeticIsExact() {
        Fraction third = Fraction.of(1, 3);
        assertThat(third.add(third).add(third)).isEqualTo(Fraction.ONE);
        assertThat(Fraction.of(27).subtract(third)).isEqualTo(Fraction.of(80, 3));
        assertThat(Fraction.of(600000).multiply(Fraction.of(80, 3)).divide(Fraction.of(27))).isEqualTo(Fraction.of(16000000, 27));
        assertThat(third.compareTo(Fraction.of(1, 2))).isNegative();
    }

    @ParameterizedTest
    @CsvSource({
            "10200000, 27, 377778",
            "16000000, 27, 592593",
            "24999, 4, 6250",
            "14125, 2, 7063",
            "8333, 2, 4167",
            "-5, 2, -3",
            "1, 3, 0"
    })
    void roundsHalfUpOnce(long numerator, long denominator, long expected) {
        assertThat(Fraction.of(numerator, denominator).roundHalfUp()).isEqualTo(expected);
    }

    @Test
    void convertsDecimalsAndParsesItsOwnText() {
        assertThat(Fraction.of(new BigDecimal("0.25"))).isEqualTo(Fraction.of(1, 4));
        assertThat(Fraction.of(new BigDecimal("1.500"))).isEqualTo(Fraction.of(3, 2));
        assertThat(Fraction.parse("80/3")).isEqualTo(Fraction.of(80, 3));
        assertThat(Fraction.parse(Fraction.of(-7, 9).toString())).isEqualTo(Fraction.of(-7, 9));
        assertThat(Fraction.of(2, 3).toDecimalString(4)).isEqualTo("0.6667");
    }

    @Test
    void rejectsZeroDenominator() {
        assertThatThrownBy(() -> Fraction.of(1, 0)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Fraction.ONE.divide(Fraction.ZERO)).isInstanceOf(ArithmeticException.class);
    }
}
