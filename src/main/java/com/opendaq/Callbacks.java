package com.opendaq;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.opendaq.lowlevel.DaqEventHandler;
import com.opendaq.lowlevel.DaqFunction;
import com.opendaq.lowlevel.DaqProcedure;
import com.opendaq.lowlevel.Ffi;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Java lambdas as openDAQ callbacks, through the C bindings' {@code ...WithData}
 * factories.
 *
 * <p>Those factories take one C callback per signature, a user-data pointer
 * passed to every call, and a release function openDAQ calls with that
 * pointer once the object is destroyed.  So each signature has a single
 * upcall stub, the user data is the id under which the Java callback is
 * registered, and the release function unregisters it: a callback lives
 * exactly as long as the native handler, procedure or function.
 *
 * <p>No exception may unwind across the C boundary: a throw inside a callback
 * is caught and reported as {@code OPENDAQ_ERR_GENERALERROR} (or swallowed
 * for the void event callback).  openDAQ may invoke and release callbacks
 * from its own worker threads; FFM upcall stubs attach those threads
 * automatically, and the registry is concurrent.
 */
final class Callbacks {

    private Callbacks() {}

    static final int OPENDAQ_SUCCESS = 0;
    static final int OPENDAQ_ERR_GENERALERROR = 0x80000014;

    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final Map<Long, Object> REGISTRY = new ConcurrentHashMap<>();

    private static final MemorySegment EVENT_STUB;
    private static final MemorySegment PROCEDURE_STUB;
    private static final MemorySegment FUNCTION_STUB;
    private static final MemorySegment FREE_STUB;

    static {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            EVENT_STUB = stub(lookup, "eventBridge",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
            PROCEDURE_STUB = stub(lookup, "procedureBridge",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            FUNCTION_STUB = stub(lookup, "functionBridge",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            FREE_STUB = stub(lookup, "freeBridge", FunctionDescriptor.ofVoid(ADDRESS));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MemorySegment stub(MethodHandles.Lookup lookup, String name, FunctionDescriptor descriptor)
            throws ReflectiveOperationException {
        MethodHandle bridge = lookup.findStatic(Callbacks.class, name, descriptor.toMethodType());
        return Ffi.linker().upcallStub(bridge, descriptor, Arena.global());
    }

    /** The user data under which {@code callback} is registered until openDAQ releases it. */
    private static MemorySegment register(Object callback) {
        long id = NEXT_ID.incrementAndGet();
        REGISTRY.put(id, callback);
        return MemorySegment.ofAddress(id);
    }

    private static <T> T registered(MemorySegment userData, Class<T> type) {
        return type.cast(REGISTRY.get(userData.address()));
    }

    /** A new openDAQ event handler (an owned reference) invoking {@code callback}. */
    static MemorySegment createEventHandler(EventCallback callback) {
        return DaqEventHandler.createEventHandlerWithData(EVENT_STUB, register(callback), FREE_STUB);
    }

    /** A new openDAQ procedure (an owned reference) invoking {@code callback}. */
    static MemorySegment createProcedure(ProcedureCallback callback) {
        return DaqProcedure.createProcedureWithData(PROCEDURE_STUB, register(callback), FREE_STUB);
    }

    /** A new openDAQ function (an owned reference) invoking {@code callback}. */
    static MemorySegment createFunction(FunctionCallback callback) {
        return DaqFunction.createFunctionWithData(FUNCTION_STUB, register(callback), FREE_STUB);
    }

    /** Whether {@code callback} backs a native object that is still alive. */
    static boolean isRegistered(Object callback) {
        return REGISTRY.containsValue(callback);
    }

    // ------------------------------------------------------------------
    // Bridges (the static methods the upcall stubs invoke)
    // ------------------------------------------------------------------

    private static void freeBridge(MemorySegment userData) {
        REGISTRY.remove(userData.address());
    }

    private static void eventBridge(MemorySegment sender, MemorySegment args, MemorySegment userData) {
        try {
            // The C event dispatcher add-refs sender and args before invoking
            // us, so the wrappers own one reference each.
            DaqObject wrappedSender = wrapOrNull(sender);
            DaqObject wrappedArgs = wrapOrNull(args);
            registered(userData, EventCallback.class).handle(wrappedSender, wrappedArgs);
        } catch (Throwable t) {
            // The event callback returns void: nothing to report the error
            // through, but it must not unwind into native frames.
        }
    }

    private static int procedureBridge(MemorySegment params, MemorySegment userData) {
        try {
            registered(userData, ProcedureCallback.class).invoke(decodeParams(params));
            return OPENDAQ_SUCCESS;
        } catch (Throwable t) {
            return OPENDAQ_ERR_GENERALERROR;
        }
    }

    private static int functionBridge(MemorySegment params, MemorySegment result, MemorySegment userData) {
        try {
            Object value = registered(userData, FunctionCallback.class).invoke(decodeParams(params));
            MemorySegment slot = result.reinterpret(ADDRESS.byteSize());
            slot.set(ADDRESS, 0, boxTransferringReference(value));
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
                return com.opendaq.lowlevel.DaqBoolean.createBoolObject(false);
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
