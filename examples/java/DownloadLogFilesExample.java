import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.opendaq.*;

/**
 * Download a device's log files: enumerate them with getLogFileInfos and pull
 * each one's contents with getLog.  On a real (often remote) device the logs
 * already exist; the bundled reference device only produces one when you ask
 * it to, so this example first sets that up:
 *
 * <ul>
 *   <li>give the instance a file logger sink, so openDAQ actually writes a
 *       log file;</li>
 *   <li>add the device with EnableLogging = true and LoggingPath pointing at
 *       that same file — that is the file the reference device reports and
 *       serves.</li>
 * </ul>
 *
 * getLogFileInfos / getLog then behave exactly as they would against a
 * remote device.
 */
public class DownloadLogFilesExample {

    public static void main(String[] args) throws Exception {
        Path workDir = Files.createTempDirectory("java-opendaq-log-example");
        Path deviceLog = workDir.resolve("ref_device_simulator.log");
        Path downloads = Files.createDirectories(workDir.resolve("downloads"));

        Instance instance = new InstanceBuilder()
            .setModulePath(Daq.nativeLibraryDirectory().toString())   // find the bundled modules
            .addLoggerSink(new LoggerSink(deviceLog.toString()))
            .build();

        // The reference device reads these two properties from its add-device config.
        PropertyObject config = new PropertyObject();
        config.addProperty(Property.createBoolProperty("EnableLogging", true, true));
        config.addProperty(Property.createStringProperty("LoggingPath", deviceLog.toString(), true));

        Device device = instance.addDevice("daqref://device0", config);

        // Flush the logger so everything buffered so far is on disk before we read it.
        instance.getContext().getLogger().flush();

        var infos = device.getLogFileInfos();
        if (infos.isEmpty()) {
            System.out.println("Device exposes no log files.");
            return;
        }
        System.out.println("Device exposes " + infos.size() + " log file(s):");
        System.out.println();
        for (LogFileInfo info : infos) {
            Path destination = downloads.resolve(info.getName());
            System.out.println("• " + info.getName());
            System.out.println("    id:            " + info.getId());
            System.out.println("    size:          " + info.getSize() + " bytes");
            System.out.println("    encoding:      " + info.getEncoding());
            System.out.println("    last-modified: " + info.getLastModified());
            // size/offset let you fetch just part of a file; here, a short head preview.
            System.out.println("    preview:       \"" + device.getLog(info.getId(), 60, 0)
                .replace("\n", "\\n") + "\"");
            String content = device.getLog(info.getId());   // whole file (defaults size -1, offset 0)
            Files.writeString(destination, content, StandardCharsets.UTF_8);
            System.out.println("    downloaded " + content.length() + " chars -> " + destination);
            System.out.println();
        }
    }
}
