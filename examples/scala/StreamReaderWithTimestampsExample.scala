// Read samples together with their domain ticks and convert each tick to an
// absolute wall-clock timestamp via the signal's domain metadata.

import com.opendaq.{Unit as _, *}   // hide com.opendaq.Unit so `: Unit` means scala.Unit
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object StreamReaderWithTimestampsExample:
  private val Format =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS 'UTC'").withZone(ZoneOffset.UTC)

  def main(args: Array[String]): Unit =
    val instance = Instance()
    instance.addDevice("daqref://device0")

    val channel = instance.findComponent("Dev/RefDev0/IO/AI/RefCh0").asType(classOf[Channel])
    val signal = channel.getSignals.get(0)
    val reader = StreamReader(signal)

    val result = reader.readWithDomain(10, 2000)
    val values = result.doubleValues
    val ticks = result.domainTicks

    val toTimestamp = Daq.domainTimeConverter(signal)
    println("Read " + values.length + " samples:")
    for i <- values.indices do
      printf("  %.6f @ %s%n", values(i), Format.format(toTimestamp.apply(ticks(i))))
