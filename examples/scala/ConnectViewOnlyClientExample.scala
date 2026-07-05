// Connect to a networked openDAQ device as a view-only client (reads work,
// configuration writes are refused).  Expects a device/simulator at
// daq.nd://127.0.0.1 or at $OPENDAQ_DEVICE.

import com.opendaq.{Unit as _, *}   // hide com.opendaq.Unit so `: Unit` means scala.Unit

object ConnectViewOnlyClientExample:
  def main(args: Array[String]): Unit =
    val instance = Instance()

    val config = instance.createDefaultAddDeviceConfig()
    val general = config.getPropertyValue("General").asInstanceOf[DaqObject].asType(classOf[PropertyObject])
    val clientType = general.getProperty("ClientType")
    val options = Daq.unbox(clientType.getSelectionValues)
    println("Available ClientType options:")
    options match
      case map: java.util.Map[?, ?] =>
        val sorted = new java.util.TreeMap[java.lang.Long, AnyRef]()
        map.entrySet.forEach { e =>
          sorted.put(e.getKey.asInstanceOf[Number].longValue, e.getValue.asInstanceOf[AnyRef])
        }
        sorted.forEach((key, value) => println("  " + key + " = " + value))
      case list: java.util.List[?] =>
        for i <- 0 until list.size do println("  " + i + " = " + list.get(i))
      case _ =>

    config.setPropertyValue("General.ClientType", 2)   // 2 = view-only
    val connectionString = Option(System.getenv("OPENDAQ_DEVICE")).getOrElse("daq.nd://127.0.0.1")

    try
      val device = instance.addDevice(connectionString, config)
      println("Connected to " + device.getName + " as a view-only client.")
      println("Visible signals: " + device.getSignalsRecursive.size)
      val propertyName = firstWritablePropertyName(device)
      if propertyName == null then
        println("Device exposes no writable property to probe.")
      else
        try
          device.setPropertyValue(propertyName, device.getPropertyValue(propertyName))
          println("Unexpected: writing \"" + propertyName + "\" was allowed.")
        catch
          case e: RuntimeException =>
            println("Write to \"" + propertyName + "\" refused, as expected for view-only: " + e.getMessage)
    catch
      case e: RuntimeException =>
        println("Could not connect to " + connectionString + ": " + e.getMessage)
        println("Start an openDAQ device/simulator there, or set OPENDAQ_DEVICE.")

  /**
   * Name of the object's first visible, non-read-only, non-callable property,
   * or null.  Used to probe whether the connection actually refuses writes.
   */
  private def firstWritablePropertyName(obj: PropertyObject): String =
    obj.getVisibleProperties.stream
      .filter(p => !p.getReadOnly && p.getValueType != CoreType.FUNC && p.getValueType != CoreType.PROC)
      .map(_.getName)
      .findFirst
      .orElse(null)
