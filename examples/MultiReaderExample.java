import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.LongFunction;

import org.opendaq.*;

/**
 * Read two channels at once with a multi reader, aligned on their common
 * domain, and print the aligned rows with wall-clock timestamps.
 */
public class MultiReaderExample {

    private static final DateTimeFormatter FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);

    public static void main(String[] args) {
        Instance instance = new Instance();
        instance.addDevice("daqref://device0");

        Channel ch0 = instance.findComponent("Dev/RefDev0/IO/AI/RefCh0").asType(Channel.class);
        Channel ch1 = instance.findComponent("Dev/RefDev0/IO/AI/RefCh1").asType(Channel.class);
        ch0.setPropertyValue("Frequency", 0.5);
        ch1.setPropertyValue("Frequency", 2.0);

        MultiReader reader = new MultiReader(List.of(
            ch0.getSignals().get(0),
            ch1.getSignals().get(0)));

        for (int attempt = 0; attempt < 10; attempt++) {
            MultiReader.MultiSamples result = reader.readWithDomain(8, 1000);
            if (result.domainTicks(0).length == 0) {
                continue;
            }
            LongFunction<Instant> toTimestamp = Daq.domainTimeConverter(reader);
            System.out.printf("%-16s%14s%14s%n", "timestamp", "signal 0", "signal 1");
            long[] ticks = result.domainTicks(0);
            for (int k = 0; k < ticks.length; k++) {
                System.out.printf("%-16s%14.6f%14.6f%n",
                    FORMAT.format(toTimestamp.apply(ticks[k])),
                    result.doubleValues(0)[k],
                    result.doubleValues(1)[k]);
            }
            return;
        }
        System.out.println("Multi reader did not synchronise in time.");
    }
}
