package com.opendaq;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Conversions between openDAQ's untyped sample buffers — whose element type
 * is only known at runtime from a {@link SampleType} — and Java's typed
 * arrays.  Used by the reader convenience API and the data packet accessors.
 *
 * <p>Unsigned sample types map onto the same-width signed Java type carrying
 * the raw bits (Java has no unsigned primitives).
 */
final class SampleArrays {

    private SampleArrays() {}

    /** The foreign element layout for a numeric sample type. */
    static ValueLayout elementLayout(SampleType type) {
        return switch (type) {
            case FLOAT32, COMPLEX_FLOAT32 -> ValueLayout.JAVA_FLOAT;
            case FLOAT64, COMPLEX_FLOAT64 -> ValueLayout.JAVA_DOUBLE;
            case INT8, UINT8 -> ValueLayout.JAVA_BYTE;
            case INT16, UINT16 -> ValueLayout.JAVA_SHORT;
            case INT32, UINT32 -> ValueLayout.JAVA_INT;
            case INT64, UINT64 -> ValueLayout.JAVA_LONG;
            default -> throw new IllegalArgumentException(
                "openDAQ sample type " + type + " is not a flat numeric buffer; the data "
                + "accessors handle the integer, float, and complex-float sample types.");
        };
    }

    static boolean isComplex(SampleType type) {
        return type == SampleType.COMPLEX_FLOAT32 || type == SampleType.COMPLEX_FLOAT64;
    }

    /**
     * Number of scalar values in one sample described by {@code descriptor}:
     * the size of its single dimension (e.g. a spectrum's bin count), or 1 —
     * mirroring the openDAQ readers, which unpack exactly one dimension.
     */
    static int descriptorWidth(DataDescriptor descriptor) {
        if (descriptor == null) {
            return 1;
        }
        java.util.List<Dimension> dimensions = descriptor.getDimensions();
        return dimensions.size() == 1 ? (int) dimensions.get(0).getSize() : 1;
    }

    /** Bytes needed for {@code samples} samples of {@code width} values each. */
    static long byteSize(SampleType type, long samples, long width) {
        long perValue = elementLayout(type).byteSize() * (isComplex(type) ? 2 : 1);
        return Math.max(samples * width * perValue, perValue);
    }

    /**
     * Copy {@code samples} samples of {@code width} values each out of a
     * foreign buffer: a flat primitive array when {@code width} is 1 (or a
     * {@link ComplexValue} array for complex types), else a 2-D array with
     * one row per sample.
     */
    static Object toArray(MemorySegment buffer, SampleType type, int samples, int width) {
        if (isComplex(type)) {
            if (width > 1) {
                throw new IllegalStateException(
                    "Reading complex samples from a dimensioned signal is not supported.");
            }
            return toComplexArray(buffer, type, samples);
        }
        if (width == 1) {
            return toFlatArray(buffer, type, samples);
        }
        Object[] rows = allocateRows(type, samples);
        long rowBytes = elementLayout(type).byteSize() * width;
        for (int row = 0; row < samples; row++) {
            rows[row] = toFlatArray(buffer.asSlice(row * rowBytes, rowBytes), type, width);
        }
        return rows;
    }

    private static Object[] allocateRows(SampleType type, int samples) {
        return switch (elementLayout(type)) {
            case ValueLayout.OfFloat f -> new float[samples][];
            case ValueLayout.OfDouble d -> new double[samples][];
            case ValueLayout.OfByte b -> new byte[samples][];
            case ValueLayout.OfShort s -> new short[samples][];
            case ValueLayout.OfInt i -> new int[samples][];
            case ValueLayout.OfLong l -> new long[samples][];
            default -> throw new IllegalStateException();
        };
    }

    private static Object toFlatArray(MemorySegment buffer, SampleType type, int count) {
        ValueLayout layout = elementLayout(type);
        MemorySegment slice = buffer.asSlice(0, layout.byteSize() * count);
        return switch (layout) {
            case ValueLayout.OfFloat f -> slice.toArray(f);
            case ValueLayout.OfDouble d -> slice.toArray(d);
            case ValueLayout.OfByte b -> slice.toArray(b);
            case ValueLayout.OfShort s -> slice.toArray(s);
            case ValueLayout.OfInt i -> slice.toArray(i);
            case ValueLayout.OfLong l -> slice.toArray(l);
            default -> throw new IllegalStateException();
        };
    }

