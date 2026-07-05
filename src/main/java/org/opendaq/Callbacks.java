package org.opendaq;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.opendaq.lowlevel.Ffi;

/**
 * Upcall stubs turning Java lambdas into the C callback pointers openDAQ
 * expects ({@code daqEventCall}, {@code daqProcCall}, {@code daqFuncCall}).
 *
 * <p>The callback types carry no user-data argument, so each distinct Java
 * callback gets its own native entry point (an FFM upcall stub bound to the
 * lambda).  openDAQ has no destruction hook for callables, so stubs live in
 * the global arena for the rest of the session — mirroring the reference
 * bindings, where each closure-backed callable pins its trampoline for the
 * life of the image.
 *
 * <p>No exception may unwind across the C boundary: a throw inside a callback
 * is caught and reported as {@code OPENDAQ_ERR_GENERALERROR} (or swallowed
 * for the void event callback).  openDAQ may invoke callbacks from its own
 * worker threads; FFM upcall stubs attach those threads automatically.
 */
final class Callbacks {

    private Callbacks() {}

    static final int OPENDAQ_SUCCESS = 0;
    static final int OPENDAQ_ERR_GENERALERROR = 0x80000014;

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static final FunctionDescriptor EVENT_DESCRIPTOR =
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    private static final FunctionDescriptor PROCEDURE_DESCRIPTOR =
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
    private static final FunctionDescriptor FUNCTION_DESCRIPTOR =
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

    private static final MethodHandle EVENT_BRIDGE;
    private static final MethodHandle PROCEDURE_BRIDGE;
    private static final MethodHandle FUNCTION_BRIDGE;

    static {
        try {
            EVENT_BRIDGE = LOOKUP.findStatic(Callbacks.class, "eventBridge",
                MethodType.methodType(void.class, EventCallback.class,
                                      MemorySegment.class, MemorySegment.class));
            PROCEDURE_BRIDGE = LOOKUP.findStatic(Callbacks.class, "procedureBridge",
                MethodType.methodType(int.class, ProcedureCallback.class, MemorySegment.class));
            FUNCTION_BRIDGE = LOOKUP.findStatic(Callbacks.class, "functionBridge",
                MethodType.methodType(int.class, FunctionCallback.class,
                                      MemorySegment.class, MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** A {@code daqEventCall} function pointer invoking {@code callback}. */
    static MemorySegment eventStub(EventCallback callback) {
        MethodHandle bound = MethodHandles.insertArguments(EVENT_BRIDGE, 0, callback);
        return Ffi.linker().upcallStub(bound, EVENT_DESCRIPTOR, Arena.global());
    }

    /** A {@code daqProcCall} function pointer invoking {@code callback}. */
    static MemorySegment procedureStub(ProcedureCallback callback) {
        MethodHandle bound = MethodHandles.insertArguments(PROCEDURE_BRIDGE, 0, callback);
        return Ffi.linker().upcallStub(bound, PROCEDURE_DESCRIPTOR, Arena.global());
    }

    /** A {@code daqFuncCall} function pointer invoking {@code callback}. */
    static MemorySegment functionStub(FunctionCallback callback) {
        MethodHandle bound = MethodHandles.insertArguments(FUNCTION_BRIDGE, 0, callback);
        return Ffi.linker().upcallStub(bound, FUNCTION_DESCRIPTOR, Arena.global());
    }

    // ------------------------------------------------------------------
    // Bridges (the static methods the upcall stubs invoke)
    // ------------------------------------------------------------------

    private static void eventBridge(EventCallback callback, MemorySegment sender, MemorySegment args) {
        try {
            // The C event dispatcher add-refs sender and args before invoking
            // us, so the wrappers own one reference each.
            callback.handle(wrapOrNull(sender), wrapOrNull(args));
        } catch (Throwable t) {
            // The event callback returns void: nothing to report the error
            // through, but it must not unwind into native frames.
        }
    }

    private static int procedureBridge(ProcedureCallback callback, MemorySegment params) {
        try {
            callback.invoke(decodeParams(params));
            return OPENDAQ_SUCCESS;
        } catch (Throwable t) {
            return OPENDAQ_ERR_GENERALERROR;
        }
    }

    private static int functionBridge(FunctionCallback callback, MemorySegment params, MemorySegment result) {
        try {
            Object value = callback.invoke(decodeParams(params));
            MemorySegment slot = result.reinterpret(ValueLayout.ADDRESS.byteSize());
            slot.set(ValueLayout.ADDRESS, 0, boxTransferringReference(value));
            return OPENDAQ_SUCCESS;
        } catch (Throwable t) {
            return OPENDAQ_ERR_GENERALERROR;
        }
    }

    private static DaqObject wrapOrNull(MemorySegment pointer) {
        return DaqObject.wrap(pointer, BaseObject.class);
    }

    /**
     * Decode the borrowed {@code params} a callable implementation receives
     * into Java arguments: null means no arguments, a daq list one argument
     * per element, anything else the single argument — each converted by
     * unboxing rules (scalars and containers to natural Java values, anything
     * else left wrapped).
     */
    static Object[] decodeParams(MemorySegment params) {
        if (params == null || params.equals(MemorySegment.NULL)) {
            return new Object[0];
        }
        // params is borrowed: add the reference the unboxing consumes (or the
        // adopted wrapper's cleaner releases).
        Ffi.addRef(params);
        Object decoded = Box.unboxPointerOwned(params);
        if (decoded instanceof List<?> list) {
            return list.toArray();
        }
        return new Object[] { decoded };
    }

    /**
     * Encode Java arguments as the params object an openDAQ callable expects
     * (an owned reference, or NULL): none for no arguments, the single boxed
     * value for one, a daq list for several.
     */
    static MemorySegment encodeParamsOwned(Object... params) {
        if (params == null || params.length == 0) {
            return MemorySegment.NULL;
        }
        if (params.length == 1) {
            // A sole null must still be boxed (to Boolean false): left bare it
            // would read as the no-arguments encoding.
            if (params[0] == null) {
                return org.opendaq.lowlevel.DaqBoolean.createBoolObject(false);
            }
            Box.Boxed boxed = Box.box(params[0]);
            if (boxed.owned()) {
                return boxed.segment();
            }
            Ffi.addRef(boxed.segment());
            return boxed.segment();
        }
        List<Object> list = new ArrayList<>(params.length);
        for (Object param : params) {
            list.add(param);
        }
        return Box.boxListOwned(list);
    }

    /**
     * Box the value a Java-implemented function returned into an owned daq
     * object pointer whose single reference is transferred to the native
     * caller.
     */
    static MemorySegment boxTransferringReference(Object value) {
        if (value == null) {
            return MemorySegment.NULL;
        }
        if (value instanceof DaqObject o) {
            MemorySegment raw = o.requireLivePointer();
            Ffi.addRef(raw);
            return raw;
        }
        if (value instanceof List<?> list) {
            return Box.boxListOwned(list);
        }
        if (value instanceof Map<?, ?> map) {
            return Box.boxDictOwned(map);
        }
        Box.Boxed boxed = Box.box(value);
        if (boxed.owned()) {
            return boxed.segment();
        }
        Ffi.addRef(boxed.segment());
        return boxed.segment();
    }
}
