import java.util.List;
import java.util.stream.Collectors;

import com.opendaq.*;

/**
 * Finding components with search filters.
 *
 * <p>The getter methods for a device's tree — getChannels, getSignals,
 * getDevices, getFunctionBlocks, and a folder's getItems — all take an
 * optional search filter.  A filter answers two questions about every
 * component the search walks over: "accept this one into the result?" and
 * "descend into its children?".
 *
 * <p>With no filter the getters return only the immediate, visible children,
 * so getChannels of the top-level device below finds nothing — the channels
 * live one device deeper.  A createRecursiveSearchFilter wrapper makes the
 * search descend.
 *
 * <p>Filters compose by construction: createAndSearchFilter,
 * createOrSearchFilter and createNotSearchFilter take other filters as their
 * arguments.  The recursive wrapper must be the outermost one — never nest it
 * inside another filter.
 */
public class SearchFilterExample {

    public static void main(String[] args) {
        Instance instance = new Instance();
        instance.addDevice("daqref://device0");
        Device root = instance.getRootDevice();

        // Give a couple of channels some tags, so the tag filter has
        // something to match (RefCh0/RefCh1 come from the reference device
        // unlabelled).
        for (Channel channel : root.getChannels(
                SearchFilter.createRecursiveSearchFilter(SearchFilter.createAnySearchFilter()))) {
            TagsPrivate tags = channel.getTags().asType(TagsPrivate.class);
            tags.add("analog");
            if (channel.getLocalId().equals("RefCh0")) {
                tags.add("primary");
            }
        }

        show("channels, no filter (immediate children only)",
             root.getChannels());

        show("channels, recursive(any)",
             root.getChannels(SearchFilter.createRecursiveSearchFilter(
                 SearchFilter.createAnySearchFilter())));

        show("channels, recursive(id = RefCh1)",
             root.getChannels(SearchFilter.createRecursiveSearchFilter(
                 SearchFilter.createLocalIdSearchFilter("RefCh1"))));

        show("signals, recursive(id = AI0 OR id = AI1)",
             root.getSignals(SearchFilter.createRecursiveSearchFilter(
                 SearchFilter.createOrSearchFilter(
                     SearchFilter.createLocalIdSearchFilter("AI0"),
                     SearchFilter.createLocalIdSearchFilter("AI1")))));

        show("channels, recursive(NOT id = RefCh1)",
             root.getChannels(SearchFilter.createRecursiveSearchFilter(
                 SearchFilter.createNotSearchFilter(
                     SearchFilter.createLocalIdSearchFilter("RefCh1")))));

        show("channels, recursive(tag = analog AND NOT id = RefCh1)",
             root.getChannels(SearchFilter.createRecursiveSearchFilter(
                 SearchFilter.createAndSearchFilter(
                     SearchFilter.createRequiredTagsSearchFilter(List.of("analog")),
                     SearchFilter.createNotSearchFilter(
                         SearchFilter.createLocalIdSearchFilter("RefCh1"))))));
    }

    /** Print components by their local id under a label. */
    private static void show(String label, List<? extends Component> components) {
        String ids = components.isEmpty() ? "(none)"
            : components.stream().map(Component::getLocalId).collect(Collectors.joining(", "));
        System.out.println(label);
        System.out.println("    => " + ids);
        System.out.println();
    }
}
