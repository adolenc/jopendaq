import com.opendaq.*;

/**
 * Subscribe a Java lambda to the instance's core event stream: each property
 * change is reported through the handler until it is unsubscribed.
 */
public class CoreEventsExample {

    public static void main(String[] args) {
        Instance instance = new Instance();
        instance.addDevice("daqref://device0");

        Channel channel = instance.findComponent("Dev/RefDev0/IO/AI/RefCh0").asType(Channel.class);

        Event coreEvent = instance.getContext().getOnCoreEvent();
        EventHandler handler = coreEvent.addHandler((sender, eventArgs) -> {
            CoreEventArgs event = eventArgs.asType(CoreEventArgs.class);
            System.out.println("  " + event.getEventName() + ": "
                + Daq.unbox(event.getParameters().get("Name")));
        });

        // While subscribed, each property change is reported by the handler above.
        System.out.println("subscribed:");
        channel.setPropertyValue("Frequency", 25.0);
        channel.setPropertyValue("Amplitude", 7.5);

        // removeHandler unsubscribes; further changes fire nothing.
        coreEvent.removeHandler(handler);
        System.out.println("unsubscribed (no lines expected below):");
        channel.setPropertyValue("Frequency", 50.0);
    }
}
