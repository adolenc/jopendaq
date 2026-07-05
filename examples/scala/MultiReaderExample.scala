// Read two channels at once with a multi reader, aligned on their common
// domain, and print the aligned rows with wall-clock timestamps.

import com.opendaq.{Unit as _, *}   // hide com.opendaq.Unit so `: Unit` means scala.Unit
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object MultiReaderExample:
  private val Format =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC)

  def main(args: Array[String]): Unit =
    val instance = Instance()
    instance.addDevice("daqref://device0")

    val ch0 = instance.findComponent("Dev/RefDev0/IO/AI/RefCh0").asType(classOf[Channel])
    val ch1 = instance.findComponent("Dev/RefDev0/IO/AI/RefCh1").asType(classOf[Channel])
    ch0.setPropertyValue("Frequency", 0.5)
    ch1.setPropertyValue("Frequency", 2.0)

    val reader = MultiReader(java.util.List.of(ch0.getSignals.get(0), ch1.getSignals.get(0)))

    var printed = false
    var attempt = 0
    while attempt < 10 && !printed do
      val result = reader.readWithDomain(8, 1000)
      val ticks = result.domainTicks(0)
      if ticks.length != 0 then
        val toTimestamp = Daq.domainTimeConverter(reader)
        printf("%-16s%14s%14s%n", "timestamp", "signal 0", "signal 1")
        for k <- ticks.indices do
          printf("%-16s%14.6f%14.6f%n",
            Format.format(toTimestamp.apply(ticks(k))),
            result.doubleValues(0)(k),
            result.doubleValues(1)(k))
        printed = true
      attempt += 1
    if !printed then println("Multi reader did not synchronise in time.")
