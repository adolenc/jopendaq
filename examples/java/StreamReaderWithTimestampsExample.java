import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.LongFunction;

import com.opendaq.*;

/**
 * Read samples together with their domain ticks and convert each tick to an
 * absolute wall-clock timestamp via the signal's domain metadata.
 */
public class StreamReaderWithTimestampsExample {

    private static final DateTimeFormatter FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS 'UTC'").withZone(ZoneOffset.UTC);

    public static void main(String[] args) {
        Instance instance = new Instance();
        instance.addDevice("daqref://device0");

        Channel channel = instance.findComponent("Dev/RefDev0/IO/AI/RefCh0").asType(Channel.class);
        Signal signal = channel.getSignals().get(0);
        StreamReader reader = new StreamReader(signal);

        SamplesWithDomain result = reader.readWithDomain(10, 2000);
        double[] values = result.doubleValues();
        long[] ticks = result.domainTicks();

        LongFunction<Instant> toTimestamp = Daq.domainTimeConverter(signal);
        System.out.println("Read " + values.length + " samples:");
        for (int i = 0; i < values.length; i++) {
            System.out.printf("  %.6f @ %s%n", values[i], FORMAT.format(toTimestamp.apply(ticks[i])));
        }
    }
}
