package com.opendaq;

import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.LongFunction;

import com.opendaq.lowlevel.Ffi;
import com.opendaq.lowlevel.NativeLoader;

/**
 * Top-level openDAQ utilities: native library management, unboxing, component
 * type discovery, and domain-tick timestamp conversion.
 */
public final class Daq {

    private Daq() {}

    /** Print a diagnostic report on locating/loading the native openDAQ libraries. */
    public static void healthcheck() {
        healthcheck(System.out);
    }

    public static void healthcheck(PrintStream out) {
        NativeLoader.healthcheck(out);
    }

    /**
     * Download and cache the pinned native openDAQ binaries for this platform
     * (done automatically on first use unless {@code OPENDAQ_NO_DOWNLOAD} is set).
     */
    public static Path installNativeLibraries() {
        return NativeLoader.installNativeLibraries();
    }

    /** The directory the native openDAQ libraries are loaded from. */
    public static Path nativeLibraryDirectory() {
        return NativeLoader.nativeLibraryDirectory();
    }

    /** Clear the calling thread's openDAQ error info. */
    public static void clearErrorInfo() {
        Ffi.clearErrorInfo();
    }

    /**
     * The natural Java value of {@code value}: unboxes a {@link DaqObject}
     * wrapper by its runtime type (see {@link DaqObject#unbox()}) and returns
     * an already-native Java value unchanged, so it is idempotent.
     */
    public static Object unbox(Object value) {
        return Box.unboxAny(value);
    }

    private static final List<Class<? extends DaqObject>> COMPONENT_TYPES = List.of(
        Channel.class, FunctionBlock.class, Device.class, Signal.class,
        InputPort.class, Folder.class, Component.class);

    /**
     * The most-derived component interface {@code object} implements
     * ({@code Channel}, {@code Signal}, {@code Device}, ...) as a wrapper
     * class, or null when it is none of them.  Lets you discover a
     * component's concrete type before committing to an
     * {@link DaqObject#asType} cast.
     */
    public static Class<? extends DaqObject> componentType(DaqObject object) {
        for (Class<? extends DaqObject> type : COMPONENT_TYPES) {
            if (object.isA(type)) {
                return type;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Domain timestamps
    //
    // A domain signal reports plain integer ticks.  Turning a tick into
    // wall-clock time needs the domain origin (an ISO-8601 string) and the
    // tick resolution (seconds per tick, an exact ratio):
    //     absolute time = origin + tick * resolution.
    // ------------------------------------------------------------------

    private static Instant parseOrigin(String origin) {
        try {
            return Instant.parse(origin);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(origin).toInstant();
            } catch (DateTimeParseException e2) {
                return LocalDateTime.parse(origin).toInstant(ZoneOffset.UTC);
            }
        }
    }

    private static LongFunction<Instant> converter(String origin, long numerator, long denominator) {
        Instant originInstant = parseOrigin(origin);
        BigInteger num = BigInteger.valueOf(numerator).multiply(BigInteger.valueOf(1_000_000_000L));
        BigInteger den = BigInteger.valueOf(denominator);
        return tick -> {
            BigInteger nanos = BigInteger.valueOf(tick).multiply(num).divide(den);
            return originInstant.plusNanos(nanos.longValueExact());
        };
    }

    /**
     * A function mapping a domain tick to an absolute {@link Instant}, using
     * the descriptor's origin and tick resolution.  The metadata is read
     * once, so reuse the returned function when converting many ticks.
     */
    public static LongFunction<Instant> domainTimeConverter(DataDescriptor descriptor) {
        Ratio resolution = descriptor.getTickResolution();
        return converter(descriptor.getOrigin(), resolution.getNumerator(), resolution.getDenominator());
    }

    /** Tick converter for a signal, using its domain signal's descriptor. */
    public static LongFunction<Instant> domainTimeConverter(Signal signal) {
        return domainTimeConverter(signal.getDomainSignal().getDescriptor());
    }

    /** Tick converter for a multi reader's common domain. */
    public static LongFunction<Instant> domainTimeConverter(MultiReader reader) {
        Ratio resolution = reader.getTickResolution();
        return converter(reader.getOrigin(), resolution.getNumerator(), resolution.getDenominator());
    }

    /**
     * Convert a single domain tick to an absolute {@link Instant}.  Prefer
     * {@link #domainTimeConverter} when converting many ticks, to avoid
     * re-reading the domain metadata each time.
     */
    public static Instant domainTickToInstant(DataDescriptor descriptor, long tick) {
        return domainTimeConverter(descriptor).apply(tick);
    }

    public static Instant domainTickToInstant(Signal signal, long tick) {
        return domainTimeConverter(signal).apply(tick);
    }
}
