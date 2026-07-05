// Finding components with search filters.
//
// The getter methods for a device's tree -- getChannels, getSignals,
// getDevices, getFunctionBlocks, and a folder's getItems -- all take an
// optional search filter.  A filter answers two questions about every
// component the search walks over: "accept this one into the result?" and
// "descend into its children?".
//
// With no filter the getters return only the immediate, visible children, so
// getChannels of the top-level device below finds nothing -- the channels live
// one device deeper.  A createRecursiveSearchFilter wrapper makes the search
// descend.  Filters compose: createAndSearchFilter, createOrSearchFilter and
// createNotSearchFilter take other filters as their arguments.  The recursive
// wrapper must be the outermost one -- never nest it inside another filter.

import com.opendaq.{Unit as _, *}   // hide com.opendaq.Unit so `: Unit` means scala.Unit

object SearchFilterExample:
  def main(args: Array[String]): Unit =
    val instance = Instance()
    instance.addDevice("daqref://device0")
    val root = instance.getRootDevice

    // Give a couple of channels some tags, so the tag filter has something to
    // match (RefCh0/RefCh1 come from the reference device unlabelled).
    root.getChannels(SearchFilter.createRecursiveSearchFilter(SearchFilter.createAnySearchFilter()))
      .forEach { channel =>
        val tags = channel.getTags.asType(classOf[TagsPrivate])
        tags.add("analog")
        if channel.getLocalId == "RefCh0" then tags.add("primary")
      }

    show("channels, no filter (immediate children only)",
      root.getChannels)

    show("channels, recursive(any)",
      root.getChannels(SearchFilter.createRecursiveSearchFilter(SearchFilter.createAnySearchFilter())))

    show("channels, recursive(id = RefCh1)",
      root.getChannels(SearchFilter.createRecursiveSearchFilter(
        SearchFilter.createLocalIdSearchFilter("RefCh1"))))

    show("signals, recursive(id = AI0 OR id = AI1)",
      root.getSignals(SearchFilter.createRecursiveSearchFilter(
        SearchFilter.createOrSearchFilter(
          SearchFilter.createLocalIdSearchFilter("AI0"),
          SearchFilter.createLocalIdSearchFilter("AI1")))))

    show("channels, recursive(NOT id = RefCh1)",
      root.getChannels(SearchFilter.createRecursiveSearchFilter(
        SearchFilter.createNotSearchFilter(
          SearchFilter.createLocalIdSearchFilter("RefCh1")))))

    show("channels, recursive(tag = analog AND NOT id = RefCh1)",
      root.getChannels(SearchFilter.createRecursiveSearchFilter(
        SearchFilter.createAndSearchFilter(
          SearchFilter.createRequiredTagsSearchFilter(java.util.List.of("analog")),
          SearchFilter.createNotSearchFilter(
            SearchFilter.createLocalIdSearchFilter("RefCh1"))))))

  /** Print components by their local id under a label. */
  private def show(label: String, components: java.util.List[? <: Component]): Unit =
    val ids =
      if components.isEmpty then "(none)"
      else
        val joiner = new java.util.StringJoiner(", ")
        for i <- 0 until components.size do joiner.add(components.get(i).getLocalId)
        joiner.toString
    println(label)
    println("    => " + ids)
    println()
