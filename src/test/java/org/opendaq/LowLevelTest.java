package org.opendaq;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import org.junit.jupiter.api.Test;
import org.opendaq.lowlevel.DaqBaseObject;
import org.opendaq.lowlevel.DaqBoolean;
import org.opendaq.lowlevel.DaqDict;
import org.opendaq.lowlevel.DaqInteger;
import org.opendaq.lowlevel.DaqList;
import org.opendaq.lowlevel.DaqRatio;
import org.opendaq.lowlevel.DaqString;
import org.opendaq.lowlevel.ErrorCodes;
import org.opendaq.lowlevel.Ffi;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for the generated low-level FFI layer and its error machinery,
 * ported from the reference bindings' low-level suites.
 */
class LowLevelTest {

    @Test
    void stringRoundTrip() {
        MemorySegment str = Ffi.createDaqString("Hello, C bindings!");
        assertEquals("Hello, C bindings!", Ffi.readDaqString(str));
        assertEquals(18, DaqString.getLength(str));
        Ffi.releaseRef(str);
    }

    @Test
    void listOperations() {
        MemorySegment list = DaqList.createList();
        MemorySegment str = Ffi.createDaqString("hello");
        DaqList.pushBack(list, str);
        assertEquals(1, DaqList.getCount(list));
        MemorySegment item = DaqList.getItemAt(list, 0);
        assertEquals("hello", Ffi.readDaqString(item));
        Ffi.releaseRef(item);
        Ffi.releaseRef(str);
        Ffi.releaseRef(list);
    }

    @Test
    void dictOperations() {
        MemorySegment dict = DaqDict.createDict();
        MemorySegment key = Ffi.createDaqString("key");
        MemorySegment value = Ffi.createDaqString("value");
        DaqDict.set(dict, key, value);
        assertEquals(1, DaqDict.getCount(dict));
        MemorySegment got = DaqDict.get(dict, key);
        assertEquals("value", Ffi.readDaqString(got));
        Ffi.releaseRef(got);
        Ffi.releaseRef(value);
        Ffi.releaseRef(key);
        Ffi.releaseRef(dict);
    }

    @Test
    void boxedPrimitives() {
        MemorySegment ratio = DaqRatio.createRatio(8, 12);
        assertEquals(8, DaqRatio.getNumerator(ratio));
        assertEquals(12, DaqRatio.getDenominator(ratio));
        Ffi.releaseRef(ratio);

        MemorySegment bool = DaqBoolean.createBoolObject(true);
        assertTrue(DaqBoolean.getValue(bool));
        Ffi.releaseRef(bool);

        MemorySegment integer = DaqInteger.createInteger(42);
        assertEquals(42, DaqInteger.getValue(integer));
        Ffi.releaseRef(integer);
    }

    @Test
    void errorMapping() {
        // Out-of-range access fails with the upstream code, its symbolic name,
        // and the descriptive error-info message.
        MemorySegment list = DaqList.createList();
        OpenDaqException e = assertThrows(OpenDaqException.class, () -> DaqList.getItemAt(list, 99));
        assertEquals(ErrorCodes.OPENDAQ_ERR_OUTOFRANGE, e.errorCode());
        assertEquals("OPENDAQ_ERR_OUTOFRANGE", e.errorName());
        assertEquals("daqList_getItemAt", e.operation());
        assertTrue(e.getMessage().contains("OPENDAQ_ERR_OUTOFRANGE"));
        Ffi.releaseRef(list);
        Ffi.clearErrorInfo();
    }

    @Test
    void failureBitClassification() {
        assertFalse(Ffi.isFailure(0));
        assertFalse(Ffi.isFailure(0x00000002));       // OPENDAQ_IGNORED-style success codes
        assertTrue(Ffi.isFailure(ErrorCodes.OPENDAQ_ERR_NOTFOUND));
        assertTrue(Ffi.isFailure(ErrorCodes.OPENDAQ_ERR_GENERALERROR));
    }

    @Test
    void referenceCountingRoundTrip() {
        MemorySegment obj = DaqBaseObject.create();
        assertEquals(2, Ffi.addRef(obj));
        assertEquals(1, Ffi.releaseRef(obj));
        assertEquals(0, Ffi.releaseRef(obj));
    }

    @Test
    void interfaceIdAndQueryInterface() {
        // The raw getInterfaceId family writes a 16-byte daqIntfID by pointer;
        // queryInterface takes it by value and hands back an owned pointer.
        MemorySegment integer = DaqInteger.createInteger(7);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment id = arena.allocate(Ffi.INTF_ID);
            DaqInteger.getInterfaceId(id);
            MemorySegment queried = DaqBaseObject.queryInterface(integer, id);
            assertNotEquals(MemorySegment.NULL, queried);
            Ffi.releaseRef(queried);

            // An unsupported interface fails cleanly instead of corrupting.
            DaqString.getInterfaceId(id);
            assertThrows(OpenDaqException.class, () -> DaqBaseObject.queryInterface(integer, id));
        }
        Ffi.releaseRef(integer);
        Ffi.clearErrorInfo();
    }

    @Test
    void inOutParameters() {
        // daqFunction_call carries an in-out result slot; the generated
        // wrapper passes the input value and returns the updated one.
        MemorySegment function = org.opendaq.lowlevel.DaqFunction.createFunction(
            Callbacks.functionStub(params -> 21L));
        MemorySegment result = org.opendaq.lowlevel.DaqFunction.call(
            function, MemorySegment.NULL, MemorySegment.NULL);
        assertEquals(21, DaqInteger.getValue(result));
        Ffi.releaseRef(result);
        Ffi.releaseRef(function);
    }
}
