package com.hmdm.persistence.domain;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** A copy carries every field of its source, so a field newCopy forgets fails here. */
public class ConfigurationCopyTest {

    /** The source's identity, and what a request or an answer carries without storing it. */
    private static final List<String> NOT_COPIED = Arrays.asList("id", "qrCodeKey", "selected", "baseUrl",
            "filesToRemove", "backgroundImageAction", "locationServicesAction");

    @Test
    public void aCopyCarriesEveryField() throws Exception {
        for (Field f : Configuration.class.getDeclaredFields()) {
            if (Modifier.isFinal(f.getModifiers()) || NOT_COPIED.contains(f.getName())) continue;
            f.setAccessible(true);
            Configuration source = new Configuration();
            f.set(source, sample(f.getType()));
            assertNotEquals(f.getName() + " sample", f.get(new Configuration()), f.get(source));
            assertEquals(f.getName(), f.get(source), f.get(source.newCopy()));
        }
    }

    private static Object sample(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return true;
        if (type == int.class || type == Integer.class) return 7;
        if (type == Double.class) return 0.5;
        if (type == String.class) return "x";
        if (type == List.class) return Collections.singletonList(null);
        if (type.isEnum()) return type.getEnumConstants()[type.getEnumConstants().length - 1];
        return JsonNodeFactory.instance.objectNode().put("a", 1);
    }
}
