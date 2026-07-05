// A reader is invalidated when its signal's descriptor changes to a sample
// type the reader cannot convert to its configured read type.  The reader
// needs to be recovered by creating a new one via the from-existing factory.

import com.opendaq.{Unit as _, *}   // hide com.opendaq.Unit so `: Unit` means scala.Unit

object ReaderInvalidationRecoveryExample:
  def main(args: Array[String]): Unit =
    val instance = Instance()
    instance.addDevice("daqref://device0")
    val channel = instance.findComponent("Dev/RefDev0/IO/AI/RefCh0").asType(classOf[Channel])
    channel.setPropertyValue("Waveform", 0)
    channel.setPropertyValue("Amplitude", 0.0)
    channel.setPropertyValue("DC", 2.0)
    channel.setPropertyValue("NoiseAmplitude", 0.0)

    // Average the channel in blocks of 10 samples.  DomainSignalType starts at
    // 0 = Implicit: the output domain is int64 ticks under a linear rule.
    val statistics = instance.addFunctionBlock("RefFBModuleStatistics")
    statistics.getInputPorts.get(0).connect(channel.getSignals.get(0))
    val avgSignal = statistics.getSignals.get(0)

    // Phase 1: read averages with the domain ticks as doubles -- fine while the
    // domain is int64, which converts to float64.
    val reader = StreamReader(avgSignal, SampleType.FLOAT64, SampleType.FLOAT64)
    println("implicit int64 domain, read as float64:")
    showAverages(reader, 4)

    // Phase 2: switch the output domain to 2 = ExplicitRange -- each average's
    // domain value becomes the RangeInt64 tick range of the block it covers,
    // and RangeInt64 has no conversion to float64.  The change reaches the
    // reader as a descriptor-changed event in its packet queue; read hands over
    // the samples queued before the change, then hits the event and throws.
    statistics.setPropertyValue("DomainSignalType", 2)
    var recovered: StreamReader = null
    try
      for _ <- 0 until 50 do reader.readWithDomain(100, 200)
      throw new IllegalStateException("Expected the domain change to invalidate the reader.")
    catch
      case e: ReaderInvalidatedException =>
        println(e.getMessage)
        recovered = StreamReader.createStreamReaderFromExisting(
          e.reader.asInstanceOf[StreamReader], SampleType.FLOAT64, SampleType.INT64)

    println("explicit RangeInt64 domain, read as int64 range starts:")
    showAverages(recovered, 4)

  /**
   * Read `count` averages with their domain values and print them (retrying
   * while the stream warms up); the domain array is float64 or int64 depending
   * on the reader's domain read type.
   */
  private def showAverages(reader: StreamReader, count: Int): Unit =
    var printed = false
    var attempt = 0
    while attempt < 50 && !printed do
      val result = reader.readWithDomain(count, 1000)
      val averages = result.doubleValues
      if averages.length > 0 then
        for i <- averages.indices do
          val tick = result.domain match
            case longs: Array[Long]     => longs(i)
            case doubles: Array[Double] => doubles(i).toLong
            case _                      => 0L
          printf("  tick %d  ->  avg %.3f%n", tick, averages(i))
        printed = true
      attempt += 1
    if !printed then throw new IllegalStateException("The statistics stream produced no averages.")
