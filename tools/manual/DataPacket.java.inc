    // daqDataPacket_getData / getRawData return the sample buffer through a
    // void** whose element type is only known at runtime from the packet's
    // data descriptor; these accessors surface it as typed Java arrays.

    /**
     * The packet's calculated/scaled sample data as a typed Java array sized
     * to the sample count: a primitive array for scalar numeric sample
     * types, a 2-D array for dimensioned signals, {@link ComplexValue}[] for
     * the complex-float types.  Throws for sample types that are not a flat
     * numeric buffer (binary, string, struct, range).
     */
    public Object getData() {
        try {
            DataDescriptor descriptor = getDataDescriptor();
            SampleType type = descriptor.getSampleType();
            int count = (int) getSampleCount();
            int width = SampleArrays.descriptorWidth(descriptor);
            MemorySegment buffer = dataAddress(false)
                .reinterpret(SampleArrays.byteSize(type, count, width));
            return SampleArrays.toArray(buffer, type, count, width);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Write samples into this packet's buffer in place, coercing each element
     * to the packet's descriptor sample type (reals to a float type, rounded
     * to an integer type).  The packet owns the buffer and must already be
     * sized to hold them (its sample count).
     */
    public void setData(double[] samples) {
        writeData(samples);
    }

    public void setData(long[] samples) {
        writeData(samples);
    }

    public void setData(ComplexValue[] samples) {
        writeData(samples);
    }

    private void writeData(Object samples) {
        try {
            DataDescriptor descriptor = getDataDescriptor();
            SampleType type = descriptor.getSampleType();
            int length = java.lang.reflect.Array.getLength(samples);
            MemorySegment buffer = dataAddress(false)
                .reinterpret(SampleArrays.byteSize(type, length, 1));
            SampleArrays.fromArray(buffer, type, samples);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * The packet's raw (pre-scaling) memory as a byte array.  Reinterpret it
     * via the data descriptor when a typed view is needed; for decoded values
     * use {@link #getData()}.
     */
    public byte[] getRawData() {
        try {
            int size = (int) getRawDataSize();
            MemorySegment buffer = dataAddress(true).reinterpret(Math.max(size, 1));
            return buffer.asSlice(0, size).toArray(ValueLayout.JAVA_BYTE);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    private MemorySegment dataAddress(boolean raw) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment slot = arena.allocate(ValueLayout.ADDRESS);
            if (raw) {
                DaqDataPacket.getRawData(requireLivePointer(), slot);
            } else {
                DaqDataPacket.getData(requireLivePointer(), slot);
            }
            return slot.get(ValueLayout.ADDRESS, 0);
        }
    }
