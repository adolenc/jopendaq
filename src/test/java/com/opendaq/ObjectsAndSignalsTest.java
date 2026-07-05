package com.opendaq;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for components, contexts, loggers, signals, scalings, streaming
 * and server types — ported from the reference bindings' component, context,
 * logger, signal, streaming and server suites.
 */
class ObjectsAndSignalsTest {

    private static Context makeContext() {
        Logger logger = new Logger(List.of(LoggerSink.createStdErrLoggerSink()), LogLevel.DEBUG);
        return new Context(null, logger, new TypeManager(), null, null, Map.of(), Map.of());
    }

    @Test
    void componentHierarchy() {
        Context context = makeContext();
        Component parent = new Component(context, null, "parent", null);
        Component child = new Component(context, parent, "child", null);
        assertEquals("child", child.getLocalId());
        assertEquals("/parent/child", child.getGlobalId());
        assertEquals(Component.class, Daq.componentType(child));
        assertTrue(child.isA(Component.class));
        assertFalse(child.isA(Channel.class));
    }

    @Test
    void contextConstruction() {
        Context context = makeContext();
        assertNotNull(context.getLogger());
        assertNotNull(context.getTypeManager());
        assertInstanceOf(Map.class, context.getOptions());
    }

    @Test
    void loggerConstruction() {
        Logger logger = new Logger(List.of(LoggerSink.createStdOutLoggerSink()), LogLevel.DEBUG);
        assertNotEquals(MemorySegment.NULL, logger.rawPointer());
        assertEquals(LogLevel.DEBUG, logger.getLevel());
    }

    @Test
    void dataDescriptor() {
        UnitBuilder unitBuilder = new UnitBuilder();
        unitBuilder.setId(-1);
        unitBuilder.setName("volts");
        unitBuilder.setSymbol("V");
        unitBuilder.setQuantity("voltage");
        Unit unit = unitBuilder.build();

        DataDescriptorBuilder builder = new DataDescriptorBuilder();
        builder.setSampleType(SampleType.INT64);
        builder.setName("vals");
        builder.setUnit(unit);
        DataDescriptor descriptor = builder.build();

        assertEquals("vals", descriptor.getName());
        assertEquals("V", descriptor.getUnit().getSymbol());
        assertEquals(SampleType.INT64, descriptor.getSampleType());
    }

    @Test
    void scaling() {
        ScalingBuilder builder = new ScalingBuilder();
        builder.setInputDataType(SampleType.INT16);
        builder.setOutputDataType(ScaledSampleType.FLOAT32);
        builder.setScalingType(ScalingType.LINEAR);
        builder.setParameters(Map.of("scale", 10, "offset", 10));
        Scaling scaling = builder.build();

        assertEquals(SampleType.INT16, scaling.getInputSampleType());
        assertEquals(ScaledSampleType.FLOAT32, scaling.getOutputSampleType());
        assertEquals(ScalingType.LINEAR, scaling.getType());
        Map<String, Object> parameters = scaling.getParameters();
        assertEquals(10L, parameters.get("scale"));
        assertEquals(10L, parameters.get("offset"));
    }

    @Test
    void inputPortConfig() {
        InputPortConfig port = new InputPortConfig(makeContext(), null, "daqInputPort", false);
        assertNotEquals(MemorySegment.NULL, port.rawPointer());
        assertFalse(port.getGapCheckingEnabled());
    }

    @Test
    void signalConfigWithDescriptor() {
        DataDescriptorBuilder builder = new DataDescriptorBuilder();
        builder.setSampleType(SampleType.INT64);
        builder.setName("vals");
        DataDescriptor descriptor = builder.build();
        SignalConfig signal = SignalConfig.createSignalWithDescriptor(
            makeContext(), descriptor, null, "sig", null);
        assertNotEquals(MemorySegment.NULL, signal.rawPointer());
        assertEquals("vals", signal.getDescriptor().getName());
    }

    @Test
    void streamingType() {
        StreamingType streamingType = new StreamingType(
            "streamingType", "streamingTypeName", "streamingTypeDescription",
            "streamingTypePrefix", new PropertyObject());
        assertEquals("streamingTypePrefix", streamingType.getConnectionStringPrefix());
    }

    @Test
    void subscriptionEventArgs() {
        SubscriptionEventArgs args = new SubscriptionEventArgs(
            "streamingConnectionString", SubscriptionEventType.SUBSCRIBED);
        assertEquals("streamingConnectionString", args.getStreamingConnectionString());
        assertEquals(SubscriptionEventType.SUBSCRIBED, args.getSubscriptionEventType());
    }

    @Test
    void serverType() {
        ServerType serverType = new ServerType(
            "serverType", "serverTypeName", "serverTypeDescription", new PropertyObject());
        assertNotEquals(MemorySegment.NULL, serverType.rawPointer());
        assertEquals("serverType", serverType.getId());
    }

    @Test
    void addressInfoBuilder() {
        AddressInfoBuilder builder = new AddressInfoBuilder();
        builder.setConnectionString("daqref://device0");
        builder.setReachabilityStatus(AddressReachabilityStatus.UNKNOWN);
        builder.setType("Type");
        builder.setAddress("Address");
        AddressInfo info = builder.build();
        assertEquals("daqref://device0", info.getConnectionString());
        assertEquals("Type", info.getType());
        assertEquals("Address", info.getAddress());
    }

    @Test
    void instanceBuilder() {
        InstanceBuilder builder = new InstanceBuilder();
        builder.setModulePath(Daq.nativeLibraryDirectory().toString());
        builder.enableStandardProviders();
        assertNotNull(builder.getModulePath());
        Instance instance = builder.build();
        assertNotNull(instance.getRootDevice());
    }
}
