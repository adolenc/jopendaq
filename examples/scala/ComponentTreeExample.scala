// Walk the whole component tree of the reference device and print it as a
// text diagram, listing each component's visible properties with their
// current values.

import com.opendaq.{Unit as _, *}   // hide com.opendaq.Unit so `: Unit` means scala.Unit

object ComponentTreeExample:
  private val ScalarTypes = java.util.Set.of(
    CoreType.BOOL, CoreType.INT, CoreType.FLOAT, CoreType.STRING,
    CoreType.RATIO, CoreType.COMPLEX_NUMBER)

  def main(args: Array[String]): Unit =
    val instance = Instance()
    instance.addDevice("daqref://device0")

    val root = instance.getRootDevice
    println(root.getName + " : " + typeLabel(root) + " (" + root.getLocalId + ")")
    drawProperties(root, "")
    drawChildren(root, "")

  /** Readable type name for a component, e.g. "Channel" or "FunctionBlock". */
  private def typeLabel(component: DaqObject): String =
    val t = Daq.componentType(component)
    if t != null then t.getSimpleName else "Component"

  /** Readable name for a property's core type, e.g. INT -> "Int". */
  private def typeName(coreType: CoreType): String =
    coreType.name.toLowerCase.split("_")
      .map(w => w.substring(0, 1).toUpperCase + w.substring(1))
      .mkString

  /**
   * Printable value of a property.  getPropertyValue already returns scalars
   * as their natural Java values; structured ones are shown as "<Type>".
   */
  private def propertyValueString(obj: PropertyObject, property: Property): String =
    if ScalarTypes.contains(property.getValueType) then
      obj.getPropertyValue(property.getName) match
        case s: String => "\"" + s + "\""
        case v         => String.valueOf(v)
    else "<" + typeName(property.getValueType) + ">"

  private def drawProperties(component: Component, prefix: String): Unit =
    if component.isA(classOf[PropertyObject]) then
      val obj = component.asType(classOf[PropertyObject])
      obj.getVisibleProperties.forEach { property =>
        println(prefix + "• " + property.getName
          + " : " + typeName(property.getValueType)
          + " = " + propertyValueString(obj, property))
      }

  /** The immediate child components of a component if it is a folder. */
  private def children(component: Component): java.util.List[? <: Component] =
    if component.isA(classOf[Folder]) then component.asType(classOf[Folder]).getItems(null)
    else java.util.List.of()

  private def drawChildren(component: Component, prefix: String): Unit =
    val kids = children(component)
    for i <- 0 until kids.size do
      val child = kids.get(i)
      val last = i == kids.size - 1
      val childPrefix = prefix + (if last then "   " else "│  ")
      println(prefix + (if last then "└─ " else "├─ ") + child.getName
        + " : " + typeLabel(child) + " (" + child.getLocalId + ")")
      drawProperties(child, childPrefix)
      drawChildren(child, childPrefix)
