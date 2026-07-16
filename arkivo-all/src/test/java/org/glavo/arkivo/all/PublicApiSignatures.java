// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/// Produces deterministic source and JVM signatures for the reviewed exported API types.
@NotNullByDefault
final class PublicApiSignatures {
    /// The resource containing all reviewed exported public and protected API type names.
    static final String TYPE_RESOURCE = "org/glavo/arkivo/all/public-api-types.txt";

    /// The resource containing the complete reviewed public signature baseline.
    static final String SIGNATURE_RESOURCE = "org/glavo/arkivo/all/public-api-signatures.txt";

    /// Prevents utility class instantiation.
    private PublicApiSignatures() {
    }

    /// Collects sorted API signatures for every reviewed public or protected API type.
    static @Unmodifiable SortedSet<String> collect(ClassLoader loader) throws IOException, ClassNotFoundException {
        return collect(loader, loadLines(loader, TYPE_RESOURCE));
    }

    /// Collects sorted API signatures for the supplied public type names.
    static @Unmodifiable SortedSet<String> collect(
            ClassLoader loader,
            @Unmodifiable SortedSet<String> typeNames
    ) throws ClassNotFoundException {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(typeNames, "typeNames");
        SortedSet<String> signatures = new TreeSet<>();
        for (String typeName : typeNames) {
            Class<?> type = Class.forName(typeName, false, loader);
            appendTypeSignatures(type, signatures);
        }
        return Collections.unmodifiableSortedSet(signatures);
    }

