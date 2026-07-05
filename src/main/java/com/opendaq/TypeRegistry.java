package com.opendaq;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import com.opendaq.lowlevel.DaqBaseObject;
import com.opendaq.lowlevel.Ffi;

/**
 * Maps each wrapper class onto how to construct it from a raw pointer and how
 * to obtain its openDAQ interface id (when the C API exposes one).  Populated
 * by the generated {@code DaqTypes} class.
 */
final class TypeRegistry {

    private TypeRegistry() {}

    record Entry<T extends DaqObject>(
        Class<T> type,
        Function<MemorySegment, T> wrapper,
        Consumer<MemorySegment> interfaceIdWriter) {}

    private static final Map<Class<?>, Entry<?>> ENTRIES = new HashMap<>();

    static {
        DaqTypes.registerAll();
    }

    static <T extends DaqObject> void register(Class<T> type,
                                               Function<MemorySegment, T> wrapper,
                                               Consumer<MemorySegment> interfaceIdWriter) {
        ENTRIES.put(type, new Entry<>(type, wrapper, interfaceIdWriter));
    }

    @SuppressWarnings("unchecked")
    static <T extends DaqObject> Entry<T> entry(Class<T> type) {
        Entry<?> entry = ENTRIES.get(type);
        if (entry == null) {
            throw new IllegalArgumentException(type.getName() + " is not an openDAQ wrapper class.");
        }
        return (Entry<T>) entry;
    }

    /**
     * Query {@code raw} for the interface whose id {@code interfaceIdWriter}
     * writes into a 16-byte daqIntfID buffer.  Adds a reference; the caller
     * owns the returned pointer.  Throws when the interface is unsupported.
     */
    static MemorySegment queryInterface(MemorySegment raw, Consumer<MemorySegment> interfaceIdWriter) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment id = arena.allocate(Ffi.INTF_ID);
            interfaceIdWriter.accept(id);
            return DaqBaseObject.queryInterface(raw, id);
        }
    }

    /**
     * The borrowed interface pointer of {@code raw} for the interface named by
     * {@code interfaceIdWriter}, or null when the object does not implement
     * it.  Carries no reference of its own and is only valid while the owner
     * of {@code raw} is.
     */
    static MemorySegment borrowInterface(MemorySegment raw, Consumer<MemorySegment> interfaceIdWriter) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment id = arena.allocate(Ffi.INTF_ID);
            interfaceIdWriter.accept(id);
            try {
                MemorySegment borrowed = DaqBaseObject.borrowInterface(raw, id);
                return borrowed.equals(MemorySegment.NULL) ? null : borrowed;
            } catch (OpenDaqException e) {
                return null;
            }
        }
    }
}
