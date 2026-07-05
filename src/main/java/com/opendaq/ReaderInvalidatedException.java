package com.opendaq;

/**
 * Thrown by a reader's read methods when the signal's data descriptor changed
 * to a sample type the reader cannot convert to its read type.  The reader
 * keeps the unread packets; build e.g. a
 * {@code StreamReader.createStreamReaderFromExisting} reader from it (with
 * read types matching the new descriptor) to continue reading.
 */
public class ReaderInvalidatedException extends RuntimeException {

    private final DaqObject reader;

    public ReaderInvalidatedException(DaqObject reader) {
        super("openDAQ reader " + reader + " was invalidated by a descriptor change it cannot "
              + "convert; recover by building a from-existing reader from it with matching read types.");
        this.reader = reader;
    }

    public DaqObject reader() {
        return reader;
    }
}
