// Read a dimensioned ("2-D") signal: an FFT function block turns the channel
// stream into spectra, where each sample is a whole vector of amplitude bins.
// readMatrix returns a (samples x bins) matrix, and the frequency axis comes
// off the value descriptor's single dimension.

import com.opendaq.{Unit as _, *}   // hide com.opendaq.Unit so `: Unit` means scala.Unit

object StreamReader2dSignalExample:
  def main(args: Array[String]): Unit =
    val instance = Instance()
    instance.addDevice("daqref://device0")

    val channel = instance.findComponent("Dev/RefDev0/IO/AI/RefCh0").asType(classOf[Channel])
    channel.setPropertyValue("Waveform", 0)          // 0 = Sine
    channel.setPropertyValue("Frequency", 125.0)
    channel.setPropertyValue("Amplitude", 5.0)
    channel.setPropertyValue("NoiseAmplitude", 0.1)

    val fft = instance.addFunctionBlock("RefFBModuleFFT")
    fft.setPropertyValue("BlockSize", 16)
    fft.getInputPorts.get(0).connect(channel.getSignals.get(0))
    val signal = fft.getSignals.get(0)

    // Wait for the block to publish its output descriptor, then read the
    // frequency axis off the value descriptor's single dimension.
    Thread.sleep(1000)
    val dimension = signal.getDescriptor.getDimensions.get(0)
    val axis = dimension.getLabels

    // Read 5 samples.  Each sample is a full spectrum, so readMatrix returns a
    // (samples x bins) matrix; retry until 5 rows have arrived (the first reads
    // may come back short while the stream warms up).
    val reader = StreamReader(signal)
    var spectra: Array[Array[Double]] = Array.empty
    var attempt = 0
    while attempt < 50 do
      spectra = reader.readMatrix(5, 1000)
      if spectra.length == 5 then attempt = 50 else attempt += 1

    // Print the axis down the rows and one column of amplitudes per sample.  The
    // 125 Hz tone dominates a single bin (~5, our amplitude) while noise fills
    // the rest with small values.  The reference block labels its bins one step
    // (31.25 Hz) below the true bin centre, so the tone lands in the 93.75 Hz row.
    println(dimension.getName + " spectrum, " + axis.size + " bins, " + dimension.getUnit.getSymbol)
    println()
    printf("%12s", "freq (Hz)")
    for i <- spectra.indices do printf("%14s", "sample " + (i + 1))
    println()
    for bin <- 0 until axis.size do
      printf("%12.2f", axis.get(bin).asInstanceOf[Number].doubleValue)
      for spectrum <- spectra do printf("%14.4f", spectrum(bin))
      println()
