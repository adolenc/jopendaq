package org.opendaq;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.opendaq.lowlevel.DaqDict;
import org.opendaq.lowlevel.DaqList;
import org.opendaq.lowlevel.Ffi;

/**
 * Conversion helpers the generated high-level bindings call to move openDAQ
 * containers and strings across into natural Java values.
 */
final class Wrap {

    private Wrap() {}

    /** An owned daqString for a Java string, or NULL for null. */
    static MemorySegment daqStringOrNull(String value) {
        return value == null ? MemorySegment.NULL : Ffi.createDaqString(value);
    }

    /**
     * Cast an <em>owned</em> raw pointer to a {@code type} wrapper, consuming
     * the reference: queried to the target interface when the C API exposes
     * its id (the pointer for one interface is not vtable-compatible with
     * another), adopted directly otherwise.  Null for NULL.
     */
    static <T extends DaqObject> T castOwned(MemorySegment owned, Class<T> type) {
        if (owned == null || owned.equals(MemorySegment.NULL)) {
            return null;
        }
        TypeRegistry.Entry<T> entry = TypeRegistry.entry(type);
        if (entry.interfaceIdWriter() == null) {
            return entry.wrapper().apply(owned);
        }
        try {
            MemorySegment queried = TypeRegistry.queryInterface(owned, entry.interfaceIdWriter());
            return entry.wrapper().apply(queried);
        } finally {
            Ffi.releaseRef(owned);
        }
    }

    /**
     * Convert an owned daqList pointer into a Java list, consuming the
     * reference.  Each element arrives as an owned pointer and is converted
     * (and consumed) by {@code element}.  A NULL list (e.g. a getter that
     * returns null for the empty case) is the empty list.
     */
    static <T> List<T> list(MemorySegment ownedList, Function<MemorySegment, T> element) {
        if (ownedList == null || ownedList.equals(MemorySegment.NULL)) {
            return new ArrayList<>();
        }
        try {
            long count = DaqList.getCount(ownedList);
            List<T> result = new ArrayList<>((int) count);
            for (long i = 0; i < count; i++) {
                result.add(element.apply(DaqList.getItemAt(ownedList, i)));
            }
            return result;
        } finally {
            Ffi.releaseRef(ownedList);
        }
    }

    /**
     * Convert an owned daqDict pointer into a Java map (insertion-ordered),
     * consuming the reference.  Keys and values arrive as owned pointers and
     * are converted (and consumed) by {@code key} / {@code value}; a null
     * dict is the empty map and a null stored value maps to null.
     */
    static <K, V> Map<K, V> map(MemorySegment ownedDict,
                                Function<MemorySegment, K> key,
                                Function<MemorySegment, V> value) {
        if (ownedDict == null || ownedDict.equals(MemorySegment.NULL)) {
            return new LinkedHashMap<>();
        }
        try {
            MemorySegment keyList = DaqDict.getKeyList(ownedDict);
            try {
                long count = DaqList.getCount(keyList);
                Map<K, V> result = new LinkedHashMap<>((int) count * 2);
                for (long i = 0; i < count; i++) {
                    MemorySegment keyPointer = DaqList.getItemAt(keyList, i);
                    MemorySegment valuePointer;
                    try {
                        valuePointer = DaqDict.get(ownedDict, keyPointer);
                    } catch (RuntimeException e) {
                        Ffi.releaseRef(keyPointer);
                        throw e;
                    }
                    // Convert the value first: key conversion consumes the key
                    // pointer the dict lookup borrowed from us.
                    V converted = value.apply(valuePointer);
                    result.put(key.apply(keyPointer), converted);
                }
                return result;
            } finally {
                Ffi.releaseRef(keyList);
            }
        } finally {
            Ffi.releaseRef(ownedDict);
        }
    }

    /** Unbox an owned pointer into its natural Java value (see {@link Box}). */
    static Object unboxOwned(MemorySegment owned) {
        return Box.unboxPointerOwned(owned);
    }
}
