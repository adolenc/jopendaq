// Load a function block from the bundled modules (a statistics block), wire a
// channel signal into its input port, and read the averaged output.

import com.opendaq.{Unit as _, *}   // hide com.opendaq.Unit so `: Unit` means scala.Unit

object AddFunctionBlockExample:
  def main(args: Array[String]): Unit =
    // Building the instance explicitly, to show where the modules come from;
    // plain Instance() does exactly this.
    val builder = InstanceBuilder()
    builder.setModulePath(Daq.nativeLibraryDirectory.toString)  // the bundled modules
    // builder.addModulePath("/path/to/your/modules")           // your own modules folder
    val instance = Instance(builder)
    instance.addDevice("daqref://device0")

    val channel = instance.findComponent("Dev/RefDev0/IO/AI/RefCh0").asType(classOf[Channel])
    channel.setPropertyValue("Amplitude", 5.0)
    channel.setPropertyValue("DC", 1.0)

    val statistics = instance.addFunctionBlock("RefFBModuleStatistics")
    statistics.setPropertyValue("BlockSize", 100)

    var statuses = statistics.getStatusContainer
    println("Before connect: " + statuses.getStatus("ComponentStatus").getValue
      + " (" + statuses.getStatusMessage("ComponentStatus") + ")")

    val port = statistics.getInputPorts.get(0)
    port.connect(channel.getSignals.get(0))

    statuses = statistics.getStatusContainer
    println("After connect:  " + statuses.getStatus("ComponentStatus").getValue
      + " (" + statuses.getStatusMessage("ComponentStatus") + ")")

    // Match on local-id, not name: a signal's name is a mutable display label,
    // while its local-id is the stable identifier within its parent.
    val signals = statistics.getSignals
    val avg = signals.stream.filter(_.getLocalId == "avg").findFirst.orElseThrow()
    val rms = signals.stream.filter(_.getLocalId == "rms").findFirst.orElseThrow()
    val reader = MultiReader(java.util.List.of(avg, rms))

    // The first reads may return nothing while the block waits for a complete
    // input descriptor, so retry until samples arrive.
    var printed = false
    var attempt = 0
    while attempt < 20 && !printed do
      val values = reader.read(5, 1000)
      if values.get(0).length > 0 then
        printf("%10s%12s%n", "avg", "rms")
        for i <- 0 until values.get(0).length do
          printf("%10.4f%12.4f%n", values.get(0)(i), values.get(1)(i))
        printed = true
      attempt += 1
    if !printed then println("Statistics block produced no samples in time.")
