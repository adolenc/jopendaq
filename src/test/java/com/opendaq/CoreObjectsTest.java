package com.opendaq;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import com.opendaq.lowlevel.Ffi;

import static org.junit.jupiter.api.Assertions.*;

/**
 * High-level core objects coverage: properties, property objects, callable
 * info, coercers/validators, and property events — ported from the reference
 * bindings' coreobjects suite.
 */
class CoreObjectsTest {

    @Test
    void argumentAndCallableInfo() {
        ArgumentInfo argumentInfo = new ArgumentInfo("test_argument", CoreType.INT);
        CallableInfo callableInfo = new CallableInfo(List.of(argumentInfo), CoreType.INT, true);
        assertEquals("test_argument", argumentInfo.getName());
        assertEquals(CoreType.INT, argumentInfo.getType());
        assertEquals(1, callableInfo.getArguments().size());
        assertEquals(CoreType.INT, callableInfo.getReturnType());
        assertTrue(callableInfo.isConst());
    }

    @Test
    void functionPropertyFromLambda() {
        // End to end: a FUNC property whose value is a lambda-backed Function
        // reads back through getPropertyValue as a callable FunctionObject,
        // and a call round-trips Java -> openDAQ -> Java.
        PropertyObject object = new PropertyObject();
        CallableInfo info = new CallableInfo(
            List.of(new ArgumentInfo("a", CoreType.INT), new ArgumentInfo("b", CoreType.INT)),
            CoreType.INT, false);
        object.addProperty(Property.createFunctionProperty("Sum", info, true));
        object.setPropertyValue("Sum", new FunctionObject(params -> (Long) params[0] + (Long) params[1]));

        Object sum = object.getPropertyValue("Sum");
        assertInstanceOf(FunctionObject.class, sum);
        assertEquals(5L, ((FunctionObject) sum).call(2, 3));
        assertEquals(42L, ((FunctionObject) sum).call(40, 2));
        // The declared arity from the callable info is enforced.
        assertThrows(IllegalArgumentException.class, () -> ((FunctionObject) sum).call(1));
    }

    @Test
    void authenticationProvider() {
        User user = new User("test_user", "test_hash", List.of("guest"));
        AuthenticationProvider provider =
            AuthenticationProvider.createStaticAuthenticationProvider(true, List.of(user));
        assertEquals("test_user", user.getUsername());
        assertInstanceOf(List.class, user.getGroups());
        assertNotNull(provider.authenticateAnonymous());
        assertEquals("test_user", provider.authenticate("test_user", "test_hash").getUsername());
        assertEquals("test_user", provider.findUser("test_user").getUsername());
    }

    @Test
    void propertyBuilders() {
        Property property = Property.createIntProperty("test_property", 10, true);
        PropertyBuilder builder = PropertyBuilder.createIntPropertyBuilder("test_property", 10);
        builder.setVisible(true);
        Property built = builder.build();
        assertEquals("test_property", property.getName());
        assertEquals(10L, built.getDefaultValue().unbox());
        assertTrue(built.getVisible());

        PropertyObject propertyObject = new PropertyObject();
        propertyObject.addProperty(property);
        assertTrue(propertyObject.hasProperty("test_property"));
        assertEquals(10L, propertyObject.getProperty("test_property").getDefaultValue().unbox());

        PropertyObjectClassBuilder classBuilder = new PropertyObjectClassBuilder("test_property_class");
        classBuilder.addProperty(Property.createIntProperty("test_property", 10, true));
        PropertyObjectClass propertyClass = classBuilder.build();
        assertTrue(propertyClass.hasProperty("test_property"));
        assertEquals("test_property", propertyClass.getProperty("test_property").getName());

        propertyObject.removeProperty("test_property");
        assertFalse(propertyObject.hasProperty("test_property"));
    }

    @Test
    void factoryValuesArePlainJava() {
        Property intProperty = Property.createIntProperty("test_property", 10, true);
        Property floatProperty = Property.createFloatProperty("test_float", 1.5, true);
        Property boolProperty = Property.createBoolProperty("test_bool", false, true);
        DataRule linearRule = DataRule.createLinearDataRule(2, 0);
        assertEquals(10L, intProperty.getDefaultValue().unbox());
        assertEquals(1.5, floatProperty.getDefaultValue().unbox());
        assertEquals(false, boolProperty.getDefaultValue().unbox());
        assertTrue(linearRule.isA(DataRule.class));
    }

