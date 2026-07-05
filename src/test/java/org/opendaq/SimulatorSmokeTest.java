package org.opendaq;

import java.time.Instant;
import java.util.List;
import java.util.function.LongFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end smoke coverage against the bundled reference device simulator —
 * ported from the reference bindings' smoke suite.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulatorSmokeTest {

    private final Instance instance = new Instance();
    private final Device device = instance.getRootDevice().addDevice("daqref://device0");

    private Channel channel(String name) {
        return device.findComponent("IO/AI/" + name).asType(Channel.class);
    }

    @Test
    void simulatorReads() {
        List<Signal> signals = channel("RefCh0").getSignalsRecursive();
        assertFalse(signals.isEmpty());
        Signal signal = signals.get(0);

        // No priming read: the first read must skip the reader's initial
        // descriptor-change event on its own and still return samples.
        StreamReader reader = new StreamReader(signal);
        double[] samples = reader.read(100, 2000);
        assertEquals(100, samples.length);

        StreamReader reader2 = new StreamReader(signal);
        SamplesWithDomain result = reader2.readWithDomain(10, 2000);
        assertEquals(10, result.doubleValues().length);
        assertEquals(result.doubleValues().length, result.domainTicks().length);

        LongFunction<Instant> converter = Daq.domainTimeConverter(signal);
        long[] ticks = result.domainTicks();
        Instant previous = null;
        for (long tick : ticks) {
            Instant timestamp = converter.apply(tick);
            if (previous != null) {
                assertFalse(timestamp.isBefore(previous), "ticks should map to non-decreasing timestamps");
            }
            previous = timestamp;
        }
        assertEquals(converter.apply(ticks[0]), Daq.domainTickToInstant(signal, ticks[0]));
    }

    @Test
    void componentTypeDetection() {
        Channel channel = channel("RefCh0");
        Signal signal = channel.getSignalsRecursive().get(0);
        assertEquals(Device.class, Daq.componentType(device));
        assertEquals(Channel.class, Daq.componentType(channel));
        assertEquals(Signal.class, Daq.componentType(signal));
        assertTrue(channel.isA(Folder.class), "a channel is a function block, which is a folder");
        assertFalse(signal.isA(Folder.class), "the failure path must not crash");
    }

    @Test
    void multiReader() {
        List<Signal> signals = List.of(
            channel("RefCh0").getSignals().get(0),
            channel("RefCh1").getSignals().get(0));
        MultiReader reader = new MultiReader(signals);

        // The first reads only synchronise the streams; loop until aligned data.
        for (int attempt = 0; attempt < 30; attempt++) {
            MultiReader.MultiSamples result = reader.readWithDomain(10, 1000);
            if (result.domainTicks(0).length == 0) {
                continue;
            }
            assertEquals(2, result.values().size());
            assertEquals(2, result.domain().size());
            assertEquals(result.doubleValues(0).length, result.doubleValues(1).length);
            assertArrayEquals(result.domainTicks(0), result.domainTicks(1),
                "synchronised signals should share identical domain ticks");
            assertEquals(2, reader.read(5, 1000).size());
            return;
        }
        fail("Multi reader did not synchronise within the attempt budget.");
    }

    @Test
    void blockReader() {
        Signal signal = channel("RefCh0").getSignalsRecursive().get(0);
        BlockReader reader = new BlockReader(signal, 10);
        double[][] blocks = reader.read(5, 2000);
        assertEquals(5, blocks.length);
        assertEquals(10, blocks[0].length);
    }

    @Test
    void streamReader2dSignal() throws InterruptedException {
        Channel channel = channel("RefCh0");
        FunctionBlock fft = instance.addFunctionBlock("RefFBModuleFFT");
        fft.setPropertyValue("BlockSize", 16);
        fft.getInputPorts().get(0).connect(channel.getSignals().get(0));
        Signal signal = fft.getSignals().get(0);
        StreamReader reader = new StreamReader(signal);

        // Wait for the block to publish its output descriptor before reading.
        for (int attempt = 0; attempt < 50 && signal.getDescriptor() == null; attempt++) {
            Thread.sleep(50);
        }
        DataDescriptor descriptor = signal.getDescriptor();
        assertNotNull(descriptor, "the FFT block should publish an output descriptor");
        List<Dimension> dimensions = descriptor.getDimensions();
        assertEquals(1, dimensions.size());
        assertEquals(16, dimensions.get(0).getSize());

        // The first reads may be short while the stream warms up; loop until
        // 5 whole spectra arrive as a (samples x bins) matrix.
        double[][] spectra = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            spectra = reader.readMatrix(5, 1000);
            if (spectra.length == 5) {
                break;
            }
        }
        assertNotNull(spectra);
        assertEquals(5, spectra.length, "read should return the requested number of samples as rows");
        assertEquals(16, spectra[0].length, "each row should hold one value per frequency bin");

        // readWithDomain pairs the matrix with one tick per sample.
        for (int attempt = 0; attempt < 50; attempt++) {
            SamplesWithDomain result = reader.readWithDomain(3, 1000);
            double[][] values = result.doubleMatrix();
            if (values.length == 3) {
                assertEquals(16, values[0].length);
                assertEquals(3, result.domainTicks().length, "one domain tick per sample");
                return;
            }
        }
        fail("The FFT stream did not produce 3 spectra with domain within the attempt budget.");
    }

    @Test
    void dataPacketBuffers() {
        DataDescriptorBuilder builder = new DataDescriptorBuilder();
        builder.setSampleType(SampleType.FLOAT64);
        DataPacket packet = new DataPacket(builder.build(), 8, 0);
        Object values = packet.getData();
        assertInstanceOf(double[].class, values);
        assertEquals(8, ((double[]) values).length);
        byte[] raw = packet.getRawData();
        assertEquals(64, raw.length, "sample-count * element-size bytes");
    }

    @Test
    void dataPacketWrite() {
        // setData coerces elements to the descriptor's sample type; getData
        // reads them back.
        DataPacket floatPacket = makePacket(SampleType.FLOAT64, 4);
        floatPacket.setData(new double[] {1.5, 2.5, 3.5, 4.5});
        assertArrayEquals(new double[] {1.5, 2.5, 3.5, 4.5}, (double[]) floatPacket.getData());

        DataPacket intPacket = makePacket(SampleType.INT32, 3);
        intPacket.setData(new double[] {1, 2.6, -3.2});
        assertArrayEquals(new int[] {1, 3, -3}, (int[]) intPacket.getData(),
            "reals should be rounded into an integer sample buffer");

        DataPacket complexPacket = makePacket(SampleType.COMPLEX_FLOAT64, 2);
        complexPacket.setData(new ComplexValue[] {
            new ComplexValue(1.0, 2.0), new ComplexValue(3.0, -4.0)});
        assertArrayEquals(new ComplexValue[] {
            new ComplexValue(1.0, 2.0), new ComplexValue(3.0, -4.0)},
            (ComplexValue[]) complexPacket.getData());

        // A sample type that is not a flat numeric buffer errors rather than
        // corrupting.
        DataPacket structPacket = makePacket(SampleType.STRUCT, 1);
        assertThrows(IllegalArgumentException.class, structPacket::getData);
    }

    private static DataPacket makePacket(SampleType sampleType, int count) {
        DataDescriptorBuilder builder = new DataDescriptorBuilder();
        builder.setSampleType(sampleType);
        return new DataPacket(builder.build(), count, 0);
    }

    @Test
    void createSignalAndRead() {
        // Build a signal by hand, push packets into it, and read them back.
        // The rule's delta/start and the packets' offsets are plain Java
        // integers — boxed and queried to INumber, which openDAQ's DataRule
        // and DataPacket factories require.
        Context context = instance.getContext();

        DataDescriptorBuilder domainBuilder = new DataDescriptorBuilder();
        domainBuilder.setSampleType(SampleType.INT64);
        domainBuilder.setName("time");
        domainBuilder.setRule(DataRule.createLinearDataRule(1, 0));
        DataDescriptor domainDescriptor = domainBuilder.build();

        DataDescriptorBuilder valueBuilder = new DataDescriptorBuilder();
        valueBuilder.setSampleType(SampleType.FLOAT64);
        valueBuilder.setName("values");
        DataDescriptor valueDescriptor = valueBuilder.build();

        SignalConfig domainSignal = new SignalConfig(context, null, "time", null);
        domainSignal.setDescriptor(domainDescriptor);
        SignalConfig signal = new SignalConfig(context, null, "values", null);
        signal.setDescriptor(valueDescriptor);
        signal.setDomainSignal(domainSignal);

        StreamReader reader = new StreamReader(signal, SampleType.FLOAT64, SampleType.INT64,
                                               ReadMode.SCALED, ReadTimeoutType.ANY, false);
        sendChunk(signal, domainDescriptor, valueDescriptor, 0, new double[] {1, 2, 3, 4});
        sendChunk(signal, domainDescriptor, valueDescriptor, 4, new double[] {5, 6, 7, 8});
        sendChunk(signal, domainDescriptor, valueDescriptor, 8, new double[] {9, 10});

        SamplesWithDomain result = reader.readWithDomain(100, 1000);
        assertArrayEquals(new double[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, result.doubleValues());
        assertArrayEquals(new long[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, result.domainTicks(),
            "the implicit linear domain should yield contiguous ticks across packets");
    }

    private static void sendChunk(SignalConfig signal, DataDescriptor domainDescriptor,
                                  DataDescriptor valueDescriptor, long offset, double[] samples) {
        DataPacket domainPacket = new DataPacket(domainDescriptor, samples.length, offset);
        DataPacket packet = DataPacket.createDataPacketWithDomain(
            domainPacket, valueDescriptor, samples.length, 0);
        packet.setData(samples);
        signal.sendPacket(packet);
    }

    @Test
    void callableProperties() {
        // FUNC property: getPropertyValue returns a callable FunctionObject.
        Object sum = device.getPropertyValue("Protected.Sum");
        assertInstanceOf(FunctionObject.class, sum);
        assertEquals(12L, ((FunctionObject) sum).call(7, 5));
        assertEquals(42L, ((FunctionObject) sum).call(40, 2));
        assertThrows(IllegalArgumentException.class, () -> ((FunctionObject) sum).call(1),
            "the wrong number of arguments should be rejected");

        // FUNC property taking a LIST argument: a Java list boxes into an
        // openDAQ list.
        FunctionObject sumList = (FunctionObject) device.getPropertyValue("Protected.SumList");
        assertEquals(10L, sumList.call(List.of(1, 2, 3, 4)));

        // Scalar property: unboxed to its native Java value.
        Object channels = device.getPropertyValue("NumberOfChannels");
        assertInstanceOf(Long.class, channels);

        // PROC property with no arguments: dispatched for its side effect.
        Object reset = channel("RefCh0").getPropertyValue("ResetCounter");
        assertInstanceOf(Procedure.class, reset);
        assertDoesNotThrow(() -> ((Procedure) reset).invoke());
        assertThrows(IllegalArgumentException.class, () -> ((Procedure) reset).invoke(1),
            "a zero-argument PROC property should reject surplus arguments");

        // FUNC property with a single argument: the bare-value param encoding.
        FunctionObject getAndSet = (FunctionObject) channel("RefCh0").getPropertyValue("GetAndSetCounter");
        assertInstanceOf(Long.class, getAndSet.call(0));
    }

    @Test
    void nativesLoaded() {
        assertNotNull(org.opendaq.lowlevel.NativeLoader.loadedNativeDirectory(),
            "the native openDAQ libraries should be loaded");
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        Daq.healthcheck(new java.io.PrintStream(buffer));
        assertTrue(buffer.toString().contains("status: loaded"));
    }
}