    private static ComplexValue[] toComplexArray(MemorySegment buffer, SampleType type, int count) {
        ComplexValue[] result = new ComplexValue[count];
        if (type == SampleType.COMPLEX_FLOAT32) {
            float[] parts = buffer.asSlice(0, 8L * count).toArray(ValueLayout.JAVA_FLOAT);
            for (int i = 0; i < count; i++) {
                result[i] = new ComplexValue(parts[2 * i], parts[2 * i + 1]);
            }
        } else {
            double[] parts = buffer.asSlice(0, 16L * count).toArray(ValueLayout.JAVA_DOUBLE);
            for (int i = 0; i < count; i++) {
                result[i] = new ComplexValue(parts[2 * i], parts[2 * i + 1]);
            }
        }
        return result;
    }

    /**
     * Write {@code samples} — a {@code double[]}, {@code long[]},
     * {@code int[]}, {@code float[]}, or {@code ComplexValue[]} — into a
     * foreign buffer as {@code type}, coercing each element (reals to the
     * buffer's float type, or rounded to its integer type).  Returns the
     * count written; the buffer must already be large enough.
     */
    static int fromArray(MemorySegment buffer, SampleType type, Object samples) {
        if (samples instanceof ComplexValue[] complexes) {
            if (!isComplex(type)) {
                throw new IllegalArgumentException("Packet sample type " + type + " is not complex.");
            }
            for (int i = 0; i < complexes.length; i++) {
                writeComplex(buffer, type, i, complexes[i].real(), complexes[i].imaginary());
            }
            return complexes.length;
        }
        double[] values = switch (samples) {
            case double[] d -> d;
            case float[] f -> {
                double[] converted = new double[f.length];
                for (int i = 0; i < f.length; i++) converted[i] = f[i];
                yield converted;
            }
            case int[] ints -> {
                double[] converted = new double[ints.length];
                for (int i = 0; i < ints.length; i++) converted[i] = ints[i];
                yield converted;
            }
            case long[] longs -> {
                // Longs written to an integer buffer keep full precision.
                if (!isComplex(type) && !(elementLayout(type) instanceof ValueLayout.OfFloat)
                        && !(elementLayout(type) instanceof ValueLayout.OfDouble)) {
                    for (int i = 0; i < longs.length; i++) {
                        writeIntegral(buffer, type, i, longs[i]);
                    }
                    yield null;
                }
                double[] converted = new double[longs.length];
                for (int i = 0; i < longs.length; i++) converted[i] = longs[i];
                yield converted;
            }
            default -> throw new IllegalArgumentException(
                "Unsupported sample array type: " + samples.getClass().getName());
        };
        if (values == null) {
            return ((long[]) samples).length;
        }
        if (isComplex(type)) {
            for (int i = 0; i < values.length; i++) {
                writeComplex(buffer, type, i, values[i], 0.0);
            }
            return values.length;
        }
        for (int i = 0; i < values.length; i++) {
            switch (elementLayout(type)) {
                case ValueLayout.OfDouble d -> buffer.setAtIndex(d, i, values[i]);
                case ValueLayout.OfFloat f -> buffer.setAtIndex(f, i, (float) values[i]);
                default -> writeIntegral(buffer, type, i, Math.round(values[i]));
            }
        }
        return values.length;
    }

    private static void writeIntegral(MemorySegment buffer, SampleType type, int index, long value) {
        switch (elementLayout(type)) {
            case ValueLayout.OfByte b -> buffer.setAtIndex(b, index, (byte) value);
            case ValueLayout.OfShort s -> buffer.setAtIndex(s, index, (short) value);
            case ValueLayout.OfInt i -> buffer.setAtIndex(i, index, (int) value);
            case ValueLayout.OfLong l -> buffer.setAtIndex(l, index, value);
            default -> throw new IllegalStateException();
        }
    }

    private static void writeComplex(MemorySegment buffer, SampleType type, int index,
                                     double real, double imaginary) {
        if (type == SampleType.COMPLEX_FLOAT32) {
            buffer.setAtIndex(ValueLayout.JAVA_FLOAT, 2L * index, (float) real);
            buffer.setAtIndex(ValueLayout.JAVA_FLOAT, 2L * index + 1, (float) imaginary);
        } else {
            buffer.setAtIndex(ValueLayout.JAVA_DOUBLE, 2L * index, real);
            buffer.setAtIndex(ValueLayout.JAVA_DOUBLE, 2L * index + 1, imaginary);
        }
    }
}
