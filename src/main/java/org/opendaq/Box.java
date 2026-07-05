package org.opendaq;

import java.lang.foreign.MemorySegment;
import java.lang.ref.Reference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opendaq.lowlevel.DaqBoolean;
import org.opendaq.lowlevel.DaqComplexNumber;
import org.opendaq.lowlevel.DaqDict;
import org.opendaq.lowlevel.DaqFloatObject;
import org.opendaq.lowlevel.DaqFunction;
import org.opendaq.lowlevel.DaqInteger;
import org.opendaq.lowlevel.DaqList;
import org.opendaq.lowlevel.DaqNumber;
import org.opendaq.lowlevel.DaqProcedure;
import org.opendaq.lowlevel.DaqRatio;
import org.opendaq.lowlevel.DaqString;
import org.opendaq.lowlevel.Ffi;

/**
 * Boxing and unboxing between natural Java values and openDAQ core objects.
 * Boxing (Java in) and unboxing (openDAQ out) are inverses:
 *
 * <pre>
 *   String        &lt;-&gt;  IString
 *   Long (etc.)   &lt;-&gt;  IInteger
 *   Double/Float  &lt;-&gt;  IFloat
 *   Boolean       &lt;-&gt;  IBoolean
 *   RatioValue    &lt;-&gt;  IRatio
 *   ComplexValue  &lt;-&gt;  IComplexNumber
 *   List / Map    &lt;-&gt;  IList / IDict
 *   DaqObject     &lt;-&gt;  its raw pointer
 * </pre>
 */
final class Box {

    private Box() {}

    /** A value boxed for one C call: the raw segment and whether we own a reference to release. */
    record Boxed(MemorySegment segment, boolean owned, Object pin) {}

    /**
     * Box a Java value for a {@code daqBaseObject*} argument.  The result is
     * either borrowed straight from a wrapper (pin it until after the call)
     * or a fresh box carrying one reference the caller must release via
     * {@link #releaseBoxed}.
     */
    static Boxed box(Object value) {
        switch (value) {
            case null -> {
                return new Boxed(MemorySegment.NULL, false, null);
            }
            case DaqObject o -> {
                return new Boxed(o.requireLivePointer(), false, o);
            }
            case MemorySegment s -> {
                return new Boxed(s, false, null);
            }
            case String s -> {
                return new Boxed(Ffi.createDaqString(s), true, null);
            }
            case Boolean b -> {
                return new Boxed(DaqBoolean.createBoolObject(b), true, null);
            }
            case Double d -> {
                return new Boxed(DaqFloatObject.createFloatObject(d), true, null);
            }
            case Float f -> {
                return new Boxed(DaqFloatObject.createFloatObject(f.doubleValue()), true, null);
            }
            case Long l -> {
                return new Boxed(DaqInteger.createInteger(l), true, null);
            }
            case Integer i -> {
                return new Boxed(DaqInteger.createInteger(i.longValue()), true, null);
            }
            case Short s -> {
                return new Boxed(DaqInteger.createInteger(s.longValue()), true, null);
            }
            case Byte b -> {
                return new Boxed(DaqInteger.createInteger(b.longValue()), true, null);
            }
            case RatioValue r -> {
                return new Boxed(DaqRatio.createRatio(r.numerator(), r.denominator()), true, null);
            }
            case ComplexValue c -> {
                return new Boxed(DaqComplexNumber.createComplexNumber(c.real(), c.imaginary()), true, null);
            }
            case List<?> list -> {
                return new Boxed(boxListOwned(list), true, null);
            }
            case Map<?, ?> map -> {
                return new Boxed(boxDictOwned(map), true, null);
            }
            case FunctionCallback fn -> {
                return new Boxed(DaqFunction.createFunction(Callbacks.functionStub(fn)), true, null);
            }
            case ProcedureCallback fn -> {
                return new Boxed(DaqProcedure.createProcedure(Callbacks.procedureStub(fn)), true, null);
            }
            default -> throw new IllegalArgumentException(
                "Cannot box " + value.getClass().getName() + " as an openDAQ object.");
        }
    }

