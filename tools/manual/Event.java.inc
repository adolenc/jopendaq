    /**
     * Subscribe a Java callback directly: it is wrapped in an
     * {@link EventHandler} and called with the wrapped sender and args each
     * time this event fires.  Returns the created handler, which may be
     * passed to {@code removeHandler} to unsubscribe.
     */
    public EventHandler addHandler(EventCallback callback) {
        EventHandler handler = new EventHandler(callback);
        addHandler(handler);
        return handler;
    }
