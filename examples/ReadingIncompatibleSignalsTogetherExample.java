import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

import com.opendaq.*;

/**
 * Read two linear-domain signals and one explicit-domain signal at the same
 * time, and line all three up on a shared wall-clock time axis.
 *
 * <ul>
 *   <li>The two linear signals are the reference device's analog channels.
 *       Their domain is an implicit linear rule (tick = start + n*delta), and
 *       we read them together with a {@link MultiReader}, which hands back
 *       one common domain.</li>
 *   <li>The explicit-domain signal is the block average of channel 0
 *       produced by a Statistics function block with DomainSignalType =
 *       Explicit: instead of a linear rule it emits, per average, the actual
 *       tick of the first raw sample in its block.  We read it with a
 *       {@link StreamReader}.</li>
 * </ul>
 *
 * All three ride the same device clock, so every average's explicit tick
 * equals one of the channel ticks — convert both to timestamps and the
 * columns line up exactly, one average every BLOCK_SIZE channel samples.
 */
public class ReadingIncompatibleSignalsTogetherExample {

    static final double SAMPLE_RATE = 100.0;   // Hz, device-wide
    static final int BLOCK_SIZE = 10;          // channel samples per average
    static final int WINDOW = 30;              // channel samples to display
    static final int CHUNK = 5;                // samples per read

    private static final DateTimeFormatter FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);

    record Row(long tick, double v0, double v1) {}

    public static void main(String[] args) throws Exception {
        Instance instance = new Instance();
        Device device = instance.addDevice("daqref://device0");
        device.setPropertyValue("GlobalSampleRate", SAMPLE_RATE);

        Channel ch0 = instance.findComponent("Dev/RefDev0/IO/AI/RefCh0").asType(Channel.class);
        Channel ch1 = instance.findComponent("Dev/RefDev0/IO/AI/RefCh1").asType(Channel.class);
        ch0.setPropertyValue("Frequency", 5.0);
        ch1.setPropertyValue("Frequency", 10.0);
        Signal ch0Signal = ch0.getSignals().get(0);
        Signal ch1Signal = ch1.getSignals().get(0);

        // Statistics block averaging channel 0 in blocks of BLOCK_SIZE
        // samples.  DomainSignalType = 1 (Explicit) makes its output domain
        // an explicit list of ticks — one tick per average, taken from the
        // block's first raw sample.
        FunctionBlock stats = instance.addFunctionBlock("RefFBModuleStatistics");
        stats.setPropertyValue("BlockSize", BLOCK_SIZE);
        stats.setPropertyValue("DomainSignalType", 1);
        stats.getInputPorts().get(0).connect(ch0Signal);
        Signal avgSignal = stats.getSignals().get(0);

        MultiReader multiReader = new MultiReader(List.of(ch0Signal, ch1Signal));
        StreamReader streamReader = new StreamReader(avgSignal);

        // Let both streams warm up so their queues overlap in time.
        Thread.sleep(1000);

        // Fill the display window with many small reads rather than one big
        // one, interleaving the two readers so they advance together.
        // Channel samples are collected until the window is full; averages go
        // into a tick->value table and keep being read until they have caught
        // up to the end of the window — an average only appears once its
        // whole block of channel samples has arrived.
        List<Row> rows = new ArrayList<>();
        Map<Long, Double> avgByTick = new HashMap<>();
        long newestAvgTick = -1;

        for (int i = 0; i < 200; i++) {
            boolean windowFull = rows.size() >= WINDOW;
            if (windowFull && newestAvgTick >= rows.get(WINDOW - 1).tick()) {
                break;
            }
            if (!windowFull) {
                MultiReader.MultiSamples chunk = multiReader.readWithDomain(CHUNK, 1000);
                long[] ticks = chunk.domainTicks(0);
                for (int k = 0; k < ticks.length; k++) {
                    rows.add(new Row(ticks[k], chunk.doubleValues(0)[k], chunk.doubleValues(1)[k]));
                }
            }
            SamplesWithDomain averages = streamReader.readWithDomain(CHUNK, 1000);
            double[] values = averages.doubleValues();
            long[] ticks = averages.domainTicks();
            for (int k = 0; k < values.length; k++) {
                avgByTick.put(ticks[k], values[k]);
                newestAvgTick = Math.max(newestAvgTick, ticks[k]);
            }
        }

        // The multi-reader only knows its common domain once it has read, so
        // build the tick->time converter now.
        LongFunction<Instant> channelTime = Daq.domainTimeConverter(multiReader);
        System.out.printf("%-14s%14s%14s%16s%n", "time", "channel 0", "channel 1", "avg(channel 0)");
        for (int i = 0; i < Math.min(WINDOW, rows.size()); i++) {
            Row row = rows.get(i);
            Double average = avgByTick.get(row.tick());
            System.out.printf("%-14s%14.4f%14.4f%16s%n",
                FORMAT.format(channelTime.apply(row.tick())),
                row.v0(), row.v1(),
                average != null ? String.format("%.4f", average) : "");
        }
    }
}
