package com.close.hook.ads.hook.util;

import java.lang.reflect.Array;
import java.util.List;

/** Resolves complete signatures and converts user literals to the declared JVM type. */
public final class HookValueParser {
    private HookValueParser() {}

    public static Class<?>[] parameterTypes(List<String> names, ClassLoader loader) {
        if (names == null || names.isEmpty()) return new Class<?>[0];
        Class<?>[] types = new Class<?>[names.size()];
        for (int i = 0; i < names.size(); i++) {
            try {
                types[i] = resolveType(names.get(i), loader);
                if (types[i] == void.class) throw new IllegalArgumentException("void is not a parameter type");
            } catch (ClassNotFoundException | IllegalArgumentException error) {
                throw new IllegalArgumentException("Cannot resolve parameter " + i + ": " + names.get(i), error);
            }
        }
        return types;
    }

    public static Class<?> resolveType(String name, ClassLoader loader) throws ClassNotFoundException {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Empty type name");
        String trimmed = name.trim();
        switch (trimmed) {
            case "boolean": return boolean.class;
            case "byte": return byte.class;
            case "short": return short.class;
            case "char": return char.class;
            case "int": return int.class;
            case "long": return long.class;
            case "float": return float.class;
            case "double": return double.class;
            case "void": return void.class;
            default:
                if (trimmed.endsWith("[]")) {
                    Class<?> component = resolveType(trimmed.substring(0, trimmed.length() - 2), loader);
                    if (component == void.class) throw new IllegalArgumentException("void[] is not a valid type");
                    return Array.newInstance(component, 0).getClass();
                }
                // Resolve without running the target class's static initializer.
                return Class.forName(trimmed, false, loader);
        }
    }

    public static Object coerce(String literal, Class<?> type) {
        if (type == void.class) return null;
        String value = literal == null ? null : literal.trim();
        if (value == null || value.equalsIgnoreCase("null")) return defaultValue(type);
        if (type == String.class || type == CharSequence.class) return value;
        if (type == boolean.class || type == Boolean.class) {
            if (value.equalsIgnoreCase("true")) return true;
            if (value.equalsIgnoreCase("false")) return false;
            throw new IllegalArgumentException("Expected a boolean, received: " + value);
        }
        if (type == byte.class || type == Byte.class) return Byte.valueOf(value);
        if (type == short.class || type == Short.class) return Short.valueOf(value);
        if (type == int.class || type == Integer.class) return Integer.valueOf(value);
        if (type == long.class || type == Long.class) return Long.valueOf(value);
        if (type == float.class || type == Float.class) return Float.valueOf(value);
        if (type == double.class || type == Double.class) return Double.valueOf(value);
        if (type == char.class || type == Character.class) {
            if (value.length() == 1) return value.charAt(0);
            throw new IllegalArgumentException("Expected a single character");
        }
        Object inferred = inferLiteral(value);
        if (type.isInstance(inferred)) return inferred;
        throw new IllegalArgumentException("Cannot convert literal to " + type.getTypeName() + ": " + value);
    }

    public static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        throw new IllegalArgumentException("Unsupported primitive: " + type);
    }

    private static Object inferLiteral(String value) {
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        try { return Integer.valueOf(value); } catch (NumberFormatException ignored) {}
        try { return Long.valueOf(value); } catch (NumberFormatException ignored) {}
        try { return Double.valueOf(value); } catch (NumberFormatException ignored) {}
        return value;
    }
}
