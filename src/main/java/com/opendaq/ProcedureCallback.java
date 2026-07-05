package com.opendaq;

/**
 * A Java implementation of an openDAQ Procedure.  Invoked with the decoded
 * call parameters — scalars and containers as natural Java values, anything
 * else as a {@link DaqObject} wrapper.  An exception thrown inside the
 * callback is reported to the native caller as an openDAQ error rather than
 * unwinding across the C boundary.
 */
@FunctionalInterface
public interface ProcedureCallback {
    void invoke(Object... params) throws Exception;
}
