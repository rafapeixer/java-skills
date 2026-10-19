package com.example.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Turns the layout rules into a failing build instead of a review comment. */
class LayoutTest {

    private static final String ROOT = "com.example.shop";
    private static final Set<String> BANNED = Set.of("util", "utils", "common", "helpers", "shared", "base",
            "model", "models", "service", "services", "repository", "repositories", "controller", "controllers");

    @Test
    void eachFeatureExposesOnlyItsApi() throws Exception {
        Map<String, Set<String>> publicTypes = new TreeMap<>();
        for (Class<?> c : mainClasses()) {
            if (Modifier.isPublic(c.getModifiers())) {
                publicTypes.computeIfAbsent(c.getPackageName(), p -> new TreeSet<>()).add(c.getSimpleName());
            }
        }
        assertEquals(Map.of(
                ROOT, Set.of("ShopApplication"),
                ROOT + ".billing", Set.of("Billing"),
                ROOT + ".orders", Set.of("Order", "Orders")), publicTypes);
    }

    @Test
    void noPackageIsNamedAfterALayerOrACatchAll() throws Exception {
        for (Class<?> c : mainClasses()) {
            for (String segment : c.getPackageName().split("\\.")) {
                assertFalse(BANNED.contains(segment), c.getName() + " is in a layer or catch-all package");
            }
        }
    }

    @Test
    void ordersDoesNotDependOnBilling() throws Exception {
        for (Class<?> c : mainClasses()) {
            if (!c.getPackageName().equals(ROOT + ".orders")) {
                continue;
            }
            Stream.concat(
                    Stream.of(c.getDeclaredFields()).map(f -> f.getType()),
                    Stream.of(c.getDeclaredConstructors()).flatMap(k -> Stream.of(k.getParameterTypes())))
                    .forEach(t -> assertFalse(t.getPackageName().endsWith(".billing"), c + " depends on billing"));
        }
    }

    /** Every top-level class compiled from src/main/java. */
    private static List<Class<?>> mainClasses() throws Exception {
        Path classes = Path.of(ShopApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Class<?>> loaded = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path p : files.toList()) {
                String name = classes.relativize(p).toString().replace('\\', '/');
                if (name.endsWith(".class") && !name.contains("$")) {
                    loaded.add(Class.forName(name.substring(0, name.length() - 6).replace('/', '.')));
                }
            }
        }
        assertTrue(loaded.size() >= 6, "found only " + loaded);
        return loaded;
    }
}
