    // A multi reader reads several signals at once, aligned on a common
    // domain, filling one buffer per signal (the C API takes a void** array
    // of buffer pointers).  We accept the signals as a plain Java list at
    // construction and return one array per signal from read/readWithDomain,
    // so callers never touch the foreign buffers.  IMultiReader exposes no
    // signal-count accessor, so the constructor records it.

    private int constructedSignalCount = -1;

    /** A scaled FLOAT64/INT64 multi reader over {@code signals}. */
    public MultiReader(List<Signal> signals) {
        this(signals, SampleType.FLOAT64, SampleType.INT64, ReadMode.SCALED, ReadTimeoutType.ALL);
    }

    public MultiReader(List<Signal> signals, SampleType valueReadType, SampleType domainReadType,
                       ReadMode mode, ReadTimeoutType timeoutType) {
        super(createFromSignals(signals, valueReadType, domainReadType, mode, timeoutType));
        this.constructedSignalCount = signals.size();
    }

    private static MemorySegment createFromSignals(List<Signal> signals, SampleType valueReadType,
                                                   SampleType domainReadType, ReadMode mode,
                                                   ReadTimeoutType timeoutType) {
        MemorySegment list = Box.boxListOwned(signals);
        try {
            return DaqMultiReader.createMultiReader(list, valueReadType, domainReadType, mode, timeoutType);
        } finally {
            Ffi.releaseRef(list);
        }
    }

    private int signalCount() {
        if (constructedSignalCount < 0) {
            throw new IllegalStateException(
                "cannot determine the signal count of " + this + "; multi-reader read needs a "
                + "reader created with new MultiReader(signals, ...)");
        }
        return constructedSignalCount;
    }

    /** The result of {@link #readWithDomain}: one values / domain array per signal. */
    public record MultiSamples(List<Object> values, List<Object> domain) {

        /** Signal {@code index}'s values as {@code double[]} (default FLOAT64 readers). */
        public double[] doubleValues(int index) {
            return (double[]) values.get(index);
        }

        /** Signal {@code index}'s domain as {@code long[]} ticks (default INT64 readers). */
        public long[] domainTicks(int index) {
            return (long[]) domain.get(index);
        }
    }

    /** Read up to {@code count} aligned samples per signal (FLOAT64 readers). */
    public List<double[]> read(int count, long timeoutMs) {
        if (getValueReadType() != SampleType.FLOAT64) {
            throw new IllegalStateException(
                "read returns double[] per signal and needs the FLOAT64 value read type; use readAny");
        }
        List<double[]> result = new java.util.ArrayList<>();
        for (Object values : readAny(count, timeoutMs)) {
            result.add((double[]) values);
        }
        return result;
    }

    public List<double[]> read(int count) {
        return read(count, 0);
    }

    /** Read up to {@code count} aligned samples per signal, typed by the value read type. */
    public List<Object> readAny(int count, long timeoutMs) {
        SampleType valueType = getValueReadType();
        int signals = signalCount();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment[] buffers = allocateBuffers(arena, valueType, count, signals);
            MemorySegment pointers = pointerArray(arena, buffers);
            DaqMultiReader.ReadResult result =
                DaqMultiReader.read(requireLivePointer(), pointers, count, timeoutMs);
            Ffi.releaseRef(result.status());
            return collect(buffers, valueType, (int) result.count());
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** Like {@code read}, but also reading each signal's aligned domain values. */
    public MultiSamples readWithDomain(int count, long timeoutMs) {
        SampleType valueType = getValueReadType();
        SampleType domainType = getDomainReadType();
        int signals = signalCount();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment[] valueBuffers = allocateBuffers(arena, valueType, count, signals);
            MemorySegment[] domainBuffers = allocateBuffers(arena, domainType, count, signals);
            MemorySegment valuePointers = pointerArray(arena, valueBuffers);
            MemorySegment domainPointers = pointerArray(arena, domainBuffers);
            DaqMultiReader.ReadWithDomainResult result = DaqMultiReader.readWithDomain(
                requireLivePointer(), valuePointers, domainPointers, count, timeoutMs);
            Ffi.releaseRef(result.status());
            int got = (int) result.count();
            return new MultiSamples(collect(valueBuffers, valueType, got),
                                    collect(domainBuffers, domainType, got));
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    private static MemorySegment[] allocateBuffers(Arena arena, SampleType type, int count, int signals) {
        MemorySegment[] buffers = new MemorySegment[signals];
        for (int i = 0; i < signals; i++) {
            buffers[i] = arena.allocate(SampleArrays.byteSize(type, count, 1));
        }
        return buffers;
    }

    private static MemorySegment pointerArray(Arena arena, MemorySegment[] buffers) {
        MemorySegment pointers = arena.allocate(ValueLayout.ADDRESS, buffers.length);
        for (int i = 0; i < buffers.length; i++) {
            pointers.setAtIndex(ValueLayout.ADDRESS, i, buffers[i]);
        }
        return pointers;
    }

    private static List<Object> collect(MemorySegment[] buffers, SampleType type, int count) {
        List<Object> result = new java.util.ArrayList<>(buffers.length);
        for (MemorySegment buffer : buffers) {
            result.add(SampleArrays.toArray(buffer, type, count, 1));
        }
        return result;
    }
