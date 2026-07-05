import com.opendaq.*;

/**
 * A reader is invalidated when its signal's descriptor changes to a sample
 * type the reader cannot convert to its configured read type.  The reader
 * needs to be recovered by creating a new one via the from-existing factory.
 */
public class ReaderInvalidationRecoveryExample {

    public static void main(String[] args) {
        Instance instance = new Instance();
        instance.addDevice("daqref://device0");
        Channel channel = instance.findComponent("Dev/RefDev0/IO/AI/RefCh0").asType(Channel.class);
        channel.setPropertyValue("Waveform", 0);
        channel.setPropertyValue("Amplitude", 0.0);
        channel.setPropertyValue("DC", 2.0);
        channel.setPropertyValue("NoiseAmplitude", 0.0);

        // Average the channel in blocks of 10 samples.  DomainSignalType
        // starts at 0 = Implicit: the output domain is int64 ticks under a
        // linear rule.
        FunctionBlock statistics = instance.addFunctionBlock("RefFBModuleStatistics");
        statistics.getInputPorts().get(0).connect(channel.getSignals().get(0));
        Signal avgSignal = statistics.getSignals().get(0);

        // Phase 1: read averages with the domain ticks as doubles — fine
        // while the domain is int64, which converts to float64.
        StreamReader reader = new StreamReader(avgSignal, SampleType.FLOAT64, SampleType.FLOAT64);
        System.out.println("implicit int64 domain, read as float64:");
        showAverages(reader, 4);

        // Phase 2: switch the output domain to 2 = ExplicitRange — each
        // average's domain value becomes the RangeInt64 tick range of the
        // block it covers, and RangeInt64 has no conversion to float64.  The
        // change reaches the reader as a descriptor-changed event in its
        // packet queue; read hands over the samples queued before the change,
        // then hits the event and throws ReaderInvalidatedException.
        statistics.setPropertyValue("DomainSignalType", 2);
        StreamReader recovered = null;
        try {
            for (int i = 0; i < 50; i++) {
                reader.readWithDomain(100, 200);
            }
            throw new IllegalStateException("Expected the domain change to invalidate the reader.");
        } catch (ReaderInvalidatedException e) {
            System.out.println(e.getMessage());
            recovered = StreamReader.createStreamReaderFromExisting(
                (StreamReader) e.reader(), SampleType.FLOAT64, SampleType.INT64);
        }

        System.out.println("explicit RangeInt64 domain, read as int64 range starts:");
        showAverages(recovered, 4);
    }

    /**
     * Read {@code count} averages with their domain values and print them
     * (retrying while the stream warms up); the domain array is float64 or
     * int64 depending on the reader's domain read type.
     */
    private static void showAverages(StreamReader reader, int count) {
        for (int attempt = 0; attempt < 50; attempt++) {
            SamplesWithDomain result = reader.readWithDomain(count, 1000);
            double[] averages = result.doubleValues();
            if (averages.length > 0) {
                for (int i = 0; i < averages.length; i++) {
                    long tick = result.domain() instanceof long[] longs
                        ? longs[i] : (long) ((double[]) result.domain())[i];
                    System.out.printf("  tick %d  ->  avg %.3f%n", tick, averages[i]);
                }
                return;
            }
        }
        throw new IllegalStateException("The statistics stream produced no averages.");
    }
}
