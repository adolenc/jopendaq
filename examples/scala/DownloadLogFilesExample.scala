// Download a device's log files: enumerate them with getLogFileInfos and pull
// each one's contents with getLog.  On a real (often remote) device the logs
// already exist; the bundled reference device only produces one when you ask it
// to, so this example first sets that up:
//
//   * give the instance a file logger sink, so openDAQ actually writes a log
//     file;
//   * add the device with EnableLogging = true and LoggingPath pointing at that
//     same file -- that is the file the reference device reports and serves.
//
// getLogFileInfos / getLog then behave exactly as they would against a remote
// device.

import com.opendaq.{Unit as _, *}   // hide com.opendaq.Unit so `: Unit` means scala.Unit
import java.nio.charset.StandardCharsets
import java.nio.file.Files

object DownloadLogFilesExample:
  def main(args: Array[String]): Unit =
    val workDir = Files.createTempDirectory("java-opendaq-log-example")
    val deviceLog = workDir.resolve("ref_device_simulator.log")
    val downloads = Files.createDirectories(workDir.resolve("downloads"))

    val builder = InstanceBuilder()
    builder.setModulePath(Daq.nativeLibraryDirectory.toString)   // find the bundled modules
    builder.addLoggerSink(LoggerSink(deviceLog.toString))
    val instance = Instance(builder)

    // The reference device reads these two properties from its add-device config.
    val config = PropertyObject()
    config.addProperty(Property.createBoolProperty("EnableLogging", true, true))
    config.addProperty(Property.createStringProperty("LoggingPath", deviceLog.toString, true))

    val device = instance.addDevice("daqref://device0", config)

    // Flush the logger so everything buffered so far is on disk before we read it.
    instance.getContext.getLogger.flush()

    val infos = device.getLogFileInfos
    if infos.isEmpty then
      println("Device exposes no log files.")
    else
      println("Device exposes " + infos.size + " log file(s):")
      println()
      infos.forEach { info =>
        val destination = downloads.resolve(info.getName)
        println("• " + info.getName)
        println("    id:            " + info.getId)
        println("    size:          " + info.getSize + " bytes")
        println("    encoding:      " + info.getEncoding)
        println("    last-modified: " + info.getLastModified)
        // size/offset let you fetch just part of a file; here, a short head preview.
        println("    preview:       \"" + device.getLog(info.getId, 60, 0).replace("\n", "\\n") + "\"")
        val content = device.getLog(info.getId)   // whole file (defaults size -1, offset 0)
        Files.writeString(destination, content, StandardCharsets.UTF_8)
        println("    downloaded " + content.length + " chars -> " + destination)
        println()
      }
