package com.opendaq;

import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;

import com.opendaq.lowlevel.Ffi;

/**
 * Root of every openDAQ wrapper class: owns a raw interface pointer and
 * registers a {@link Cleaner} action that calls {@code releaseRef} exactly
 * once when the wrapper is garbage collected, so openDAQ objects never have
 * to be released manually.  {@link #release()} releases eagerly instead.
 */
public class DaqObject implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    private volatile MemorySegment pointer;
    private final Cleaner.Cleanable cleanable;

    /**
     * Adopt {@code pointer}, an <em>owned</em> reference the C API handed back
     * (this does not add a reference).  Throws for a null pointer.
     */
    public DaqObject(MemorySegment pointer) {
        if (pointer == null || pointer.equals(MemorySegment.NULL)) {
            throw new IllegalArgumentException("Cannot wrap a null openDAQ pointer.");
        }
        this.pointer = pointer;
        this.cleanable = CLEANER.register(this, new ReleaseAction(pointer));
    }

    private static final class ReleaseAction implements Runnable {
        private final MemorySegment pointer;

        ReleaseAction(MemorySegment pointer) {
            this.pointer = pointer;
        }

        @Override
        public void run() {
            Ffi.releaseRef(pointer);
        }
    }

    /** The raw interface pointer (NULL after {@link #release()}). */
    public final MemorySegment rawPointer() {
        MemorySegment p = pointer;
        return p == null ? MemorySegment.NULL : p;
    }

    /** The raw pointer, or an {@link IllegalStateException} if already released. */
    public final MemorySegment requireLivePointer() {
        MemorySegment p = pointer;
        if (p == null || p.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("The openDAQ object " + this + " has already been released.");
        }
        return p;
    }

    /**
     * Release this wrapper's reference now instead of waiting for the garbage
     * collector.  Idempotent; any later method call on this wrapper throws.
     */
    public final void release() {
        MemorySegment p = pointer;
        if (p != null && !p.equals(MemorySegment.NULL)) {
            pointer = MemorySegment.NULL;
            cleanable.clean();
        }
    }

    /** Alias of {@link #release()} so wrappers work in try-with-resources. */
    @Override
    public final void close() {
        release();
    }

    /** Add a reference to the underlying object; returns the new reference count. */
    public final int addRef() {
        try {
            return Ffi.addRef(requireLivePointer());
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Cast this object to the more specific openDAQ {@code type}, returning a
     * wrapper of that type owning its own reference.  This is a real interface
     * query ({@code queryInterface}) — required because an object's interfaces
     * live at different vtable offsets — and an unsupported type throws an
     * {@link OpenDaqException} (use {@link #isA} to test first).
     *
     * <p>The few coretypes interfaces with no interface id in the C API
     * ({@code BaseObject}, {@code FunctionObject}, {@code Procedure}, ...)
     * cannot be queried; for those the pointer is reinterpreted unchecked,
     * which is sound only when {@code type} lies on the same interface
     * inheritance chain as this wrapper's type.
     *
     * <p>{@code asType} never leaves openDAQ: both the input and the result
     * are wrappers.  To get the natural Java value of an object use
     * {@link #unbox()}.
     */
    public final <T extends DaqObject> T asType(Class<T> type) {
        try {
            TypeRegistry.Entry<T> entry = TypeRegistry.entry(type);
            MemorySegment raw = requireLivePointer();
            if (entry.interfaceIdWriter() != null) {
                MemorySegment queried = TypeRegistry.queryInterface(raw, entry.interfaceIdWriter());
                return entry.wrapper().apply(queried);
            }
            Ffi.addRef(raw);
            return entry.wrapper().apply(raw);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * True when this object implements the openDAQ interface wrapped by
     * {@code type} — the same interface query {@link #asType} performs, but
     * returning a boolean instead of throwing, so it is safe to ask about any
     * type.
     */
    public final boolean isA(Class<? extends DaqObject> type) {
        try {
            TypeRegistry.Entry<? extends DaqObject> entry = TypeRegistry.entry(type);
            if (entry.interfaceIdWriter() == null) {
                throw new IllegalArgumentException(
                    "The openDAQ C API exposes no interface id for " + type.getSimpleName()
                    + ", so isA cannot query it.");
            }
            return TypeRegistry.borrowInterface(requireLivePointer(), entry.interfaceIdWriter()) != null;
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * The natural Java value of this object: a boxed scalar yields its native
     * value (Long, Double, String, Boolean, {@link RatioValue},
     * {@link ComplexValue}), a daq list yields a {@code java.util.List} and a
     * daq dict a {@code java.util.Map}, with elements unboxed recursively (an
     * element with no Java form stays a wrapper for {@link #asType}).  Throws
     * for an object with no natural Java form (a device, a property object,
     * ...) — those are what {@link #asType} / {@link #isA} are for.
     */
    public final Object unbox() {
        return Box.unboxObject(this);
    }

    /**
     * Adopt a raw owned pointer as a {@code type} wrapper without querying
     * (the correct way to wrap a pointer returned by an
     * {@code com.opendaq.lowlevel} call).  Returns null for a null pointer.
     */
    public static <T extends DaqObject> T wrap(MemorySegment pointer, Class<T> type) {
        if (pointer == null || pointer.equals(MemorySegment.NULL)) {
            return null;
        }
        return TypeRegistry.entry(type).wrapper().apply(pointer);
    }

    @Override
    public String toString() {
        MemorySegment p = pointer;
        return getClass().getSimpleName() + "@0x"
            + (p == null || p.equals(MemorySegment.NULL) ? "released" : Long.toHexString(p.address()));
    }
}
