package org.opendaq;

/**
 * The result of a reader's {@code readWithDomain}: the sample values and the
 * parallel domain (e.g. timestamp tick) values, both sized to the actual
 * sample count.  The arrays are typed by the reader's value/domain read
 * types; the accessors cover the default {@code FLOAT64} / {@code INT64}
 * combination.
 */
public record SamplesWithDomain(Object values, Object domain) {

    /** The values as {@code double[]} (readers with the default FLOAT64 value type). */
    public double[] doubleValues() {
        return (double[]) values;
    }

    /** The values as a {@code double[][]} matrix (block readers / dimensioned signals). */
    public double[][] doubleMatrix() {
        return (double[][]) values;
    }

    /** The domain as {@code long[]} ticks (readers with the default INT64 domain type). */
    public long[] domainTicks() {
        return (long[]) domain;
    }

    /** The domain as a {@code long[][]} matrix (block readers). */
    public long[][] domainTickMatrix() {
        return (long[][]) domain;
    }
}
