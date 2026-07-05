import org.opendaq.*;

/**
 * The minimal openDAQ flow: connect to the reference (simulator) device, take
 * its first analog channel's signal, and stream samples off it.
 */
public class StreamReaderExample {

    public static void main(String[] args) {
        Instance instance = new Instance();
        instance.addDevice("daqref://device0");

        Channel channel = instance.findComponent("Dev/RefDev0/IO/AI/RefCh0").asType(Channel.class);
        Signal signal = channel.getSignals().get(0);
        StreamReader reader = new StreamReader(signal);

        System.out.println("some samples: " + java.util.Arrays.toString(reader.read(100, 1000)));
        System.out.println("and more samples: " + java.util.Arrays.toString(reader.read(100, 1000)));
        System.out.println("and more still: " + java.util.Arrays.toString(reader.read(100, 1000)));
    }
}
