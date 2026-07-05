package org.opendaq;

import java.lang.foreign.MemorySegment;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.opendaq.lowlevel.DaqBaseObject;
import org.opendaq.lowlevel.DaqRatio;
import org.opendaq.lowlevel.Ffi;

import static org.junit.jupiter.api.Assertions.*;

/**
 * High-level core types coverage: boxed primitives, boxing/unboxing,
 * asType/isA casts, containers, type manager, events, and Java-lambda-backed
 * callables — ported from the reference bindings' coretypes suite.
 */
class CoreTypesTest {

    @Test
    void primitives() {
        BaseObject baseObject = DaqObject.wrap(DaqBaseObject.create(), BaseObject.class);
        assertNotNull(baseObject);

        Ratio wrappedRatio = DaqObject.wrap(DaqRatio.createRatio(8, 12), Ratio.class);
        assertEquals(8, wrappedRatio.getNumerator());
        assertEquals(12, wrappedRatio.getDenominator());

        Ratio ratio = new Ratio(6, 9);
        assertEquals(6, ratio.getNumerator());
        assertEquals(9, ratio.getDenominator());
        Ratio simplified = ratio.simplify();
        assertEquals(2, simplified.getNumerator());
        assertEquals(3, simplified.getDenominator());

        assertFalse(new BooleanObject(false).getValue());
        ComplexNumber complexNumber = new ComplexNumber(1.0, 2.0);
        assertEquals(1.0, complexNumber.getReal());
        assertEquals(2.0, complexNumber.getImaginary());
        assertEquals(1, new IntegerObject(1).getValue());
        assertEquals(1.0, new FloatObject(1.0).getValue());
        assertEquals("test", new StringObject("test").unbox());

        SimpleType simpleType = new SimpleType(CoreType.INT);
        assertNotEquals(MemorySegment.NULL, simpleType.rawPointer());

        VersionInfo versionInfo = new VersionInfo(1, 2, 3);
        assertEquals(1, versionInfo.getMajor());
        assertEquals(2, versionInfo.getMinor());
        assertEquals(3, versionInfo.getPatch());

        assertEquals(16, new BinaryData(16).getSize());
    }

