import java.util.List;
import java.util.stream.Collectors;

import org.opendaq.*;

/**
 * Switch a device between operation modes and lock/unlock it against
 * configuration changes.
 */
public class DeviceLockAndOperationModeExample {

    public static void main(String[] args) {
        Instance instance = new Instance();
        Device device = instance.addDevice("daqref://device0");

        List<Long> modes = device.getAvailableOperationModes();
        System.out.println("Available operation modes: " + modes.stream()
            .map(mode -> label(OperationModeType.fromValue(mode.intValue())))
            .collect(Collectors.joining(", ")));
        System.out.println("Current operation mode:    " + label(device.getOperationMode()));

        device.setOperationMode(OperationModeType.OPERATION);
        device.lock();
        System.out.println("After setting Operation:   " + label(device.getOperationMode()));
        System.out.println("Device locked: " + device.isLocked());

        device.unlock();
        device.setOperationMode(OperationModeType.SAFE_OPERATION);
        System.out.println();
        System.out.println("Device locked: " + device.isLocked());
        System.out.println("Final operation mode:      " + label(device.getOperationMode()));
    }

    private static String label(OperationModeType mode) {
        StringBuilder sb = new StringBuilder();
        for (String word : mode.name().toLowerCase().split("_")) {
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
}
