    /**
     * Wrap a Java callback as an openDAQ Procedure, invoked with the decoded
     * params each time the procedure is dispatched — from Java or from native
     * code (a proc property's value, ...).
     */
    public Procedure(ProcedureCallback callback) {
        super(DaqProcedure.createProcedure(Callbacks.procedureStub(callback)));
    }

    // Declared argument count from the owning property's callable info (set
    // by PropertyObject.getPropertyValue); -1 when unknown.
    int declaredArity = -1;

    /**
     * Dispatch this procedure with natural Java arguments (boxed per the
     * openDAQ params convention: none, a single value, or a list).  A
     * procedure obtained from a property checks the argument count against
     * the property's declared callable info.
     */
    public void invoke(Object... params) {
        if (declaredArity >= 0 && params.length != declaredArity) {
            throw new IllegalArgumentException(
                "openDAQ callable expects " + declaredArity + " argument(s) but was called with "
                + params.length + ".");
        }
        MemorySegment encoded = Callbacks.encodeParamsOwned(params);
        try {
            DaqProcedure.dispatch(requireLivePointer(), encoded);
        } finally {
            Ffi.releaseRef(encoded);
            Reference.reachabilityFence(this);
        }
    }
