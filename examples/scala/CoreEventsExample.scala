// Subscribe a Scala lambda to the instance's core event stream: each property
// change is reported through the handler until it is unsubscribed.

import com.opendaq.{Unit as _, *}   // hide com.opendaq.Unit so `: Unit` means scala.Unit

object CoreEventsExample:
  def main(args: Array[String]): Unit =
    val instance = Instance()
    instance.addDevice("daqref://device0")

    val channel = instance.findComponent("Dev/RefDev0/IO/AI/RefCh0").asType(classOf[Channel])

    val coreEvent = instance.getContext.getOnCoreEvent
    val callback: EventCallback = (_, eventArgs) =>
      val event = eventArgs.asType(classOf[CoreEventArgs])
      println("  " + event.getEventName + ": " + Daq.unbox(event.getParameters.get("Name")))
    val handler = coreEvent.addHandler(callback)

    // While subscribed, each property change is reported by the handler above.
    println("subscribed:")
    channel.setPropertyValue("Frequency", 25.0)
    channel.setPropertyValue("Amplitude", 7.5)

    // removeHandler unsubscribes; further changes fire nothing.
    coreEvent.removeHandler(handler)
    println("unsubscribed (no lines expected below):")
    channel.setPropertyValue("Frequency", 50.0)
