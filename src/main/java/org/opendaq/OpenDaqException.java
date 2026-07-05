package org.opendaq;

import org.opendaq.lowlevel.ErrorCodes;

/**
 * Thrown when an openDAQ C call returns a failure {@code daqErrCode}.
 *
 * <p>Carries the raw 32-bit status code, the name of the C function that
 * failed, and, when available, the descriptive error message retrieved from
 * the openDAQ error-info API before it is overwritten by the next failing
 * call.
 */
public class OpenDaqException extends RuntimeException {

    private final int errorCode;
    private final String operation;
    private final String errorMessage;

    public OpenDaqException(int errorCode, String operation, String errorMessage) {
        super(formatMessage(errorCode, operation, errorMessage));
        this.errorCode = errorCode;
        this.operation = operation;
        this.errorMessage = errorMessage;
    }

    private static String formatMessage(int errorCode, String operation, String errorMessage) {
        String name = ErrorCodes.name(errorCode);
        StringBuilder sb = new StringBuilder();
        sb.append("openDAQ call ").append(operation)
          .append(" failed with ").append(name != null ? name : "an unknown error")
          .append(" (0x").append(String.format("%08X", errorCode)).append(")");
        if (errorMessage != null && !errorMessage.isEmpty()) {
            sb.append(". ").append(errorMessage);
        }
        return sb.toString();
    }

    /** The raw 32-bit openDAQ status code (high bit set). */
    public int errorCode() {
        return errorCode;
    }

    /** The upstream symbolic name of the code, e.g. "OPENDAQ_ERR_NOTFOUND", or null. */
    public String errorName() {
        return ErrorCodes.name(errorCode);
    }

    /** The C function whose call failed, e.g. "daqDevice_addDevice". */
    public String operation() {
        return operation;
    }

    /** The descriptive message from the openDAQ error-info API, or null. */
    public String errorMessage() {
        return errorMessage;
    }
}