    @Test
    void propertyValueUnboxesScalars() {
        // getPropertyValue converts a scalar property to its natural Java
        // value; an object property has no scalar value and stays a wrapper.
        PropertyObject object = new PropertyObject();
        object.addProperty(Property.createIntProperty("anint", 10, true));
        object.addProperty(Property.createFloatProperty("afloat", 1.5, true));
        object.addProperty(Property.createStringProperty("astring", "hi", true));
        object.addProperty(Property.createBoolProperty("abool", true, true));
        object.addProperty(Property.createRatioProperty("aratio", new RatioValue(1, 2), true));
        object.addProperty(Property.createObjectProperty("anobject", new PropertyObject()));

        assertEquals(10L, object.getPropertyValue("anint"));
        assertEquals(1.5, object.getPropertyValue("afloat"));
        assertEquals("hi", object.getPropertyValue("astring"));
        assertEquals(true, object.getPropertyValue("abool"));
        assertEquals(new RatioValue(1, 2), object.getPropertyValue("aratio"));
        Object nested = object.getPropertyValue("anobject");
        assertInstanceOf(DaqObject.class, nested);
        assertTrue(((DaqObject) nested).isA(PropertyObject.class));
    }

    @Test
    void evalCoercerValidator() {
        PropertyObject propertyObject = new PropertyObject();
        propertyObject.addProperty(Property.createIntProperty("test_property", 10, true));
        propertyObject.addProperty(Property.createReferenceProperty(
            "ref_property", new EvalValue("%test_property")));

        assertEquals(10L, propertyObject.getPropertyValue("ref_property"));

        Coercer coercer = new Coercer("value + 2");
        assertEquals("value + 2", coercer.getEval());
        assertEquals(12L, coercer.coerce(propertyObject, 10).unbox());

        Validator validator = new Validator("value > 5");
        assertDoesNotThrow(() -> validator.validate(propertyObject, 10));
        assertThrows(OpenDaqException.class, () -> validator.validate(propertyObject, 5));
        Ffi.clearErrorInfo();
    }

    @Test
    void propertyValueEventArgs() {
        Property property = Property.createIntProperty("test_property", 10, true);
        PropertyValueEventArgs eventArgs = new PropertyValueEventArgs(
            property, new IntegerObject(30), new IntegerObject(20), PropertyEventType.UPDATE, false);
        assertEquals("test_property", eventArgs.getProperty().getName());
        assertEquals(30L, eventArgs.getValue().unbox());
        assertEquals(20L, eventArgs.getOldValue().unbox());
        assertEquals(PropertyEventType.UPDATE, eventArgs.getPropertyEventType());
        assertFalse(eventArgs.getIsUpdating());
    }

    @Test
    void selectionProperty() {
        Property property = Property.createSelectionProperty(
            "Gain", List.of("Low", "Mid", "High"), 1, true);
        assertEquals(List.of("Low", "Mid", "High"), property.getSelectionValues().unbox());

        PropertyBuilder builder = PropertyBuilder.createSelectionPropertyBuilder(
            "Gain", List.of("Low", "Mid", "High"), 0);
        assertEquals(List.of("Low", "Mid", "High"), builder.getSelectionValues().unbox());

        PropertyObject object = new PropertyObject();
        object.addProperty(property);
        assertEquals(1L, object.getPropertyValue("Gain"));
        object.setPropertyValue("Gain", 2);
        assertEquals(2L, object.getPropertyValue("Gain"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sparseSelectionProperty() {
        Property property = Property.createSparseSelectionProperty(
            "Mode", Map.of(1, "Control", 3, "ViewOnly", 7, "Other"), 3, true);
        Map<Object, Object> choices = (Map<Object, Object>) property.getSelectionValues().unbox();
        assertEquals(3, choices.size());
        assertEquals("Control", choices.get(1L));
        assertEquals("ViewOnly", choices.get(3L));
        assertEquals("Other", choices.get(7L));

        PropertyObject object = new PropertyObject();
        object.addProperty(property);
        assertEquals(3L, object.getPropertyValue("Mode"));
    }

    @Test
    void endUpdateEvent() {
        PropertyObject propertyObject = new PropertyObject();
        boolean[] updateEnded = {false};
        propertyObject.getOnEndUpdate().addHandler((sender, args) -> updateEnded[0] = true);
        propertyObject.beginUpdate();
        propertyObject.endUpdate();
        assertTrue(updateEnded[0]);
    }

    @Test
    void batchUpdatesScope() throws Exception {
        PropertyObject object = new PropertyObject();
        object.addProperty(Property.createIntProperty("v", 1, true));
        try (AutoCloseable batch = object.batchUpdates()) {
            object.setPropertyValue("v", 5);
            // Still inside the batch: the write is staged but not applied.
            assertEquals(1L, object.getPropertyValue("v"));
        }
        assertEquals(5L, object.getPropertyValue("v"));
    }
}