    /// Loads a strictly sorted non-empty text baseline resource.
    static @Unmodifiable SortedSet<String> loadLines(ClassLoader loader, String resourceName) throws IOException {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(resourceName, "resourceName");
        InputStream input = Objects.requireNonNull(
                loader.getResourceAsStream(resourceName),
                "Missing public API baseline: " + resourceName
        );
        SortedSet<String> lines = new TreeSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            @Nullable String previousLine = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (!line.equals(line.strip())) {
                    throw new IOException("Public API baseline contains surrounding whitespace: " + resourceName);
                }
                if (previousLine != null && previousLine.compareTo(line) >= 0) {
                    throw new IOException("Public API baseline is not strictly sorted at: " + line);
                }
                lines.add(line);
                previousLine = line;
            }
        }
        if (lines.isEmpty()) {
            throw new IOException("Public API baseline is empty: " + resourceName);
        }
        return Collections.unmodifiableSortedSet(lines);
    }

    /// Writes a sorted API signature baseline using stable LF line endings.
    static void write(Path path, @Unmodifiable SortedSet<String> signatures) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(signatures, "signatures");
        Path parent = Objects.requireNonNull(path.toAbsolutePath().getParent(), "baseline parent");
        Files.createDirectories(parent);
        Files.writeString(
                path,
                String.join("\n", signatures) + '\n',
                StandardCharsets.UTF_8
        );
    }

    /// Appends one type declaration and all declared public or protected members.
    private static void appendTypeSignatures(Class<?> type, SortedSet<String> signatures) {
        signatures.add(typeSignature(type));
        for (Field field : type.getDeclaredFields()) {
            if (isApiMember(field.getModifiers())) {
                signatures.add(fieldSignature(type, field));
            }
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (isApiMember(constructor.getModifiers())) {
                signatures.add(constructorSignature(type, constructor));
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (isApiMember(method.getModifiers())) {
                signatures.add(methodSignature(type, method));
            }
        }
    }

    /// Returns the deterministic declaration signature for one public type.
    private static String typeSignature(Class<?> type) {
        @Nullable Type superclass = type.getGenericSuperclass();
        Class<?> @Nullable [] permittedSubclasses = type.getPermittedSubclasses();
        return String.join(
                "\t",
                "TYPE",
                type.getName(),
                "kind=" + typeKind(type),
                "modifiers=" + modifiers(type.getModifiers(), Modifier.classModifiers()),
                "typeParameters=" + typeParameters(type.getTypeParameters()),
                "superclass=" + (superclass != null ? superclass.getTypeName() : "-"),
                "interfaces=" + sortedTypeNames(type.getGenericInterfaces()),
                "permits=" + sortedClassNames(permittedSubclasses)
        );
    }

    /// Returns the deterministic signature for one public or protected field.
    private static String fieldSignature(Class<?> owner, Field field) {
        List<String> flags = new ArrayList<>(2);
        if (field.isEnumConstant()) {
            flags.add("enumConstant");
        }
        if (field.isSynthetic()) {
            flags.add("synthetic");
        }
        return String.join(
                "\t",
                "FIELD",
                owner.getName(),
                field.getName(),
                "descriptor=" + descriptor(field.getType()),
                "generic=" + field.getGenericType().getTypeName(),
                "modifiers=" + modifiers(field.getModifiers(), Modifier.fieldModifiers()),
                "flags=" + flags(flags),
                "constant=" + constantValue(field)
        );
    }

    /// Returns the deterministic signature for one public or protected constructor.
    private static String constructorSignature(Class<?> owner, Constructor<?> constructor) {
        List<String> flags = new ArrayList<>(2);
        if (constructor.isSynthetic()) {
            flags.add("synthetic");
        }
        if (constructor.isVarArgs()) {
            flags.add("varargs");
        }
        return String.join(
                "\t",
                "CONSTRUCTOR",
                owner.getName(),
                "descriptor=" + executableDescriptor(constructor.getParameterTypes(), void.class),
                "genericParameters=" + typeNames(constructor.getGenericParameterTypes()),
                "typeParameters=" + typeParameters(constructor.getTypeParameters()),
                "throws=" + sortedTypeNames(constructor.getGenericExceptionTypes()),
                "modifiers=" + modifiers(constructor.getModifiers(), Modifier.constructorModifiers()),
                "flags=" + flags(flags)
        );
    }

    /// Returns the deterministic signature for one public or protected method.
    private static String methodSignature(Class<?> owner, Method method) {
        List<String> flags = new ArrayList<>(4);
        if (method.isBridge()) {
            flags.add("bridge");
        }
        if (method.isDefault()) {
            flags.add("default");
        }
        if (method.isSynthetic()) {
            flags.add("synthetic");
        }
        if (method.isVarArgs()) {
            flags.add("varargs");
        }
        @Nullable Object defaultValue = method.getDefaultValue();
        return String.join(
                "\t",
                "METHOD",
                owner.getName(),
                method.getName(),
                "descriptor=" + executableDescriptor(method.getParameterTypes(), method.getReturnType()),
                "genericParameters=" + typeNames(method.getGenericParameterTypes()),
                "genericReturn=" + method.getGenericReturnType().getTypeName(),
                "typeParameters=" + typeParameters(method.getTypeParameters()),
                "throws=" + sortedTypeNames(method.getGenericExceptionTypes()),
                "modifiers=" + modifiers(method.getModifiers(), Modifier.methodModifiers()),
                "flags=" + flags(flags),
                "default=" + (defaultValue != null ? annotationValue(defaultValue) : "-")
        );
    }

    /// Returns whether a declared member contributes to public or subclass-facing API.
    private static boolean isApiMember(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    /// Returns a stable kind label for one API type.
    private static String typeKind(Class<?> type) {
        if (type.isAnnotation()) {
            return "annotation";
        }
        if (type.isEnum()) {
            return "enum";
        }
        if (type.isRecord()) {
            return "record";
        }
        if (type.isInterface()) {
            return "interface";
        }
        return type.isSealed() ? "sealed-class" : "class";
    }

    /// Returns stable Java modifier text without context-dependent synthetic flag aliases.
    private static String modifiers(int modifiers, int applicableModifiers) {
        String value = Modifier.toString(modifiers & applicableModifiers);
        return value.isEmpty() ? "-" : value.replace(' ', '+');
    }

    /// Returns ordered generic type variable declarations.
    private static String typeParameters(TypeVariable<?>[] variables) {
        if (variables.length == 0) {
            return "-";
        }
        List<String> declarations = new ArrayList<>(variables.length);
        for (TypeVariable<?> variable : variables) {
            declarations.add(variable.getName() + ':' + typeNames(variable.getBounds()));
        }
        return String.join(",", declarations);
    }

    /// Returns generic type names in declaration order.
    private static String typeNames(Type[] types) {
        if (types.length == 0) {
            return "-";
        }
        List<String> names = new ArrayList<>(types.length);
        for (Type type : types) {
            names.add(type.getTypeName());
        }
        return String.join(",", names);
    }

    /// Returns generic type names in sorted order.
    private static String sortedTypeNames(Type[] types) {
        if (types.length == 0) {
            return "-";
        }
        List<String> names = new ArrayList<>(types.length);
        for (Type type : types) {
            names.add(type.getTypeName());
        }
        names.sort(Comparator.naturalOrder());
        return String.join(",", names);
    }

    /// Returns permitted subclass names in sorted order.
    private static String sortedClassNames(Class<?> @Nullable [] classes) {
        if (classes == null || classes.length == 0) {
            return "-";
        }
        List<String> names = new ArrayList<>(classes.length);
        for (Class<?> type : classes) {
            names.add(type.getName());
        }
        names.sort(Comparator.naturalOrder());
        return String.join(",", names);
    }

    /// Returns sorted marker flags or a sentinel when no flags apply.
    private static String flags(List<String> flags) {
        if (flags.isEmpty()) {
            return "-";
        }
        flags.sort(Comparator.naturalOrder());
        return String.join(",", flags);
    }

    /// Returns one JVM field descriptor.
    private static String descriptor(Class<?> type) {
        if (type.isArray()) {
            return type.getName().replace('.', '/');
        }
        if (!type.isPrimitive()) {
            return 'L' + type.getName().replace('.', '/') + ';';
        }
        if (type == void.class) {
            return "V";
        }
        if (type == boolean.class) {
            return "Z";
        }
        if (type == byte.class) {
            return "B";
        }
        if (type == char.class) {
            return "C";
        }
        if (type == short.class) {
            return "S";
        }
        if (type == int.class) {
            return "I";
        }
        if (type == long.class) {
            return "J";
        }
        if (type == float.class) {
            return "F";
        }
        if (type == double.class) {
            return "D";
        }
        throw new AssertionError("Unknown primitive type: " + type);
    }

    /// Returns one JVM method or constructor descriptor.
    private static String executableDescriptor(Class<?>[] parameterTypes, Class<?> returnType) {
        StringBuilder builder = new StringBuilder("(");
        for (Class<?> parameterType : parameterTypes) {
            builder.append(descriptor(parameterType));
        }
        return builder.append(')').append(descriptor(returnType)).toString();
    }

    /// Returns an inlinable primitive or string constant value, or a sentinel for other fields.
    private static String constantValue(Field field) {
        int modifiers = field.getModifiers();
        Class<?> type = field.getType();
        if (!Modifier.isStatic(modifiers)
                || !Modifier.isFinal(modifiers)
                || (!type.isPrimitive() && type != String.class)) {
            return "-";
        }
        try {
            if (!field.canAccess(null) && !field.trySetAccessible()) {
                throw new IllegalAccessException("Cannot access API constant " + field);
            }
            return annotationValue(Objects.requireNonNull(field.get(null), "API constant value"));
        } catch (IllegalAccessException exception) {
            throw new AssertionError("Cannot read API constant " + field, exception);
        }
    }

    /// Returns a stable annotation default value representation.
    private static String annotationValue(Object value) {
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int length = Array.getLength(value);
            List<String> elements = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                elements.add(annotationValue(Objects.requireNonNull(Array.get(value, index), "annotation array value")));
            }
            return '[' + String.join(",", elements) + ']';
        }
        if (value instanceof Class<?> classValue) {
            return "class:" + classValue.getName();
        }
        if (value instanceof Enum<?> enumValue) {
            return "enum:" + enumValue.getDeclaringClass().getName() + ':' + enumValue.name();
        }
        return escape(String.valueOf(value));
    }

    /// Escapes control characters used by the line-oriented baseline format.
    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
