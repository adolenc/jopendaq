// Batch several property writes into one update per object: values set inside
// the batch are staged rather than applied -- reads still return the old
// values and no property events fire until the batch closes -- and committed
// atomically per object when the resources scope ends.

import com.opendaq.{Unit as _, *}   // hide com.opendaq.Unit so `: Unit` means scala.Unit
import scala.util.Using

object BatchUpdatePropertiesExample:
  def main(args: Array[String]): Unit =
    val instance = Instance()
    val device = instance.addDevice("daqref://device0")
    val ch0 = device.getChannels.get(0)
    val ch1 = device.getChannels.get(1)

    showSettings("Before:          ", ch0)
    showSettings("Before:          ", ch1)

    // batchUpdates returns an AutoCloseable; Using commits it (per object) as
    // the scope ends, staging every write made inside.
    Using.Manager { use =>
      use(ch0.batchUpdates())
      use(ch1.batchUpdates())
      ch0.setPropertyValue("Amplitude", 2.5)
      ch0.setPropertyValue("Frequency", 25.0)
      ch0.setPropertyValue("Waveform", 1)
      ch1.setPropertyValue("Amplitude", 4.0)
      ch1.setPropertyValue("Frequency", 50.0)
      ch1.setPropertyValue("Waveform", 2)
      // Still inside the batch: the writes above are staged but not applied.
      showSettings("During (staged): ", ch0)
      showSettings("During (staged): ", ch1)
    }.get

    showSettings("After the batch: ", ch0)
    showSettings("After the batch: ", ch1)

  private def showSettings(label: String, channel: Channel): Unit =
    println(label + channel.getName
      + ":  Amplitude=" + channel.getPropertyValue("Amplitude")
      + "  Frequency=" + channel.getPropertyValue("Frequency")
      + "  Waveform=" + channel.getPropertyValue("Waveform"))
