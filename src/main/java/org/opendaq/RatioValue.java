package org.opendaq;

/**
 * The natural Java value of an openDAQ Ratio: an exact fraction.  Java has no
 * built-in rational type, so ratios (e.g. a domain tick resolution of 1/1000)
 * unbox to this record instead of a lossy double.
 */
public record RatioValue(long numerator, long denominator) {

    public RatioValue {
        if (denominator == 0) {
            throw new IllegalArgumentException("Ratio denominator cannot be zero.");
        }
    }

    /** The fraction as a (possibly lossy) double. */
    public double doubleValue() {
        return (double) numerator / (double) denominator;
    }

    /** The reduced form of this fraction. */
    public RatioValue simplified() {
        long gcd = java.math.BigInteger.valueOf(numerator)
            .gcd(java.math.BigInteger.valueOf(denominator)).longValueExact();
        if (gcd == 0) return this;
        return new RatioValue(numerator / gcd, denominator / gcd);
    }

    public static RatioValue of(long numerator, long denominator) {
        return new RatioValue(numerator, denominator);
    }

    @Override
    public String toString() {
        return numerator + "/" + denominator;
    }
}
