// Build a signal by hand and read it back with wall-clock timestamps.
//
// The trick for making the domain timestamps line up with the PC clock is NOT
// to move the origin to "now": the origin stays pinned at the Unix epoch, and
// the current time is carried entirely by the integer domain ticks.  With a
// tick resolution of 1/1000 s (one tick = one millisecond) a sample's absolute
// time is origin + tick/1000 s, so a tick equal to the current Unix time in
// milliseconds reads back as the current wall-clock time.  Spacing the ticks
// 200 apart (200 ms) then yields exactly 5 samples per second.

import com.opendaq.{Unit as _, *}   // hide com.opendaq.Unit so `: Unit` means scala.Unit
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter

object CreateSignalExample:
  private val SamplesPerSecond = 5
  private val TickResolution = RatioValue(1, 1000)         // seconds per tick (1 ms)
  private val TicksPerSample = 1000 / SamplesPerSecond     // 200 ticks = 200 ms
  private val Format = DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC)

  def main(args: Array[String]): Unit =
    val instance = Instance()

    val domainBuilder = DataDescriptorBuilder()
    domainBuilder.setSampleType(SampleType.INT64)
    domainBuilder.setName("time")
    // Origin stays at the epoch; the wall-clock time lives in the ticks.
    domainBuilder.setOrigin("1970-01-01T00:00:00Z")
    domainBuilder.setTickResolution(TickResolution)
    domainBuilder.setUnit(com.opendaq.Unit(-1, "s", "second", "time"))
    domainBuilder.setRule(DataRule.createLinearDataRule(TicksPerSample, 0))
    val domainDescriptor = domainBuilder.build()

    val valueBuilder = DataDescriptorBuilder()
    valueBuilder.setSampleType(SampleType.FLOAT64)
    valueBuilder.setName("values")
    val valueDescriptor = valueBuilder.build()

    val domainSignal = SignalConfig(instance.getContext, null, "time", null)
    domainSignal.setDescriptor(domainDescriptor)
    val signal = SignalConfig(instance.getContext, null, "values", null)
    signal.setDescriptor(valueDescriptor)
    signal.setDomainSignal(domainSignal)

    val reader = StreamReader(signal, SampleType.FLOAT64, SampleType.INT64,
      ReadMode.SCALED, ReadTimeoutType.ANY, false)

    // Send samples as one packet whose implicit domain ticks start at `offset`
    // and advance by TicksPerSample per sample.
    def sendChunk(offset: Long, samples: Array[Double]): Unit =
      val domainPacket = DataPacket(domainDescriptor, samples.length, offset)
      val packet = DataPacket.createDataPacketWithDomain(domainPacket, valueDescriptor, samples.length, 0)
      packet.setData(samples)
      signal.sendPacket(packet)

    // Stream a few seconds of a 5 Hz signal in real time.  Each one-second
    // batch is one packet of 5 samples; the ticks stay contiguous across
    // batches (batch k starts at startTick + k*1000 ms), so the whole run is an
    // unbroken 5 Hz stream anchored to when the program started.
    val batches = 3
    val startTick = Instant.now.toEpochMilli
    val toTimestamp = Daq.domainTimeConverter(signal)
    println("Streaming " + SamplesPerSecond + " samples/second, starting at "
      + Format.format(toTimestamp.apply(startTick)))

    for batch <- 0 until batches do
      val baseSample = batch * SamplesPerSecond
      val offset = startTick + baseSample.toLong * TicksPerSample
      val samples = new Array[Double](SamplesPerSecond)
      for i <- samples.indices do
        samples(i) = Math.sin(2 * Math.PI * ((baseSample + i) / 25.0))
      sendChunk(offset, samples)

      val read = reader.readWithDomain(100, 1000)
      val values = read.doubleValues
      val ticks = read.domainTicks
      for i <- values.indices do
        printf("  %s  ->  %.4f%n", Format.format(toTimestamp.apply(ticks(i))), values(i))
      Thread.sleep(1000)