    @Test
    void ratioAndComplexBoxing() {
        ObjectList list = new ObjectList();
        list.pushBack(new RatioValue(2, 3));
        assertEquals(new RatioValue(2, 3), Daq.unbox(list.popFront().unbox()));

        ObjectList ratios = new ObjectList();
        ratios.pushBack(new RatioValue(1, 4));
        ratios.pushBack(new RatioValue(3, 8));
        assertEquals(List.of(new RatioValue(1, 4), new RatioValue(3, 8)), ratios.unbox());

        ObjectList complexes = new ObjectList();
        complexes.pushBack(new ComplexValue(3.0, -4.0));
        assertEquals(List.of(new ComplexValue(3.0, -4.0)), complexes.unbox());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unboxScalarsAndContainers() {
        assertEquals(42L, new IntegerObject(42).unbox());
        assertEquals(1.5, new FloatObject(1.5).unbox());
        assertEquals(false, new BooleanObject(false).unbox());
        assertEquals(new RatioValue(1, 2), new Ratio(1, 2).unbox());
        assertEquals("hello", DaqObject.wrap(Ffi.createDaqString("hello"), BaseObject.class).unbox());
        // unbox is idempotent on already-native values.
        assertEquals(42, Daq.unbox(42));
        // An object with no natural Java form is rejected.
        assertThrows(IllegalArgumentException.class, () -> new PropertyObject().unbox());

        ObjectList list = new ObjectList();
        list.pushBack(1);
        list.pushBack("two");
        assertEquals(List.of(1L, "two"), list.unbox());
        assertEquals(List.of(), new ObjectList().unbox());

        Dict dict = new Dict();
        dict.set("x", 10);
        dict.set("y", "twenty");
        Map<Object, Object> table = (Map<Object, Object>) dict.unbox();
        assertEquals(2, table.size());
        assertEquals(10L, table.get("x"));
        assertEquals("twenty", table.get("y"));

        // Nested containers unbox recursively.
        ObjectList outer = new ObjectList();
        ObjectList inner = new ObjectList();
        inner.pushBack(7);
        outer.pushBack(inner);
        assertEquals(List.of(List.of(7L)), outer.unbox());

        // An element with no Java form stays an openDAQ wrapper.
        ObjectList mixed = new ObjectList();
        mixed.pushBack(new PropertyObject());
        Object element = ((List<Object>) mixed.unbox()).get(0);
        assertInstanceOf(DaqObject.class, element);
        assertTrue(((DaqObject) element).isA(PropertyObject.class));
    }

    @Test
    void asTypeCastAndFailure() {
        // asType is a real interface query: casting to an unsupported
        // interface throws instead of handing back a broken wrapper.
        IntegerObject boxed = new IntegerObject(42);
        assertThrows(OpenDaqException.class, () -> boxed.asType(StringObject.class));
        assertEquals(42L, boxed.unbox());
        // INumber sits at a different vtable offset than IInteger, so the
        // queried wrapper must read correctly through its own pointer.
        assertEquals(42.0, boxed.asType(NumberObject.class).getFloatValue());
        assertTrue(boxed.isA(NumberObject.class));
        assertFalse(boxed.isA(StringObject.class));

        IntegerObject released = new IntegerObject(1);
        released.release();
        assertThrows(IllegalStateException.class, () -> released.asType(NumberObject.class));
        Ffi.clearErrorInfo();
    }

    @Test
    void collections() {
        ObjectList list = new ObjectList();
        list.pushBack(1);
        list.pushBack(2);
        list.pushBack(3);
        assertEquals(3, list.getCount());
        assertEquals(1L, list.popFront().unbox());
        assertEquals(3L, list.removeAt(1).unbox());
        list.clear();
        assertEquals(0, list.getCount());

        Dict dict = new Dict();
        dict.set("key", "value");
        assertEquals(1, dict.getCount());
        assertEquals("value", dict.get("key").unbox());
    }

    @Test
    void enumerationAndStructs() {
        EnumerationType enumerationType = EnumerationType.createEnumerationTypeWithValues(
            "MyEnum", Map.of("One", 1, "Two", 2));
        Enumeration enumeration = Enumeration.createEnumerationWithType(enumerationType, "Two");
        assertEquals(2, enumerationType.getCount());
        assertEquals(2, enumeration.getIntValue());

        TypeManager typeManager = new TypeManager();
        StructType structType = StructType.createStructTypeNoDefaults(
            "test", List.of("int"), List.of(new SimpleType(CoreType.INT)));
        typeManager.addType(structType);
        assertTrue(typeManager.hasType("test"));
        assertEquals("test", typeManager.getType("test").getName());

        StructBuilder structBuilder = new StructBuilder("test", typeManager);
        structBuilder.set("int", 10);
        Struct struct = structBuilder.build();
        assertEquals(10L, struct.get("int").unbox());

        typeManager.removeType("test");
        assertFalse(typeManager.hasType("test"));
    }

    @Test
    void eventsWithGeneratedHandler() {
        Event event = new Event();
        EventArgs eventArgs = new EventArgs(10, "test_event");
        boolean[] called = {false};
        EventHandler handler = new EventHandler((sender, args) -> called[0] = true);
        BaseObject sender = DaqObject.wrap(DaqBaseObject.create(), BaseObject.class);

        assertEquals(0, event.getSubscriberCount());
        assertEquals(10, eventArgs.getEventId());
        assertEquals("test_event", eventArgs.getEventName());
        event.addHandler(handler);
        assertEquals(1, event.getSubscriberCount());
        handler.handleEvent(sender, eventArgs);
        assertTrue(called[0]);
    }

    @Test
    void eventHandlerFromLambda() {
        // A Java lambda goes straight to addHandler: sender and args arrive
        // wrapped and their references are managed for you.
        Event event = new Event();
        String[] captured = {null};
        EventHandler handler = event.addHandler((sender, args) ->
            captured[0] = args.asType(EventArgs.class).getEventName());
        BaseObject sender = DaqObject.wrap(DaqBaseObject.create(), BaseObject.class);
        EventArgs eventArgs = new EventArgs(42, "fn_event");

        assertEquals(1, event.getSubscriberCount());
        handler.handleEvent(sender, eventArgs);
        assertEquals("fn_event", captured[0]);
    }

    @Test
    void eventHandlerRouting() {
        // Distinct lambda handlers must get distinct upcall stubs routing to
        // their own callbacks, and removal must not disturb the others.
        Event event = new Event();
        boolean[] a = {false};
        boolean[] b = {false};
        EventHandler handlerA = event.addHandler((s, args) -> a[0] = true);
        event.addHandler((s, args) -> b[0] = true);
        BaseObject sender = DaqObject.wrap(DaqBaseObject.create(), BaseObject.class);
        EventArgs eventArgs = new EventArgs(1, "e");

        assertEquals(2, event.getSubscriberCount());
        handlerA.handleEvent(sender, eventArgs);
        assertTrue(a[0]);
        assertFalse(b[0]);

        event.removeHandler(handlerA);
        a[0] = false;
        EventHandler handlerC = event.addHandler((s, args) -> a[0] = true);
        handlerC.handleEvent(sender, eventArgs);
        assertTrue(a[0]);
    }

    @Test
    void procedureFromLambda() {
        List<Object> seen = new ArrayList<>();
        Procedure procedure = new Procedure(params -> seen.add(params[0]));
        procedure.invoke(5);
        procedure.invoke("hello");
        assertEquals(List.of(5L, "hello"), seen);
    }

    @Test
    void callableParamsConventions() {
        // openDAQ encodes callable params as null / single value / list of
        // values; each must decode to the natural Java argument list.
        List<Object[]> calls = new ArrayList<>();
        Procedure procedure = new Procedure(params -> calls.add(params));
        procedure.invoke();
        procedure.invoke(1, "two");
        assertEquals(2, calls.size());
        assertEquals(0, calls.get(0).length);
        assertArrayEquals(new Object[] {1L, "two"}, calls.get(1));
    }

    @Test
    void functionFromLambda() {
        FunctionObject sum = new FunctionObject(params -> (Long) params[0] + (Long) params[1]);
        assertEquals(5L, sum.call(2, 3));

        // A Java list result boxes into a daq list.
        FunctionObject listResult = new FunctionObject(params -> List.of(1, 2, 3));
        assertEquals(List.of(1L, 2L, 3L), listResult.call());

        // A param with no scalar Java form arrives wrapped, for the callback
        // to asType-cast.
        FunctionObject reader = new FunctionObject(params ->
            ((DaqObject) params[0]).asType(EventArgs.class).getEventId());
        assertEquals(7L, reader.call(new EventArgs(7, "x")));
    }

    @Test
    void callableErrorPropagation() {
        // An exception thrown inside the callback must not unwind across the
        // C boundary: it is reported as an error code, which the call site
        // surfaces as an OpenDaqException.
        Procedure procedure = new Procedure(params -> {
            throw new RuntimeException("boom");
        });
        FunctionObject function = new FunctionObject(params -> {
            throw new RuntimeException("boom");
        });
        assertThrows(OpenDaqException.class, procedure::invoke);
        assertThrows(OpenDaqException.class, function::call);
        Ffi.clearErrorInfo();
    }

    @Test
    void explicitRelease() {
        StringObject string = new StringObject("bye");
        string.release();
        assertEquals(MemorySegment.NULL, string.rawPointer());
        assertThrows(IllegalStateException.class, string::getLength);
        // release is idempotent, and close() is an alias.
        string.release();
        string.close();
    }

    @Test
    void automaticReleaseOnGc() throws InterruptedException {
        // The wrapper's Cleaner must release exactly the reference it owns
        // once the wrapper is unreachable.
        MemorySegment pointer = DaqRatio.createRatio(14, 21);
        Ffi.addRef(pointer);   // our probe reference; the wrapper owns the other
        WeakReference<Ratio> weak = new WeakReference<>(DaqObject.wrap(pointer, Ratio.class));
        for (int i = 0; i < 200 && (weak.get() != null || probeRefCount(pointer) != 1); i++) {
            System.gc();
            Thread.sleep(50);
        }
        assertNull(weak.get(), "the wrapper object should be reclaimable");
        assertEquals(1, probeRefCount(pointer), "the cleaner should have released the wrapper's reference");
        Ffi.releaseRef(pointer);
    }

    private static int probeRefCount(MemorySegment pointer) {
        int count = Ffi.addRef(pointer);
        Ffi.releaseRef(pointer);
        return count - 1;
    }
}
