// Switch a device between operation modes and lock/unlock it against
// configuration changes.

import com.opendaq.{Unit as _, *}   // hide com.opendaq.Unit so `: Unit` means scala.Unit

object DeviceLockAndOperationModeExample:
  def main(args: Array[String]): Unit =
    val instance = Instance()
    val device = instance.addDevice("daqref://device0")

    val modes = device.getAvailableOperationModes
    val modeLabels = new java.util.StringJoiner(", ")
    modes.forEach(mode => modeLabels.add(label(OperationModeType.fromValue(mode.intValue))))
    println("Available operation modes: " + modeLabels)
    println("Current operation mode:    " + label(device.getOperationMode))

    device.setOperationMode(OperationModeType.OPERATION)
    device.lock()
    println("After setting Operation:   " + label(device.getOperationMode))
    println("Device locked: " + device.isLocked)

    device.unlock()
    device.setOperationMode(OperationModeType.SAFE_OPERATION)
    println()
    println("Device locked: " + device.isLocked)
    println("Final operation mode:      " + label(device.getOperationMode))

  private def label(mode: OperationModeType): String =
    mode.name.toLowerCase.split("_")
      .map(w => w.substring(0, 1).toUpperCase + w.substring(1))
      .mkString
