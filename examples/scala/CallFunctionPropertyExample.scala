// Call a device's function properties.  getPropertyValue returns a FUNC
// property as a FunctionObject: call it with natural Java arguments and the
// result comes back unboxed.

import com.opendaq.{Unit as _, *}   // hide com.opendaq.Unit so `: Unit` means scala.Unit

object CallFunctionPropertyExample:
  def main(args: Array[String]): Unit =
    val instance = Instance()
    val device = instance.addDevice("daqref://device0")

    val sum = device.getPropertyValue("Protected.Sum").asInstanceOf[FunctionObject]
    println("Protected.Sum(7, 5)   = " + sum.call(7, 5))
    println("Protected.Sum(40, 2)  = " + sum.call(40, 2))
    println("Protected.Sum(100, 1) = "
      + device.getPropertyValue("Protected.Sum").asInstanceOf[FunctionObject].call(100, 1))

    val sumList = device.getPropertyValue("Protected.SumList").asInstanceOf[FunctionObject]
    println("Protected.SumList((1 2 3 4)) = " + sumList.call(java.util.List.of(1, 2, 3, 4)))
    println("Protected.SumList(()) = " + sumList.call(java.util.List.of()))

    try
      sum.call(1, 2, 3)
      println("Unexpected: wrong arity was accepted.")
    catch
      case e: RuntimeException =>
        println()
        println("Wrong arity is rejected: " + e.getMessage)
