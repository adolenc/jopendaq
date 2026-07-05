package com.opendaq;

/**
 * A Java implementation of an openDAQ Function.  Invoked with the decoded
 * call parameters (see {@link ProcedureCallback}); the return value is boxed
 * back for the native caller — a scalar, {@code java.util.List},
 * {@code java.util.Map}, a {@link DaqObject} wrapper, or null.  An exception
 * thrown inside the callback is reported to the native caller as an openDAQ
 * error rather than unwinding across the C boundary.
 */
@FunctionalInterface
public interface FunctionCallback {
    Object invoke(Object... params) throws Exception;
}
