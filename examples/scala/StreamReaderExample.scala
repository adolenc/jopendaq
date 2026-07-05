// The minimal openDAQ flow: connect to the reference (simulator) device, take
// its first analog channel's signal, and stream samples off it.

import com.opendaq.{Unit as _, *}   // hide com.opendaq.Unit so `: Unit` means scala.Unit

object StreamReaderExample:
  def main(args: Array[String]): Unit =
    val instance = Instance()
    instance.addDevice("daqref://device0")

    val channel = instance.findComponent("Dev/RefDev0/IO/AI/RefCh0").asType(classOf[Channel])
    val signal = channel.getSignals.get(0)
    val reader = StreamReader(signal)

    println("some samples: " + java.util.Arrays.toString(reader.read(100, 1000)))
    println("and more samples: " + java.util.Arrays.toString(reader.read(100, 1000)))
    println("and more still: " + java.util.Arrays.toString(reader.read(100, 1000)))
