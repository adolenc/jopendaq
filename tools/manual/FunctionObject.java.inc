    /**
     * Wrap a Java callback as an openDAQ Function, invoked with the decoded
     * params each time the function is called; the callback's return value is
     * boxed back for the caller.
     */
    public FunctionObject(FunctionCallback callback) {
        super(DaqFunction.createFunction(Callbacks.functionStub(callback)));
    }

    // Declared argument count from the owning property's callable info (set
    // by PropertyObject.getPropertyValue); -1 when unknown.
    int declaredArity = -1;

    /**
     * Call this function with natural Java arguments (boxed per the openDAQ
     * params convention) and return the unboxed result — a scalar or
     * container as its natural Java value, anything else as a wrapper.  A
     * function obtained from a property checks the argument count against
     * the property's declared callable info.
     */
    public Object call(Object... params) {
        if (declaredArity >= 0 && params.length != declaredArity) {
            throw new IllegalArgumentException(
                "openDAQ callable expects " + declaredArity + " argument(s) but was called with "
                + params.length + ".");
        }
        MemorySegment encoded = Callbacks.encodeParamsOwned(params);
        try {
            MemorySegment result = DaqFunction.call(requireLivePointer(), encoded, MemorySegment.NULL);
            return Wrap.unboxOwned(result);
        } finally {
            Ffi.releaseRef(encoded);
            Reference.reachabilityFence(this);
        }
    }
