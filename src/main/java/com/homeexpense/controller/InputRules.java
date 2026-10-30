package com.homeexpense.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.regex.Pattern;

import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.money.Money;

/**
 * The limits for everything typed into the website, in one place. They stop typing mistakes (an extra
 * zero, a year 9999, "1e9") from becoming records that cannot be paid or closed.
 */
public final class InputRules {

    public static final LocalDate EARLIEST_DATE = LocalDate.of(2000, 1, 1);
    public static final long MAX_RUPEES = 1_000_000;
    public static final int MAX_LITRES = 1000;
    public static final int MAX_HOURS_PER_DAY = 24;
    public static final int MAX_NAME = 100;
    public static final int MAX_PHONE = 30;

    /** Digits with an optional decimal part of at most three digits; no sign, no exponent. */
    private static final Pattern PLAIN_NUMBER = Pattern.compile("\\d{1,7}([.,]\\d{1,3})?");

    private InputRules() {
    }

    /** A day from 1 January 2000 up to one year after today. */
    public static LocalDate requireDate(LocalDate date, LocalDate today) {
        if (date == null) {
            throw new BusinessRuleException("Choose a date.");
        }
        if (date.isBefore(EARLIEST_DATE) || date.isAfter(today.plusYears(1))) {
            throw new BusinessRuleException("Choose a date between " + EARLIEST_DATE.getYear() + " and next year.");
        }
        return date;
    }

    public static YearMonth requireMonth(YearMonth month, LocalDate today) {
        if (month == null) {
            throw new BusinessRuleException("Choose a month.");
        }
        requireDate(month.atDay(1), today);
        return month;
    }

    /** A plain positive number such as 2, 1.5 or 1,5 with at most three decimals. */
    public static BigDecimal requirePlainNumber(String text, String what) {
        String trimmed = text == null ? "" : text.strip();
        if (!PLAIN_NUMBER.matcher(trimmed).matches()) {
            throw new BusinessRuleException("Enter " + what + " as a number such as 2 or 1.5.");
        }
        return new BigDecimal(trimmed.replace(',', '.'));
    }

    /** Litres as thousandths: more than zero and at most {@value #MAX_LITRES}. */
    public static long requireLitresMilli(String text) {
        BigDecimal litres = requirePlainNumber(text, "the litres");
        if (litres.signum() <= 0) {
            throw new BusinessRuleException("Enter more than zero litres. For no delivery, use the 'No delivery' button.");
        }
        if (litres.compareTo(BigDecimal.valueOf(MAX_LITRES)) > 0) {
            throw new BusinessRuleException("That is more than " + MAX_LITRES + " litres in a day. Please check the number.");
        }
        return litres.movePointRight(3).longValueExact();
    }

    /** Hours worked in a day, as whole minutes: more than zero and at most 24 hours. */
    public static int requireMinutes(String text) {
        BigDecimal hours = requirePlainNumber(text, "the hours");
        if (hours.signum() <= 0) {
            throw new BusinessRuleException("Enter more than zero hours. If they did not work, use 'Our holiday' or 'On leave'.");
        }
        if (hours.compareTo(BigDecimal.valueOf(MAX_HOURS_PER_DAY)) > 0) {
            throw new BusinessRuleException("A day has at most " + MAX_HOURS_PER_DAY + " hours.");
        }
        BigDecimal minutes = hours.multiply(BigDecimal.valueOf(60));
        if (minutes.stripTrailingZeros().scale() > 0) {
            throw new BusinessRuleException("Use hours that make whole minutes, for example 1.5 or 0.25.");
        }
        return minutes.intValueExact();
    }

    /** Rupees to two decimals, more than zero and at most ₹10,00,000. Returns the cleaned text. */
    public static String requireRupees(String text, String what) {
        String cleaned = text == null ? "" : text.strip().replace(",", "").replace("₹", "").strip();
        long paise;
        try {
            paise = Money.parsePositiveRupees(cleaned);
        } catch (BusinessRuleException e) {
            throw new BusinessRuleException("Enter " + what + " as rupees, for example 5500 or 72.50.");
        } catch (RuntimeException e) {
            throw new BusinessRuleException("Enter " + what + " as rupees, for example 5500 or 72.50.");
        }
        if (paise > MAX_RUPEES * 100) {
            throw new BusinessRuleException("That is more than ₹" + MAX_RUPEES + ". Please check the amount.");
        }
        return cleaned;
    }

    public static String requireName(String text) {
        String name = text == null ? "" : text.strip();
        if (name.isEmpty()) {
            throw new BusinessRuleException("Enter the worker's name.");
        }
        if (name.length() > MAX_NAME) {
            throw new BusinessRuleException("The name is too long. Use at most " + MAX_NAME + " characters.");
        }
        return name;
    }

    public static String optionalPhone(String text) {
        String phone = text == null ? "" : text.strip();
        if (phone.length() > MAX_PHONE) {
            throw new BusinessRuleException("The phone number is too long. Use at most " + MAX_PHONE + " characters.");
        }
        return phone;
    }

    /** Optional free text, trimmed and kept short. */
    public static String limited(String text, int max, String what) {
        String clean = text == null ? "" : text.strip();
        if (clean.length() > max) {
            throw new BusinessRuleException("Please keep " + what + " to " + max + " characters or fewer.");
        }
        return clean;
    }
}
