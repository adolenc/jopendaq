import java.util.List;

import com.opendaq.*;

/**
 * Load a function block from the bundled modules (a statistics block), wire a
 * channel signal into its input port, and read the averaged output.
 */
public class AddFunctionBlockExample {

    public static void main(String[] args) {
        // Building the instance explicitly, to show where the modules come
        // from; plain `new Instance()` does exactly this.  The builder's
        // configuration methods return the builder, so they chain.
        Instance instance = new InstanceBuilder()
            .setModulePath(Daq.nativeLibraryDirectory().toString())  // the bundled modules
            // .addModulePath("/path/to/your/modules")               // your own modules folder
            .build();
        instance.addDevice("daqref://device0");

        Channel channel = instance.findComponent("Dev/RefDev0/IO/AI/RefCh0").asType(Channel.class);
        channel.setPropertyValue("Amplitude", 5.0);
        channel.setPropertyValue("DC", 1.0);

        FunctionBlock statistics = instance.addFunctionBlock("RefFBModuleStatistics");
        statistics.setPropertyValue("BlockSize", 100);

        ComponentStatusContainer statuses = statistics.getStatusContainer();
        System.out.println("Before connect: " + statuses.getStatus("ComponentStatus").getValue()
            + " (" + statuses.getStatusMessage("ComponentStatus") + ")");

        InputPort port = statistics.getInputPorts().get(0);
        port.connect(channel.getSignals().get(0));

        statuses = statistics.getStatusContainer();
        System.out.println("After connect:  " + statuses.getStatus("ComponentStatus").getValue()
            + " (" + statuses.getStatusMessage("ComponentStatus") + ")");

        // Match on local-id, not name: a signal's name is a mutable display
        // label, while its local-id is the stable identifier within its parent.
        List<Signal> signals = statistics.getSignals();
        Signal avg = signals.stream().filter(s -> s.getLocalId().equals("avg")).findFirst().orElseThrow();
        Signal rms = signals.stream().filter(s -> s.getLocalId().equals("rms")).findFirst().orElseThrow();
        MultiReader reader = new MultiReader(List.of(avg, rms));

        // The first reads may return nothing while the block waits for a
        // complete input descriptor, so retry until samples arrive.
        for (int attempt = 0; attempt < 20; attempt++) {
            List<double[]> values = reader.read(5, 1000);
            if (values.get(0).length > 0) {
                System.out.printf("%10s%12s%n", "avg", "rms");
                for (int i = 0; i < values.get(0).length; i++) {
                    System.out.printf("%10.4f%12.4f%n", values.get(0)[i], values.get(1)[i]);
                }
                return;
            }
        }
        System.out.println("Statistics block produced no samples in time.");
    }
}
