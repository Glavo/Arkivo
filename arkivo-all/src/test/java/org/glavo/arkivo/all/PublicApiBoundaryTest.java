// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies exported Arkivo types expose only public types from transitively readable modules.
@NotNullByDefault
public final class PublicApiBoundaryTest {
    /// The package prefix reserved for Arkivo implementation and API types.
    private static final String ARKIVO_PACKAGE_PREFIX = "org.glavo.arkivo.";

    /// Maps every unqualified JPMS export to its owning module.
    private static final @Unmodifiable Map<String, String> PUBLIC_PACKAGE_MODULES = Map.ofEntries(
            Map.entry("org.glavo.arkivo.archive", "org.glavo.arkivo.archive"),
            Map.entry("org.glavo.arkivo.archive.spi", "org.glavo.arkivo.archive"),
            Map.entry("org.glavo.arkivo.archive.ar", "org.glavo.arkivo.archive.ar"),
            Map.entry("org.glavo.arkivo.archive.rar", "org.glavo.arkivo.archive.rar"),
            Map.entry("org.glavo.arkivo.archive.sevenzip", "org.glavo.arkivo.archive.sevenzip"),
            Map.entry("org.glavo.arkivo.archive.tar", "org.glavo.arkivo.archive.tar"),
            Map.entry("org.glavo.arkivo.archive.zip", "org.glavo.arkivo.archive.zip"),
            Map.entry("org.glavo.arkivo.codec", "org.glavo.arkivo.codec"),
            Map.entry("org.glavo.arkivo.codec.spi", "org.glavo.arkivo.codec"),
            Map.entry("org.glavo.arkivo.codec.transform", "org.glavo.arkivo.codec"),
            Map.entry("org.glavo.arkivo.codec.bcj", "org.glavo.arkivo.codec.bcj"),
            Map.entry("org.glavo.arkivo.codec.bzip2", "org.glavo.arkivo.codec.bzip2"),
            Map.entry("org.glavo.arkivo.codec.deflate", "org.glavo.arkivo.codec.deflate"),
            Map.entry("org.glavo.arkivo.codec.deflate64", "org.glavo.arkivo.codec.deflate64"),
            Map.entry("org.glavo.arkivo.codec.delta", "org.glavo.arkivo.codec.delta"),
            Map.entry("org.glavo.arkivo.codec.gzip", "org.glavo.arkivo.codec.gzip"),
            Map.entry("org.glavo.arkivo.codec.lzma", "org.glavo.arkivo.codec.lzma"),
            Map.entry("org.glavo.arkivo.codec.ppmd", "org.glavo.arkivo.codec.ppmd"),
            Map.entry("org.glavo.arkivo.codec.xz", "org.glavo.arkivo.codec.xz"),
            Map.entry("org.glavo.arkivo.codec.zlib", "org.glavo.arkivo.codec.zlib"),
            Map.entry("org.glavo.arkivo.codec.zstd", "org.glavo.arkivo.codec.zstd")
    );

    /// Lists the public module dependencies exposed transitively by each module with exported types.
    private static final @Unmodifiable Map<String, @Unmodifiable Set<String>> TRANSITIVE_REQUIREMENTS = Map.ofEntries(
            Map.entry("org.glavo.arkivo.archive.ar", Set.of("org.glavo.arkivo.archive")),
            Map.entry("org.glavo.arkivo.archive.rar", Set.of("org.glavo.arkivo.archive")),
            Map.entry("org.glavo.arkivo.archive.sevenzip", Set.of("org.glavo.arkivo.archive")),
            Map.entry("org.glavo.arkivo.archive.tar", Set.of(
                    "org.glavo.arkivo.archive",
                    "org.glavo.arkivo.codec"
            )),
            Map.entry("org.glavo.arkivo.archive.zip", Set.of("org.glavo.arkivo.archive")),
            Map.entry("org.glavo.arkivo.codec.bcj", Set.of("org.glavo.arkivo.codec")),
            Map.entry("org.glavo.arkivo.codec.bzip2", Set.of("org.glavo.arkivo.codec")),
            Map.entry("org.glavo.arkivo.codec.deflate", Set.of("org.glavo.arkivo.codec")),
            Map.entry("org.glavo.arkivo.codec.deflate64", Set.of("org.glavo.arkivo.codec")),
            Map.entry("org.glavo.arkivo.codec.delta", Set.of("org.glavo.arkivo.codec")),
            Map.entry("org.glavo.arkivo.codec.gzip", Set.of("org.glavo.arkivo.codec")),
            Map.entry("org.glavo.arkivo.codec.lzma", Set.of("org.glavo.arkivo.codec")),
            Map.entry("org.glavo.arkivo.codec.ppmd", Set.of("org.glavo.arkivo.codec")),
            Map.entry("org.glavo.arkivo.codec.xz", Set.of("org.glavo.arkivo.codec")),
            Map.entry("org.glavo.arkivo.codec.zlib", Set.of("org.glavo.arkivo.codec")),
            Map.entry("org.glavo.arkivo.codec.zstd", Set.of("org.glavo.arkivo.codec"))
    );

