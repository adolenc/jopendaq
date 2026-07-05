    /**
     * The value of the named property in its natural Java form, based on the
     * property's value type:
     * <ul>
     *   <li>a scalar (bool/int/float/string/ratio/complex) or container
     *       (list/dict) value is unboxed to its natural Java value;</li>
     *   <li>a {@code FUNC} or {@code PROC} property yields a
     *       {@link FunctionObject} / {@link Procedure} wrapper — invoke it
     *       with {@code call(...)} / {@code invoke(...)};</li>
     *   <li>anything else (object, struct, ...) is returned as a wrapper for
     *       {@link DaqObject#asType}.</li>
     * </ul>
     * Calls the openDAQ C function {@code daqPropertyObject_getPropertyValue()}.
     */
    public Object getPropertyValue(String propertyName) {
        CoreType type = null;
        Property property = null;
        try {
            property = getProperty(propertyName);
            if (property != null) {
                type = property.getValueType();
            }
        } catch (RuntimeException ignored) {
            // Metadata lookup is best-effort: leave the value getter (and its
            // error reporting) in charge.
        }
        MemorySegment raw = rawPropertyValue(propertyName);
        if (type == CoreType.FUNC) {
            FunctionObject function = Wrap.castOwned(raw, FunctionObject.class);
            function.declaredArity = declaredArity(property);
            return function;
        }
        if (type == CoreType.PROC) {
            Procedure procedure = Wrap.castOwned(raw, Procedure.class);
            procedure.declaredArity = declaredArity(property);
            return procedure;
        }
        return Wrap.unboxOwned(raw);
    }

    private static int declaredArity(Property property) {
        try {
            CallableInfo info = property.getCallableInfo();
            return info == null ? -1 : info.getArguments().size();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private MemorySegment rawPropertyValue(String propertyName) {
        MemorySegment name = Wrap.daqStringOrNull(propertyName);
        try {
            return DaqPropertyObject.getPropertyValue(requireLivePointer(), name);
        } finally {
            Ffi.releaseRef(name);
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Open a batch property update on this object (staging writes until the
     * batch closes) and return a scope that commits it, for use with
     * try-with-resources:
     *
     * <pre>{@code
     * try (var batch = device.batchUpdates()) {
     *     device.setPropertyValue("A", 1);
     *     device.setPropertyValue("B", 2);
     * }   // both values committed here, atomically per object
     * }</pre>
     *
     * Wraps {@code beginUpdate} / {@code endUpdate}.
     */
    public AutoCloseable batchUpdates() {
        beginUpdate();
        return this::endUpdate;
    }
