package com.homeexpense.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import com.homeexpense.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @ParameterizedTest
    @CsvSource({
            "115.38, 11538",
            "100, 10000",
            "100.00, 10000",
            "0.5, 50",
            "0.05, 5",
            "1.500, 150",
            "'₹ 99.90', 9990",
            "'  42  ', 4200",
            "50000000.00, 5000000000"
    })
    void parsesRupeesToExactPaise(String text, long expectedPaise) {
        assertThat(Money.parseRupees(text)).isEqualTo(expectedPaise);
    }

    @Test
    void rejectsMoreThanTwoDecimalPlacesInsteadOfRounding() {
        assertThatThrownBy(() -> Money.parseRupees("1.005"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("more than two decimal places");
        assertThatThrownBy(() -> Money.toPaise(new BigDecimal("115.384")))
                .isInstanceOf(BusinessRuleException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc", "1,000", "12.", ".5", "1e3", "₹", "12.3.4"})
    void rejectsTextThatIsNotAPlainAmount(String text) {
        assertThatThrownBy(() -> Money.parseRupees(text)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> Money.parseRupees(null)).isInstanceOf(BusinessRuleException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-5"})
    void positiveAmountsMustBeMoreThanZero(String text) {
        assertThatThrownBy(() -> Money.parsePositiveRupees(text))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("more than ₹0.00");
    }

    @Test
    void rejectsAmountsTooLargeForStorage() {
        assertThatThrownBy(() -> Money.parseRupees("999999999999999999999"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("too large");
    }

    @ParameterizedTest
    @CsvSource({
            "11538, ₹115.38",
            "10000, ₹100.00",
            "5, ₹0.05",
            "0, ₹0.00",
            "-500, -₹5.00",
            "5000000000, ₹50000000.00"
    })
    void formatsWithExactlyTwoDecimalPlaces(long paise, String expected) {
        assertThat(Money.format(paise)).isEqualTo(expected);
    }

    @Test
    void convertsPaiseToExactRupees() {
        assertThat(Money.toRupees(11538)).isEqualByComparingTo("115.38");
        assertThat(Money.toRupees(11538).scale()).isEqualTo(2);
    }
}
