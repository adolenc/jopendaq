import org.opendaq.*;

/**
 * Batch several property writes into one update per object: values set inside
 * the batch are staged rather than applied — reads still return the old
 * values and no property events fire until the batch closes — and committed
 * atomically per object when the try-with-resources scope ends.
 */
public class BatchUpdatePropertiesExample {

    public static void main(String[] args) throws Exception {
        Instance instance = new Instance();
        Device device = instance.addDevice("daqref://device0");
        Channel ch0 = device.getChannels().get(0);
        Channel ch1 = device.getChannels().get(1);

        showSettings("Before:          ", ch0);
        showSettings("Before:          ", ch1);

        try (AutoCloseable batch0 = ch0.batchUpdates();
             AutoCloseable batch1 = ch1.batchUpdates()) {
            ch0.setPropertyValue("Amplitude", 2.5);
            ch0.setPropertyValue("Frequency", 25.0);
            ch0.setPropertyValue("Waveform", 1);
            ch1.setPropertyValue("Amplitude", 4.0);
            ch1.setPropertyValue("Frequency", 50.0);
            ch1.setPropertyValue("Waveform", 2);
            // Still inside the batch: the writes above are staged but not applied.
            showSettings("During (staged): ", ch0);
            showSettings("During (staged): ", ch1);
        }

        showSettings("After the batch: ", ch0);
        showSettings("After the batch: ", ch1);
    }

    private static void showSettings(String label, Channel channel) {
        System.out.println(label + channel.getName()
            + ":  Amplitude=" + channel.getPropertyValue("Amplitude")
            + "  Frequency=" + channel.getPropertyValue("Frequency")
            + "  Waveform=" + channel.getPropertyValue("Waveform"));
    }
}
