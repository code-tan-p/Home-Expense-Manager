package com.homeexpense.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * An exact rational number, used for earnings so that no intermediate value is ever rounded.
 * A recorded amount is rounded once, with {@link #roundHalfUp()}, when it becomes paise.
 */
public record Fraction(BigInteger numerator, BigInteger denominator) implements Comparable<Fraction> {

    public static final Fraction ZERO = of(0);
    public static final Fraction ONE = of(1);

    public Fraction {
        if (denominator.signum() == 0) {
            throw new ArithmeticException("Denominator must not be zero");
        }
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger gcd = numerator.gcd(denominator);
        if (!gcd.equals(BigInteger.ONE) && gcd.signum() != 0) {
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
        }
    }

    public static Fraction of(long value) {
        return new Fraction(BigInteger.valueOf(value), BigInteger.ONE);
    }

    public static Fraction of(long numerator, long denominator) {
        return new Fraction(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    public static Fraction of(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            return new Fraction(stripped.toBigIntegerExact(), BigInteger.ONE);
        }
        return new Fraction(stripped.unscaledValue(), BigInteger.TEN.pow(stripped.scale()));
    }

    /** Parses the {@link #toString()} form, "n" or "n/d". */
    public static Fraction parse(String text) {
        int slash = text.indexOf('/');
        if (slash < 0) {
            return new Fraction(new BigInteger(text.strip()), BigInteger.ONE);
        }
        return new Fraction(new BigInteger(text.substring(0, slash).strip()), new BigInteger(text.substring(slash + 1).strip()));
    }

    public Fraction add(Fraction other) {
        return new Fraction(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    public Fraction subtract(Fraction other) {
        return add(other.negate());
    }

    public Fraction multiply(Fraction other) {
        return new Fraction(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
    }

    public Fraction multiply(long value) {
        return multiply(of(value));
    }

    public Fraction divide(Fraction other) {
        if (other.numerator.signum() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        return new Fraction(numerator.multiply(other.denominator), denominator.multiply(other.numerator));
    }

    public Fraction negate() {
        return new Fraction(numerator.negate(), denominator);
    }

    public boolean isZero() {
        return numerator.signum() == 0;
    }

    public int signum() {
        return numerator.signum();
    }

    /** Rounds to the nearest whole number, halves away from zero. */
    public long roundHalfUp() {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /** A decimal for display, rounded HALF_UP to {@code scale} places. Never used for calculation. */
    public String toDecimalString(int scale) {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), scale, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    @Override
    public int compareTo(Fraction other) {
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }

    @Override
    public String toString() {
        return denominator.equals(BigInteger.ONE) ? numerator.toString() : numerator + "/" + denominator;
    }
}
