    // Constructed through the tail reader builder so skip-events can default
    // to true (the native reader then transparently skips the initial
    // descriptor-change event, so the first read already returns data).

    private Signal constructedFromSignal;

    /** A scaled FLOAT64/INT64 tail reader keeping the last {@code historySize} samples. */
    public TailReader(Signal signal, long historySize) {
        this(signal, historySize, SampleType.FLOAT64, SampleType.INT64, ReadMode.SCALED, true);
    }

    public TailReader(Signal signal, long historySize, SampleType valueReadType,
                      SampleType domainReadType, ReadMode mode, boolean skipEvents) {
        super(buildTailReader(signal, historySize, valueReadType, domainReadType, mode, skipEvents));
        this.constructedFromSignal = signal;
    }

    private static MemorySegment buildTailReader(Signal signal, long historySize,
                                                 SampleType valueReadType, SampleType domainReadType,
                                                 ReadMode mode, boolean skipEvents) {
        MemorySegment builder = DaqTailReaderBuilder.createTailReaderBuilder();
        try {
            DaqTailReaderBuilder.setSignal(builder, signal.requireLivePointer());
            DaqTailReaderBuilder.setHistorySize(builder, historySize);
            DaqTailReaderBuilder.setValueReadType(builder, valueReadType);
            DaqTailReaderBuilder.setDomainReadType(builder, domainReadType);
            DaqTailReaderBuilder.setReadMode(builder, mode);
            DaqTailReaderBuilder.setSkipEvents(builder, skipEvents);
            return DaqTailReaderBuilder.build(builder);
        } finally {
            Ffi.releaseRef(builder);
            Reference.reachabilityFence(signal);
        }
    }

    private int currentWidth() {
        return constructedFromSignal != null
            ? SampleArrays.descriptorWidth(constructedFromSignal.getDescriptor()) : 1;
    }

    /** Read up to the last {@code count} samples as {@code double[]} (FLOAT64 readers). */
    public double[] read(int count) {
        if (getValueReadType() != SampleType.FLOAT64) {
            throw new IllegalStateException(
                "read returns double[] and needs the FLOAT64 value read type; use readAny");
        }
        Object result = readAny(count);
        if (!(result instanceof double[] flat)) {
            throw new IllegalStateException("signal is dimensioned; readAny returns the matrix");
        }
        return flat;
    }

    /** Read up to the last {@code count} samples, typed by the value read type and width. */
    public Object readAny(int count) {
        SampleType valueType = getValueReadType();
        int width = currentWidth();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(SampleArrays.byteSize(valueType, count, width));
            DaqTailReader.ReadResult result = DaqTailReader.read(requireLivePointer(), buffer, count);
            Ffi.releaseRef(result.status());
            return SampleArrays.toArray(buffer, valueType, (int) result.count(), width);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** Like {@code read}, but also reading the parallel domain values. */
    public SamplesWithDomain readWithDomain(int count) {
        SampleType valueType = getValueReadType();
        SampleType domainType = getDomainReadType();
        int width = currentWidth();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment values = arena.allocate(SampleArrays.byteSize(valueType, count, width));
            MemorySegment domain = arena.allocate(SampleArrays.byteSize(domainType, count, 1));
            DaqTailReader.ReadWithDomainResult result =
                DaqTailReader.readWithDomain(requireLivePointer(), values, domain, count);
            Ffi.releaseRef(result.status());
            int got = (int) result.count();
            return new SamplesWithDomain(SampleArrays.toArray(values, valueType, got, width),
                                         SampleArrays.toArray(domain, domainType, got, 1));
        } finally {
            Reference.reachabilityFence(this);
        }
    }
