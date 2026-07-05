    /**
     * Wrap a Java callback as an openDAQ event handler.  The callback is
     * invoked with the wrapped sender and event args each time the subscribed
     * event fires; reference counting is handled for you.
     */
    public EventHandler(EventCallback callback) {
        super(DaqEventHandler.createEventHandler(Callbacks.eventStub(callback)));
    }
