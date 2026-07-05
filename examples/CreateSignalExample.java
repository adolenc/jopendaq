import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.LongFunction;

import org.opendaq.*;

/**
 * Build a signal by hand and read it back with wall-clock timestamps.
 *
 * <p>The trick for making the domain timestamps line up with the PC clock is
 * NOT to move the origin to "now": the origin stays pinned at the Unix epoch,
 * and the current time is carried entirely by the integer domain <em>ticks</em>.
 * With a tick resolution of 1/1000 s (one tick = one millisecond) a sample's
 * absolute time is origin + tick/1000 s, so a tick equal to the current Unix
 * time in milliseconds reads back as the current wall-clock time.  Spacing
 * the ticks 200 apart (200 ms) then yields exactly 5 samples per second.
 */
public class CreateSignalExample {

    static final int SAMPLES_PER_SECOND = 5;
    static final RatioValue TICK_RESOLUTION = new RatioValue(1, 1000);   // seconds per tick (1 ms)
    static final long TICKS_PER_SAMPLE = 1000 / SAMPLES_PER_SECOND;      // 200 ticks = 200 ms

    private static final DateTimeFormatter FORMAT =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);

    static Instance instance;
    static DataDescriptor domainDescriptor;
    static DataDescriptor valueDescriptor;
    static SignalConfig domainSignal;
    static SignalConfig signal;

    public static void main(String[] args) throws Exception {
        instance = new Instance();

        DataDescriptorBuilder domainBuilder = new DataDescriptorBuilder();
        domainBuilder.setSampleType(SampleType.INT64);
        domainBuilder.setName("time");
        // Origin stays at the epoch; the wall-clock time lives in the ticks.
        domainBuilder.setOrigin("1970-01-01T00:00:00Z");
        domainBuilder.setTickResolution(TICK_RESOLUTION);
        domainBuilder.setUnit(new Unit(-1, "s", "second", "time"));
        domainBuilder.setRule(DataRule.createLinearDataRule(TICKS_PER_SAMPLE, 0));
        domainDescriptor = domainBuilder.build();

        DataDescriptorBuilder valueBuilder = new DataDescriptorBuilder();
        valueBuilder.setSampleType(SampleType.FLOAT64);
        valueBuilder.setName("values");
        valueDescriptor = valueBuilder.build();

        domainSignal = new SignalConfig(instance.getContext(), null, "time", null);
        domainSignal.setDescriptor(domainDescriptor);
        signal = new SignalConfig(instance.getContext(), null, "values", null);
        signal.setDescriptor(valueDescriptor);
        signal.setDomainSignal(domainSignal);

        StreamReader reader = new StreamReader(signal, SampleType.FLOAT64, SampleType.INT64,
                                               ReadMode.SCALED, ReadTimeoutType.ANY, false);

        // Stream a few seconds of a 5 Hz signal in real time.  Each
        // one-second batch is one packet of 5 samples; the ticks stay
        // contiguous across batches (batch k starts at startTick + k*1000 ms),
        // so the whole run is an unbroken 5 Hz stream anchored to when the
        // program started.
        int batches = 3;
        long startTick = Instant.now().toEpochMilli();
        LongFunction<Instant> toTimestamp = Daq.domainTimeConverter(signal);
        System.out.println("Streaming " + SAMPLES_PER_SECOND + " samples/second, starting at "
            + FORMAT.format(toTimestamp.apply(startTick)));

        for (int batch = 0; batch < batches; batch++) {
            int baseSample = batch * SAMPLES_PER_SECOND;
            long offset = startTick + baseSample * TICKS_PER_SAMPLE;
            double[] samples = new double[SAMPLES_PER_SECOND];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = Math.sin(2 * Math.PI * ((baseSample + i) / 25.0));
            }
            sendChunk(offset, samples);

            SamplesWithDomain read = reader.readWithDomain(100, 1000);
            double[] values = read.doubleValues();
            long[] ticks = read.domainTicks();
            for (int i = 0; i < values.length; i++) {
                System.out.printf("  %s  ->  %.4f%n", FORMAT.format(toTimestamp.apply(ticks[i])), values[i]);
            }
            Thread.sleep(1000);
        }
    }

    /**
     * Send samples as one packet whose implicit domain ticks start at
     * {@code offset} and advance by TICKS_PER_SAMPLE per sample.
     */
    static void sendChunk(long offset, double[] samples) {
        DataPacket domainPacket = new DataPacket(domainDescriptor, samples.length, offset);
        DataPacket packet = DataPacket.createDataPacketWithDomain(
            domainPacket, valueDescriptor, samples.length, 0);
        packet.setData(samples);
        signal.sendPacket(packet);
    }
}