    /// Verifies every public and protected signature reachable from an exported package.
    @Test
    void exportedSignaturesUsePublicTransitiveTypes() throws Exception {
        ClassLoader loader = Objects.requireNonNull(
                Thread.currentThread().getContextClassLoader(),
                "context class loader"
        );
        List<String> failures = new ArrayList<>();
        Set<String> publicTypeNames = new TreeSet<>();

        for (Map.Entry<String, String> entry : PUBLIC_PACKAGE_MODULES.entrySet()) {
            String packageName = entry.getKey();
            Set<Class<?>> apiClasses = findPublicClasses(packageName, loader);
            if (apiClasses.isEmpty()) {
                failures.add("Exported package contains no public classes: " + packageName);
                continue;
            }
            for (Class<?> apiClass : apiClasses) {
                publicTypeNames.add(apiClass.getName());
                validateClass(apiClass, entry.getValue(), failures);
            }
        }

        assertTrue(failures.isEmpty(), () -> "Public API boundary violations:\n" + String.join("\n", failures));
        assertEquals(
                loadExpectedPublicTypes(loader),
                publicTypeNames,
                "Exported public types differ from the reviewed 1.0 API baseline"
        );
    }

    /// Loads the reviewed public type names that define the current 1.0 API baseline.
    private static @Unmodifiable Set<String> loadExpectedPublicTypes(ClassLoader loader) throws IOException {
        String resourceName = "org/glavo/arkivo/all/public-api-types.txt";
        InputStream input = Objects.requireNonNull(
                loader.getResourceAsStream(resourceName),
                "Missing public API baseline: " + resourceName
        );
        Set<String> typeNames = new TreeSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            @Nullable String previousTypeName = null;
            String line;
            while ((line = reader.readLine()) != null) {
                String typeName = line.strip();
                if (typeName.isEmpty()) {
                    continue;
                }
                if (previousTypeName != null && previousTypeName.compareTo(typeName) >= 0) {
                    throw new IOException("Public API baseline is not strictly sorted at: " + typeName);
                }
                typeNames.add(typeName);
                previousTypeName = typeName;
            }
        }
        return Set.copyOf(typeNames);
    }

    /// Finds accessible public classes directly contained in one exported package.
    private static @Unmodifiable Set<Class<?>> findPublicClasses(
            String packageName,
            ClassLoader loader
    ) throws IOException, URISyntaxException, ClassNotFoundException {
        String resourcePath = packageName.replace('.', '/');
        Set<String> classNames = new TreeSet<>();
        Enumeration<URL> resources = loader.getResources(resourcePath);
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            switch (resource.getProtocol()) {
                case "file" -> collectDirectoryClasses(Path.of(resource.toURI()), packageName, classNames);
                case "jar" -> collectJarClasses(resource, resourcePath, classNames);
                default -> throw new IOException("Unsupported public API resource URL: " + resource);
            }
        }

        Set<Class<?>> classes = new HashSet<>();
        for (String className : classNames) {
            Class<?> candidate = Class.forName(className, false, loader);
            if (isPubliclyAccessible(candidate)) {
                classes.add(candidate);
            }
        }
        return Set.copyOf(classes);
    }

    /// Collects class names from one exploded package directory without descending into subpackages.
    private static void collectDirectoryClasses(
            Path directory,
            String packageName,
            Set<String> classNames
    ) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            children.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(PublicApiBoundaryTest::isClassFile)
                    .map(fileName -> packageName + '.' + fileName.substring(0, fileName.length() - 6))
                    .forEach(classNames::add);
        }
    }

    /// Collects class names from one packaged exported directory without descending into subpackages.
    private static void collectJarClasses(
            URL resource,
            String resourcePath,
            Set<String> classNames
    ) throws IOException {
        JarURLConnection connection = (JarURLConnection) resource.openConnection();
        connection.setUseCaches(false);
        String entryPrefix = resourcePath + '/';
        try (JarFile jar = connection.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String entryName = entries.nextElement().getName();
                if (!entryName.startsWith(entryPrefix)) {
                    continue;
                }
                String remainder = entryName.substring(entryPrefix.length());
                if (remainder.indexOf('/') < 0 && isClassFile(remainder)) {
                    classNames.add(entryName.substring(0, entryName.length() - 6).replace('/', '.'));
                }
            }
        }
    }

    /// Returns whether a package entry is an ordinary class file relevant to API inspection.
    private static boolean isClassFile(String fileName) {
        return fileName.endsWith(".class") && !fileName.equals("package-info.class");
    }

    /// Returns whether a class and all enclosing classes are public.
    private static boolean isPubliclyAccessible(Class<?> type) {
        for (@Nullable Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            if (!Modifier.isPublic(current.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    /// Validates all declared source and binary API signatures of one public class.
    private static void validateClass(Class<?> apiClass, String ownerModule, List<String> failures) {
        String classLocation = apiClass.getName();
        validateType(apiClass.getGenericSuperclass(), ownerModule, classLocation + " superclass", failures);
        for (Type type : apiClass.getGenericInterfaces()) {
            validateType(type, ownerModule, classLocation + " interface", failures);
        }
        validateTypeVariables(apiClass.getTypeParameters(), ownerModule, classLocation, failures);
        Class<?> @Nullable [] permittedClasses = apiClass.getPermittedSubclasses();
        if (permittedClasses != null) {
            for (Class<?> permittedClass : permittedClasses) {
                validatePermittedClass(permittedClass, ownerModule, classLocation + " permits", failures);
            }
        }

        @Nullable RecordComponent[] recordComponents = apiClass.getRecordComponents();
        if (recordComponents != null) {
            for (RecordComponent component : recordComponents) {
                validateType(
                        component.getGenericType(),
                        ownerModule,
                        classLocation + " record component " + component.getName(),
                        failures
                );
            }
        }

        for (Field field : apiClass.getDeclaredFields()) {
            if (isApiMember(field.getModifiers())) {
                validateType(
                        field.getGenericType(),
                        ownerModule,
                        classLocation + " field " + field.getName(),
                        failures
                );
            }
        }
        for (Constructor<?> constructor : apiClass.getDeclaredConstructors()) {
            if (isApiMember(constructor.getModifiers())) {
                String location = classLocation + " constructor";
                validateExecutableTypes(
                        constructor.getGenericParameterTypes(),
                        constructor.getGenericExceptionTypes(),
                        constructor.getTypeParameters(),
                        ownerModule,
                        location,
                        failures
                );
            }
        }
        for (Method method : apiClass.getDeclaredMethods()) {
            if (isApiMember(method.getModifiers())) {
                String location = classLocation + " method " + method.getName();
                validateType(method.getGenericReturnType(), ownerModule, location + " return", failures);
                validateExecutableTypes(
                        method.getGenericParameterTypes(),
                        method.getGenericExceptionTypes(),
                        method.getTypeParameters(),
                        ownerModule,
                        location,
                        failures
                );
            }
        }
    }

    /// Returns whether one declared member contributes to source or binary public API.
    private static boolean isApiMember(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    /// Validates parameter, exception, and type-variable signatures shared by methods and constructors.
    private static void validateExecutableTypes(
            Type @Unmodifiable [] parameterTypes,
            Type @Unmodifiable [] exceptionTypes,
            TypeVariable<?> @Unmodifiable [] typeVariables,
            String ownerModule,
            String location,
            List<String> failures
    ) {
        for (Type parameterType : parameterTypes) {
            validateType(parameterType, ownerModule, location + " parameter", failures);
        }
        for (Type exceptionType : exceptionTypes) {
            validateType(exceptionType, ownerModule, location + " throws", failures);
        }
        validateTypeVariables(typeVariables, ownerModule, location, failures);
    }

    /// Validates every upper bound declared by a generic type variable.
    private static void validateTypeVariables(
            TypeVariable<?> @Unmodifiable [] variables,
            String ownerModule,
            String location,
            List<String> failures
    ) {
        for (TypeVariable<?> variable : variables) {
            for (Type bound : variable.getBounds()) {
                validateType(bound, ownerModule, location + " type variable " + variable.getName(), failures);
            }
        }
    }

    /// Validates every class reachable through one reflective generic type.
    private static void validateType(
            @Nullable Type type,
            String ownerModule,
            String location,
            List<String> failures
    ) {
        if (type == null) {
            return;
        }
        validateType(type, ownerModule, location, failures, new HashSet<>());
    }

    /// Recursively validates a generic type while breaking recursive type-variable cycles.
    private static void validateType(
            Type type,
            String ownerModule,
            String location,
            List<String> failures,
            Set<Type> visited
    ) {
        if (!visited.add(type)) {
            return;
        }
        if (type instanceof Class<?> typeClass) {
            if (typeClass.isArray()) {
                validateType(typeClass.getComponentType(), ownerModule, location, failures, visited);
            } else {
                validateReferencedClass(typeClass, ownerModule, location, failures);
            }
            return;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            validateType(parameterizedType.getRawType(), ownerModule, location, failures, visited);
            @Nullable Type ownerType = parameterizedType.getOwnerType();
            if (ownerType != null) {
                validateType(ownerType, ownerModule, location, failures, visited);
            }
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                validateType(argument, ownerModule, location, failures, visited);
            }
            return;
        }
        if (type instanceof GenericArrayType arrayType) {
            validateType(arrayType.getGenericComponentType(), ownerModule, location, failures, visited);
            return;
        }
        if (type instanceof TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) {
                validateType(bound, ownerModule, location, failures, visited);
            }
            return;
        }
        if (type instanceof WildcardType wildcardType) {
            for (Type bound : wildcardType.getUpperBounds()) {
                validateType(bound, ownerModule, location, failures, visited);
            }
            for (Type bound : wildcardType.getLowerBounds()) {
                validateType(bound, ownerModule, location, failures, visited);
            }
            return;
        }
        failures.add(location + " uses unsupported reflective type " + type.getTypeName());
    }

    /// Validates one concrete type referenced by a public signature.
    private static void validateReferencedClass(
            Class<?> referencedClass,
            String ownerModule,
            String location,
            List<String> failures
    ) {
        if (referencedClass.isPrimitive() || referencedClass == Void.TYPE) {
            return;
        }
        String packageName = referencedClass.getPackageName();
        if (packageName.startsWith("java.") || packageName.startsWith("javax.")) {
            return;
        }
        if (!packageName.startsWith(ARKIVO_PACKAGE_PREFIX)) {
            failures.add(location + " exposes external type " + referencedClass.getTypeName());
            return;
        }

        @Nullable String referencedModule = PUBLIC_PACKAGE_MODULES.get(packageName);
        if (referencedModule == null) {
            failures.add(location + " exposes non-exported type " + referencedClass.getTypeName());
            return;
        }
        if (ownerModule.equals(referencedModule)) {
            return;
        }
        if (!TRANSITIVE_REQUIREMENTS.getOrDefault(ownerModule, Collections.emptySet()).contains(referencedModule)) {
            failures.add(location + " exposes " + referencedClass.getTypeName()
                    + " from non-transitive module " + referencedModule);
        }
    }

    /// Validates a sealed permitted class, allowing an encapsulated implementation from the declaring module.
    private static void validatePermittedClass(
            Class<?> permittedClass,
            String ownerModule,
            String location,
            List<String> failures
    ) {
        @Nullable String permittedModule = moduleForPackage(permittedClass.getPackageName());
        if (ownerModule.equals(permittedModule)) {
            return;
        }
        validateReferencedClass(permittedClass, ownerModule, location, failures);
    }

    /// Resolves an exported package or its nested implementation package to the owning Arkivo module.
    private static @Nullable String moduleForPackage(String packageName) {
        @Nullable String matchedModule = null;
        int matchedLength = -1;
        for (Map.Entry<String, String> entry : PUBLIC_PACKAGE_MODULES.entrySet()) {
            String publicPackage = entry.getKey();
            if ((packageName.equals(publicPackage) || packageName.startsWith(publicPackage + '.'))
                    && publicPackage.length() > matchedLength) {
                matchedModule = entry.getValue();
                matchedLength = publicPackage.length();
            }
        }
        return matchedModule;
    }
}
