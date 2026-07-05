package org.opendaq;

/**
 * A Java event handler: called with the wrapped sender and event args each
 * time the subscribed openDAQ event fires.  Both arrive as {@link DaqObject}
 * wrappers whose references are managed for you; cast {@code args} with
 * {@link DaqObject#asType} for typed event args (e.g. to
 * {@code CoreEventArgs}).  openDAQ may invoke handlers from its own worker
 * threads.
 */
@FunctionalInterface
public interface EventCallback {
    void handle(DaqObject sender, DaqObject args);
}
