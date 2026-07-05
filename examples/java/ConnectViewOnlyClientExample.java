import java.util.Map;
import java.util.TreeMap;

import com.opendaq.*;

/**
 * Connect to a networked openDAQ device as a view-only client (reads work,
 * configuration writes are refused).  Expects a device/simulator at
 * daq.nd://127.0.0.1 or at $OPENDAQ_DEVICE.
 */
public class ConnectViewOnlyClientExample {

    public static void main(String[] args) {
        Instance instance = new Instance();

        PropertyObject config = instance.createDefaultAddDeviceConfig();

        PropertyObject general = ((DaqObject) config.getPropertyValue("General"))
            .asType(PropertyObject.class);
        Property clientType = general.getProperty("ClientType");
        Object options = Daq.unbox(clientType.getSelectionValues());
        System.out.println("Available ClientType options:");
        if (options instanceof Map<?, ?> map) {
            new TreeMap<>(map.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                        e -> ((Number) e.getKey()).longValue(), Map.Entry::getValue)))
                .forEach((key, value) -> System.out.println("  " + key + " = " + value));
        } else if (options instanceof java.util.List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                System.out.println("  " + i + " = " + list.get(i));
            }
        }

        config.setPropertyValue("General.ClientType", 2);   // 2 = view-only
        String connectionString = System.getenv().getOrDefault("OPENDAQ_DEVICE", "daq.nd://127.0.0.1");

        try {
            Device device = instance.addDevice(connectionString, config);
            System.out.println("Connected to " + device.getName() + " as a view-only client.");
            System.out.println("Visible signals: " + device.getSignalsRecursive().size());
            String propertyName = firstWritablePropertyName(device);
            if (propertyName == null) {
                System.out.println("Device exposes no writable property to probe.");
                return;
            }
            try {
                device.setPropertyValue(propertyName, device.getPropertyValue(propertyName));
                System.out.println("Unexpected: writing \"" + propertyName + "\" was allowed.");
            } catch (RuntimeException e) {
                System.out.println("Write to \"" + propertyName + "\" refused, as expected for view-only: "
                    + e.getMessage());
            }
        } catch (RuntimeException e) {
            System.out.println("Could not connect to " + connectionString + ": " + e.getMessage());
            System.out.println("Start an openDAQ device/simulator there, or set OPENDAQ_DEVICE.");
        }
    }

    /**
     * Name of the object's first visible, non-read-only, non-callable
     * property, or null.  Used to probe whether the connection actually
     * refuses writes.
     */
    private static String firstWritablePropertyName(PropertyObject object) {
        for (Property property : object.getVisibleProperties()) {
            CoreType type = property.getValueType();
            if (!property.getReadOnly() && type != CoreType.FUNC && type != CoreType.PROC) {
                return property.getName();
            }
        }
        return null;
    }
}
