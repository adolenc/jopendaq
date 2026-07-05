import java.util.List;
import java.util.Set;

import com.opendaq.*;

/**
 * Walk the whole component tree of the reference device and print it as a
 * text diagram, listing each component's visible properties with their
 * current values.
 */
public class ComponentTreeExample {

    private static final Set<CoreType> SCALAR_TYPES = Set.of(
        CoreType.BOOL, CoreType.INT, CoreType.FLOAT, CoreType.STRING,
        CoreType.RATIO, CoreType.COMPLEX_NUMBER);

    public static void main(String[] args) {
        Instance instance = new Instance();
        instance.addDevice("daqref://device0");

        Device root = instance.getRootDevice();
        System.out.println(root.getName() + " : " + typeLabel(root) + " (" + root.getLocalId() + ")");
        drawProperties(root, "");
        drawChildren(root, "");
    }

    /** Readable type name for a component, e.g. "Channel" or "FunctionBlock". */
    private static String typeLabel(DaqObject component) {
        Class<? extends DaqObject> type = Daq.componentType(component);
        return type != null ? type.getSimpleName() : "Component";
    }

    /** Readable name for a property's core type, e.g. INT -> "Int". */
    private static String typeName(CoreType type) {
        String[] words = type.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    /**
     * Printable value of a property.  getPropertyValue already returns
     * scalars as their natural Java values; structured ones are shown as
     * "&lt;Type&gt;" instead.
     */
    private static String propertyValueString(PropertyObject object, Property property) {
        if (SCALAR_TYPES.contains(property.getValueType())) {
            Object value = object.getPropertyValue(property.getName());
            return value instanceof String s ? "\"" + s + "\"" : String.valueOf(value);
        }
        return "<" + typeName(property.getValueType()) + ">";
    }

    private static void drawProperties(Component component, String prefix) {
        if (!component.isA(PropertyObject.class)) {
            return;
        }
        PropertyObject object = component.asType(PropertyObject.class);
        for (Property property : object.getVisibleProperties()) {
            System.out.println(prefix + "• " + property.getName()
                + " : " + typeName(property.getValueType())
                + " = " + propertyValueString(object, property));
        }
    }

    /** The immediate child components of a component if it is a folder. */
    private static List<Component> children(Component component) {
        if (!component.isA(Folder.class)) {
            return List.of();
        }
        return component.asType(Folder.class).getItems(null);
    }

    private static void drawChildren(Component component, String prefix) {
        List<Component> kids = children(component);
        for (int i = 0; i < kids.size(); i++) {
            Component child = kids.get(i);
            boolean last = i == kids.size() - 1;
            String childPrefix = prefix + (last ? "   " : "│  ");
            System.out.println(prefix + (last ? "└─ " : "├─ ") + child.getName()
                + " : " + typeLabel(child) + " (" + child.getLocalId() + ")");
            drawProperties(child, childPrefix);
            drawChildren(child, childPrefix);
        }
    }
}
