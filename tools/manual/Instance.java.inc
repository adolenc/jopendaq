    /**
     * Create a ready-to-use openDAQ instance: built from a default
     * {@code InstanceBuilder} whose module path points at the bundled native
     * openDAQ modules, so no arguments are needed.
     */
    public Instance() {
        super(createDefaultInstance());
    }

    private static MemorySegment createDefaultInstance() {
        InstanceBuilder builder = new InstanceBuilder();
        try {
            builder.setModulePath(NativeLoader.nativeLibraryDirectory().toString());
            return DaqInstance.createInstanceFromBuilder(builder.requireLivePointer());
        } finally {
            builder.release();
        }
    }
