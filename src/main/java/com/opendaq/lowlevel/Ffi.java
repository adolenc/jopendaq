package com.opendaq.lowlevel;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import com.opendaq.OpenDaqException;

/**
 * FFI plumbing shared by the generated low-level bindings: the native linker,
 * symbol lookup, error checking, and the couple of C helpers the bindings
 * themselves are built from.
 */
public final class Ffi {

    private Ffi() {}

    /**
     * Layout of the by-value 16-byte {@code daqIntfID} GUID
     * (uint32, uint16, uint16, uint64 — no padding).
     */
    public static final MemoryLayout INTF_ID = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("Data1"),
        ValueLayout.JAVA_SHORT.withName("Data2"),
        ValueLayout.JAVA_SHORT.withName("Data3"),
        ValueLayout.JAVA_LONG.withName("Data4"));

    private static final Linker LINKER = Linker.nativeLinker();

    /** Create a downcall handle for the named C function, loading the natives first. */
    public static MethodHandle downcall(String symbol, FunctionDescriptor descriptor) {
        SymbolLookup lookup = NativeLoader.ensureLoaded();
        MemorySegment address = lookup.find(symbol).orElseThrow(
            () -> new IllegalStateException("openDAQ native symbol not found: " + symbol));
        return LINKER.downcallHandle(address, descriptor);
    }

    /**
     * Like {@link #downcall}, but a missing symbol yields null instead of
     * throwing.  The generated bindings use this so a symbol another platform
     * exports (e.g. the Windows-only debug logger sink) fails at call time
     * with a clear error, not when its whole class initializes.
     */
    public static MethodHandle downcallOrNull(String symbol, FunctionDescriptor descriptor) {
        SymbolLookup lookup = NativeLoader.ensureLoaded();
        return lookup.find(symbol)
            .map(address -> LINKER.downcallHandle(address, descriptor))
            .orElse(null);
    }

    /** The handle, or a clear error when its symbol is missing on this platform. */
    public static MethodHandle require(MethodHandle handle, String symbol) {
        if (handle == null) {
            throw new IllegalStateException(
                "The openDAQ native libraries on this platform do not export " + symbol + ".");
        }
        return handle;
    }

    /** The native linker, for upcall stub creation in the callback runtime. */
    public static Linker linker() {
        return LINKER;
    }

    // ------------------------------------------------------------------
    // Error handling
    // ------------------------------------------------------------------

    /** True when the {@code daqErrCode} has its failure (high) bit set. */
    public static boolean isFailure(int errCode) {
        return (errCode & 0x80000000) != 0;
    }

    /**
     * Throw an {@link OpenDaqException} when {@code errCode} is a failure,
     * attaching the descriptive message from the openDAQ error-info API.
     */
    public static void checkError(int errCode, String operation) {
        if (isFailure(errCode)) {
            throw new OpenDaqException(errCode, operation, errorInfoMessage());
        }
    }

    private static final class ErrorInfoHandles {
        // daqErrCode daqGetErrorInfoMessage(daqString** errorMessage);
        static final MethodHandle GET_MESSAGE = downcall("daqGetErrorInfoMessage",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        // void daqClearErrorInfo(void);
        static final MethodHandle CLEAR = downcall("daqClearErrorInfo",
            FunctionDescriptor.ofVoid());
        // daqErrCode daqString_getCharPtr(daqString* self, daqConstCharPtr* value);
        static final MethodHandle STRING_GET_CHAR_PTR = downcall("daqString_getCharPtr",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        // daqErrCode daqString_createString(daqString** obj, daqConstCharPtr str);
        static final MethodHandle STRING_CREATE = downcall("daqString_createString",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        // int daqBaseObject_releaseRef(daqBaseObject* self);
        static final MethodHandle RELEASE_REF = downcall("daqBaseObject_releaseRef",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        // int daqBaseObject_addRef(daqBaseObject* self);
        static final MethodHandle ADD_REF = downcall("daqBaseObject_addRef",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    }

    /**
     * The human-readable message for the calling thread's last openDAQ error,
     * or null when no error information is available.  Uses direct FFI calls
     * to avoid re-entering {@link #checkError} during error reporting.
     */
    public static String errorInfoMessage() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment slot = arena.allocate(ValueLayout.ADDRESS);
            int stored = (int) ErrorInfoHandles.GET_MESSAGE.invokeExact(slot);
            if (!isFailure(stored)) return null;
            MemorySegment message = slot.get(ValueLayout.ADDRESS, 0);
            if (message.equals(MemorySegment.NULL)) return null;
            try {
                MemorySegment charSlot = arena.allocate(ValueLayout.ADDRESS);
                int err = (int) ErrorInfoHandles.STRING_GET_CHAR_PTR.invokeExact(message, charSlot);
                if (isFailure(err)) return null;
                MemorySegment chars = charSlot.get(ValueLayout.ADDRESS, 0);
                if (chars.equals(MemorySegment.NULL)) return null;
                return chars.reinterpret(Long.MAX_VALUE).getString(0);
            } finally {
                int ignored = (int) ErrorInfoHandles.RELEASE_REF.invokeExact(message);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Clear the calling thread's openDAQ error info. */
    public static void clearErrorInfo() {
        try {
            ErrorInfoHandles.CLEAR.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Convert the {@link Throwable} a {@code MethodHandle.invokeExact} declares
     * into an unchecked exception (native calls do not actually throw checked
     * exceptions).
     */
    public static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException e) return e;
        if (t instanceof Error e) throw e;
        return new RuntimeException(t);
    }

    // ------------------------------------------------------------------
    // Strings and reference counting
    // ------------------------------------------------------------------

    /** Create a daqString from a Java string; the caller owns the returned reference. */
    public static MemorySegment createDaqString(String value) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment slot = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment chars = arena.allocateFrom(value);
            int err = (int) ErrorInfoHandles.STRING_CREATE.invokeExact(slot, chars);
            checkError(err, "daqString_createString");
            return slot.get(ValueLayout.ADDRESS, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * The Java string held by a {@code daqString*}; the string object's own
     * reference is left untouched.  Returns null for a null pointer.
     */
    public static String readDaqString(MemorySegment daqString) {
        if (daqString == null || daqString.equals(MemorySegment.NULL)) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment charSlot = arena.allocate(ValueLayout.ADDRESS);
            int err = (int) ErrorInfoHandles.STRING_GET_CHAR_PTR.invokeExact(daqString, charSlot);
            checkError(err, "daqString_getCharPtr");
            MemorySegment chars = charSlot.get(ValueLayout.ADDRESS, 0);
            if (chars.equals(MemorySegment.NULL)) return null;
            return chars.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Read a {@code daqString*}'s Java value and release the reference it carries. */
    public static String readDaqStringAndRelease(MemorySegment daqString) {
        if (daqString == null || daqString.equals(MemorySegment.NULL)) return null;
        try {
            return readDaqString(daqString);
        } finally {
            releaseRef(daqString);
        }
    }

    /** Map a null Java reference onto the C null pointer (generated bindings use this). */
    public static MemorySegment orNull(MemorySegment segment) {
        return segment == null ? MemorySegment.NULL : segment;
    }

    /** Call daqBaseObject_addRef; returns the new reference count. */
    public static int addRef(MemorySegment object) {
        try {
            return (int) ErrorInfoHandles.ADD_REF.invokeExact(object);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Call daqBaseObject_releaseRef (ignoring null); returns the new count or 0. */
    public static int releaseRef(MemorySegment object) {
        if (object == null || object.equals(MemorySegment.NULL)) return 0;
        try {
            return (int) ErrorInfoHandles.RELEASE_REF.invokeExact(object);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }
}
