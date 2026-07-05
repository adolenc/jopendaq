// Read two linear-domain signals and one explicit-domain signal at the same
// time, and line all three up on a shared wall-clock time axis.
//
//   * The two linear signals are the reference device's analog channels.  Their
//     domain is an implicit linear rule (tick = start + n*delta), and we read
//     them together with a MultiReader, which hands back one common domain.
//   * The explicit-domain signal is the block average of channel 0 produced by
//     a Statistics function block with DomainSignalType = Explicit: instead of
//     a linear rule it emits, per average, the actual tick of the first raw
//     sample in its block.  We read it with a StreamReader.
//
// All three ride the same device clock, so every average's explicit tick equals
// one of the channel ticks -- convert both to timestamps and the columns line
// up exactly, one average every BLOCK_SIZE channel samples.

import com.opendaq.{Unit as _, *}   // hide com.opendaq.Unit so `: Unit` means scala.Unit
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import scala.collection.mutable

object ReadingIncompatibleSignalsTogetherExample:
  private val SampleRate = 100.0   // Hz, device-wide
  private val BlockSize = 10        // channel samples per average
  private val Window = 30           // channel samples to display
  private val Chunk = 5             // samples per read
  private val Format = DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC)

  private case class Row(tick: Long, v0: Double, v1: Double)

  def main(args: Array[String]): Unit =
    val instance = Instance()
    val device = instance.addDevice("daqref://device0")
    device.setPropertyValue("GlobalSampleRate", SampleRate)

    val ch0 = instance.findComponent("Dev/RefDev0/IO/AI/RefCh0").asType(classOf[Channel])
    val ch1 = instance.findComponent("Dev/RefDev0/IO/AI/RefCh1").asType(classOf[Channel])
    ch0.setPropertyValue("Frequency", 5.0)
    ch1.setPropertyValue("Frequency", 10.0)
    val ch0Signal = ch0.getSignals.get(0)
    val ch1Signal = ch1.getSignals.get(0)

    // Statistics block averaging channel 0 in blocks of BlockSize samples.
    // DomainSignalType = 1 (Explicit) makes its output domain an explicit list
    // of ticks -- one tick per average, taken from the block's first raw sample.
    val stats = instance.addFunctionBlock("RefFBModuleStatistics")
    stats.setPropertyValue("BlockSize", BlockSize)
    stats.setPropertyValue("DomainSignalType", 1)
    stats.getInputPorts.get(0).connect(ch0Signal)
    val avgSignal = stats.getSignals.get(0)

    val multiReader = MultiReader(java.util.List.of(ch0Signal, ch1Signal))
    val streamReader = StreamReader(avgSignal)

    // Let both streams warm up so their queues overlap in time.
    Thread.sleep(1000)

    // Fill the display window with many small reads rather than one big one,
    // interleaving the two readers so they advance together.  Channel samples
    // are collected until the window is full; averages go into a tick->value
    // table and keep being read until they have caught up to the end of the
    // window -- an average only appears once its whole block of channel samples
    // has arrived.
    val rows = mutable.ArrayBuffer.empty[Row]
    val avgByTick = mutable.HashMap.empty[Long, Double]
    var newestAvgTick = -1L

    var i = 0
    var done = false
    while i < 200 && !done do
      val windowFull = rows.size >= Window
      if windowFull && newestAvgTick >= rows(Window - 1).tick then
        done = true
      else
        if !windowFull then
          val chunk = multiReader.readWithDomain(Chunk, 1000)
          val ticks = chunk.domainTicks(0)
          for k <- ticks.indices do
            rows += Row(ticks(k), chunk.doubleValues(0)(k), chunk.doubleValues(1)(k))
        val averages = streamReader.readWithDomain(Chunk, 1000)
        val values = averages.doubleValues
        val ticks = averages.domainTicks
        for k <- values.indices do
          avgByTick.put(ticks(k), values(k))
          newestAvgTick = math.max(newestAvgTick, ticks(k))
        i += 1

    // The multi-reader only knows its common domain once it has read, so build
    // the tick->time converter now.
    val channelTime = Daq.domainTimeConverter(multiReader)
    printf("%-14s%14s%14s%16s%n", "time", "channel 0", "channel 1", "avg(channel 0)")
    for i <- 0 until math.min(Window, rows.size) do
      val row = rows(i)
      val average = avgByTick.get(row.tick)
      printf("%-14s%14.4f%14.4f%16s%n",
        Format.format(channelTime.apply(row.tick)),
        row.v0, row.v1,
        average.map(a => "%.4f".format(a)).getOrElse(""))