    static void releaseBoxed(Boxed boxed) {
        if (boxed.owned()) {
            Ffi.releaseRef(boxed.segment());
        }
        Reference.reachabilityFence(boxed.pin());
    }

    /** Box a Java list into an owned daqList, each element boxed by its runtime type. */
    static MemorySegment boxListOwned(List<?> list) {
        MemorySegment daqList = DaqList.createList();
        try {
            for (Object element : list) {
                Boxed boxed = box(element);
                try {
                    DaqList.pushBack(daqList, boxed.segment());
                } finally {
                    releaseBoxed(boxed);
                }
            }
            return daqList;
        } catch (RuntimeException e) {
            Ffi.releaseRef(daqList);
            throw e;
        }
    }

    /** Box a Java map into an owned daqDict, keys and values boxed by runtime type. */
    static MemorySegment boxDictOwned(Map<?, ?> map) {
        MemorySegment dict = DaqDict.createDict();
        try {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Boxed key = box(entry.getKey());
                try {
                    Boxed value = box(entry.getValue());
                    try {
                        DaqDict.set(dict, key.segment(), value.segment());
                    } finally {
                        releaseBoxed(value);
                    }
                } finally {
                    releaseBoxed(key);
                }
            }
            return dict;
        } catch (RuntimeException e) {
            Ffi.releaseRef(dict);
            throw e;
        }
    }

    /**
     * Box a Java value as a genuine {@code INumber} pointer (an owned
     * reference).  openDAQ Number parameters need the INumber interface
     * pointer itself: a raw IInteger/IFloat pointer is not
     * interface-compatible and would corrupt the call.
     */
    static MemorySegment boxNumberOwned(Object value) {
        if (value == null) {
            return MemorySegment.NULL;
        }
        if (value instanceof DaqObject o) {
            try {
                return TypeRegistry.queryInterface(o.requireLivePointer(), DaqNumber::getInterfaceId);
            } finally {
                Reference.reachabilityFence(o);
            }
        }
        MemorySegment boxed = switch (value) {
            case Double d -> DaqFloatObject.createFloatObject(d);
            case Float f -> DaqFloatObject.createFloatObject(f.doubleValue());
            case Long l -> DaqInteger.createInteger(l);
            case Integer i -> DaqInteger.createInteger(i.longValue());
            case Short s -> DaqInteger.createInteger(s.longValue());
            case Byte b -> DaqInteger.createInteger(b.longValue());
            default -> throw new IllegalArgumentException(
                "Cannot coerce " + value + " to an openDAQ Number.");
        };
        try {
            return TypeRegistry.queryInterface(boxed, DaqNumber::getInterfaceId);
        } finally {
            Ffi.releaseRef(boxed);
        }
    }

    // ------------------------------------------------------------------
    // Unboxing
    // ------------------------------------------------------------------

    private enum UnboxKind { STRING, INTEGER, FLOAT, BOOLEAN, RATIO, COMPLEX, LIST, DICT }

    private record Probe(UnboxKind kind, MemorySegment borrowed) {}

    /**
     * Probe the object at {@code raw} for an interface with a natural Java
     * form, in the same order as the reference bindings.  The returned
     * pointer is borrowed — valid only while {@code raw}'s owner is alive.
     */
    private static Probe probe(MemorySegment raw) {
        record Candidate(UnboxKind kind, java.util.function.Consumer<MemorySegment> idWriter) {}
        List<Candidate> candidates = List.of(
            new Candidate(UnboxKind.STRING, DaqString::getInterfaceId),
            new Candidate(UnboxKind.INTEGER, DaqInteger::getInterfaceId),
            new Candidate(UnboxKind.FLOAT, DaqFloatObject::getInterfaceId),
            new Candidate(UnboxKind.BOOLEAN, DaqBoolean::getInterfaceId),
            new Candidate(UnboxKind.RATIO, DaqRatio::getInterfaceId),
            new Candidate(UnboxKind.COMPLEX, DaqComplexNumber::getInterfaceId),
            new Candidate(UnboxKind.LIST, DaqList::getInterfaceId),
            new Candidate(UnboxKind.DICT, DaqDict::getInterfaceId));
        for (Candidate candidate : candidates) {
            MemorySegment borrowed = TypeRegistry.borrowInterface(raw, candidate.idWriter());
            if (borrowed != null) {
                return new Probe(candidate.kind(), borrowed);
            }
        }
        return null;
    }

    private static Object readValue(Probe probe) {
        return switch (probe.kind()) {
            case STRING -> Ffi.readDaqString(probe.borrowed());
            case INTEGER -> DaqInteger.getValue(probe.borrowed());
            case FLOAT -> DaqFloatObject.getValue(probe.borrowed());
            case BOOLEAN -> DaqBoolean.getValue(probe.borrowed());
            case RATIO -> new RatioValue(DaqRatio.getNumerator(probe.borrowed()),
                                         DaqRatio.getDenominator(probe.borrowed()));
            case COMPLEX -> new ComplexValue(DaqComplexNumber.getReal(probe.borrowed()),
                                             DaqComplexNumber.getImaginary(probe.borrowed()));
            case LIST -> unboxDaqList(probe.borrowed());
            case DICT -> unboxDaqDict(probe.borrowed());
        };
    }

    private static List<Object> unboxDaqList(MemorySegment listInterface) {
        long count = DaqList.getCount(listInterface);
        List<Object> result = new java.util.ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            result.add(unboxPointerOwned(DaqList.getItemAt(listInterface, i)));
        }
        return result;
    }

    private static Map<Object, Object> unboxDaqDict(MemorySegment dictInterface) {
        MemorySegment keyList = DaqDict.getKeyList(dictInterface);
        try {
            long count = DaqList.getCount(keyList);
            Map<Object, Object> result = new LinkedHashMap<>((int) count * 2);
            for (long i = 0; i < count; i++) {
                MemorySegment keyPointer = DaqList.getItemAt(keyList, i);
                MemorySegment valuePointer;
                try {
                    valuePointer = DaqDict.get(dictInterface, keyPointer);
                } catch (RuntimeException e) {
                    Ffi.releaseRef(keyPointer);
                    throw e;
                }
                Object value = unboxPointerOwned(valuePointer);
                Object key = unboxPointerOwned(keyPointer);
                result.put(key, value);
            }
            return result;
        } finally {
            Ffi.releaseRef(keyList);
        }
    }

    /**
     * Unbox the object at {@code pointer}, a fresh reference this code owns (a
     * list element or dict entry temporary), releasing it once the value is
     * read.  An object with no Java form is instead adopted as a
     * {@code BaseObject} wrapper and returned for the caller to
     * {@link DaqObject#asType}-cast.  A null pointer is null.
     */
    static Object unboxPointerOwned(MemorySegment pointer) {
        if (pointer == null || pointer.equals(MemorySegment.NULL)) {
            return null;
        }
        Probe probe = probe(pointer);
        if (probe == null) {
            return DaqObject.wrap(pointer, BaseObject.class);
        }
        try {
            return readValue(probe);
        } finally {
            Ffi.releaseRef(pointer);
        }
    }

    /** Unbox a wrapped object, leaving the wrapper's own reference intact. */
    static Object unboxObject(DaqObject object) {
        try {
            Probe probe = probe(object.requireLivePointer());
            if (probe == null) {
                throw new IllegalArgumentException(
                    object + " has no natural Java form to unbox; keep it wrapped and asType() it "
                    + "to the openDAQ interface you expect instead.");
            }
            return readValue(probe);
        } finally {
            Reference.reachabilityFence(object);
        }
    }

    /** Unbox any value: wrappers by their runtime type, native Java values unchanged. */
    static Object unboxAny(Object value) {
        return value instanceof DaqObject o ? unboxObject(o) : value;
    }
}
