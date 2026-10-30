package com.homeexpense.money;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import com.homeexpense.exception.BusinessRuleException;

/**
 * Conversion between rupee amounts and stored integer paise (1 rupee = 100 paise).
 *
 * <p>Conversion is exact: amounts with more than two decimal places are rejected rather
 * than rounded, and floating-point types are never used.
 */
public final class Money {

    private static final Pattern RUPEES = Pattern.compile("-?\\d+(\\.\\d+)?");

    private Money() {
    }

    /** Parses text such as "115.38", "100" or "₹ 99.5" into paise. */
    public static long parseRupees(String text) {
        if (text == null || text.isBlank()) {
            throw new BusinessRuleException("Enter an amount.");
        }
        String cleaned = text.strip().replaceFirst("^₹", "").strip();
        if (!RUPEES.matcher(cleaned).matches()) {
            throw new BusinessRuleException("'" + text.strip() + "' is not a valid amount. "
                    + "Use digits with up to two decimal places, for example 115.38.");
        }
        return toPaise(new BigDecimal(cleaned));
    }

    /** Parses an amount that must be more than zero. */
    public static long parsePositiveRupees(String text) {
        long paise = parseRupees(text);
        if (paise <= 0) {
            throw new BusinessRuleException("The amount must be more than ₹0.00.");
        }
        return paise;
    }

    /** Converts an exact rupee amount to paise; rejects more than two decimal places. */
    public static long toPaise(BigDecimal rupees) {
        if (rupees.stripTrailingZeros().scale() > 2) {
            throw new BusinessRuleException("The amount " + rupees.toPlainString()
                    + " has more than two decimal places. Amounts are recorded to the paisa and are never rounded automatically.");
        }
        try {
            return rupees.movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new BusinessRuleException("The amount " + rupees.toPlainString() + " is too large.");
        }
    }

    public static BigDecimal toRupees(long paise) {
        return BigDecimal.valueOf(paise, 2);
    }

    /** Formats paise with exactly two decimal places, for example ₹115.38 or -₹5.00. */
    /** Rupees as plain text for an input box, for example 1250.50. */
    public static String plain(long paise) {
        return java.math.BigDecimal.valueOf(paise, 2).toPlainString();
    }

    public static String format(long paise) {
        BigDecimal rupees = toRupees(paise);
        return rupees.signum() < 0 ? "-₹" + rupees.negate().toPlainString() : "₹" + rupees.toPlainString();
    }
}
