    // Constructed through the block reader builder so skip-events can default
    // to true (the first read then already returns data).

    /** A scaled FLOAT64/INT64 block reader of {@code blockSize}-sample blocks. */
    public BlockReader(Signal signal, long blockSize) {
        this(signal, blockSize, SampleType.FLOAT64, SampleType.INT64, ReadMode.SCALED, true);
    }

    public BlockReader(Signal signal, long blockSize, SampleType valueReadType,
                       SampleType domainReadType, ReadMode mode, boolean skipEvents) {
        super(buildBlockReader(signal, blockSize, valueReadType, domainReadType, mode, skipEvents));
    }

    private static MemorySegment buildBlockReader(Signal signal, long blockSize,
                                                  SampleType valueReadType, SampleType domainReadType,
                                                  ReadMode mode, boolean skipEvents) {
        MemorySegment builder = DaqBlockReaderBuilder.createBlockReaderBuilder();
        try {
            DaqBlockReaderBuilder.setSignal(builder, signal.requireLivePointer());
            DaqBlockReaderBuilder.setBlockSize(builder, blockSize);
            DaqBlockReaderBuilder.setValueReadType(builder, valueReadType);
            DaqBlockReaderBuilder.setDomainReadType(builder, domainReadType);
            DaqBlockReaderBuilder.setReadMode(builder, mode);
            DaqBlockReaderBuilder.setSkipEvents(builder, skipEvents);
            return DaqBlockReaderBuilder.build(builder);
        } finally {
            Ffi.releaseRef(builder);
            Reference.reachabilityFence(signal);
        }
    }

    /**
     * Read up to {@code count} whole blocks as a (blocks x blockSize) matrix,
     * one row per block (FLOAT64 readers).
     */
    public double[][] read(int count, long timeoutMs) {
        if (getValueReadType() != SampleType.FLOAT64) {
            throw new IllegalStateException(
                "read returns double[][] and needs the FLOAT64 value read type; use readAny");
        }
        return (double[][]) readAny(count, timeoutMs);
    }

    public double[][] read(int count) {
        return read(count, 0);
    }

    /** Read up to {@code count} blocks, typed by the value read type. */
    public Object readAny(int count, long timeoutMs) {
        SampleType valueType = getValueReadType();
        int blockSize = (int) getBlockSize();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(SampleArrays.byteSize(valueType, count, blockSize));
            DaqBlockReader.ReadResult result =
                DaqBlockReader.read(requireLivePointer(), buffer, count, timeoutMs);
            Ffi.releaseRef(result.status());
            return SampleArrays.toArray(buffer, valueType, (int) result.count(), blockSize);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /** Like {@code read}, but also reading the parallel domain values (both matrices). */
    public SamplesWithDomain readWithDomain(int count, long timeoutMs) {
        SampleType valueType = getValueReadType();
        SampleType domainType = getDomainReadType();
        int blockSize = (int) getBlockSize();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment values = arena.allocate(SampleArrays.byteSize(valueType, count, blockSize));
            MemorySegment domain = arena.allocate(SampleArrays.byteSize(domainType, count, blockSize));
            DaqBlockReader.ReadWithDomainResult result =
                DaqBlockReader.readWithDomain(requireLivePointer(), values, domain, count, timeoutMs);
            Ffi.releaseRef(result.status());
            int got = (int) result.count();
            return new SamplesWithDomain(SampleArrays.toArray(values, valueType, got, blockSize),
                                         SampleArrays.toArray(domain, domainType, got, blockSize));
        } finally {
            Reference.reachabilityFence(this);
        }
    }
