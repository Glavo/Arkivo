// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoEditStorageFactory;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies typed archive option descriptors, raw environments, and public-option conversion.
@NotNullByDefault
final class ArchiveOptionsTest {
    /// Verifies option identity components and namespace validation.
    @Test
    void validatesOptionNamesAndRuntimeTypes() {
        ArchiveOption<String> option = ArchiveOption.of("format.zip", "commentCharset", String.class);

        assertEquals("format.zip", option.namespace());
        assertEquals("commentCharset", option.name());
        assertEquals("format.zip.commentCharset", option.key());
        assertSame(String.class, option.type());
        assertEquals(option.key(), option.toString());
        assertThrows(
                IllegalArgumentException.class,
                () -> ArchiveOptions.fromEnvironment(Map.of(option.key(), 1)).get(option)
        );

        for (String namespace : List.of("", " ", ".zip", "zip.", "format..zip", "format zip")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ArchiveOption.of(namespace, "name", String.class),
                    namespace
            );
        }
        for (String name : List.of("", " ", "entry.name", "entry name", "entry\tname")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ArchiveOption.of("format", name, String.class),
                    name
            );
        }
    }

    /// Verifies converters normalize values and must return a non-null value of the declared type.
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void enforcesConverterResultContract() {
        ArchiveOption<String> normalized = ArchiveOption.of(
                "test",
                "normalized",
                String.class,
                value -> ((String) value).trim()
        );
        ArchiveOptions options = ArchiveOptions.EMPTY.with(normalized, " value ");

        assertEquals("value", options.get(normalized));
        assertSame(options, options.with(normalized, "value"));

        ArchiveOption<String> nullResult = ArchiveOption.of(
                "test",
                "nullResult",
                String.class,
                value -> null
        );
        assertThrows(NullPointerException.class, () -> ArchiveOptions.EMPTY.with(nullResult, "value"));

        Function wrongConverter = (Function<Object, Integer>) value -> 1;
        ArchiveOption<String> wrongResult = ArchiveOption.of(
                "test",
                "wrongResult",
                String.class,
                wrongConverter
        );
        assertThrows(IllegalStateException.class, () -> ArchiveOptions.EMPTY.with(wrongResult, "value"));
    }

    /// Verifies raw environments are copied, null values are absent, and value semantics use stored entries.
    @Test
    void copiesRawEnvironmentAndExposesValueSemantics() {
        ArchiveOption<String> option = ArchiveOption.of("test", "value", String.class);
        LinkedHashMap<String, Object> environment = new LinkedHashMap<>();
        environment.put(option.key(), "configured");
        environment.put("test.ignored", null);
        ArchiveOptions options = ArchiveOptions.fromEnvironment(environment);
        environment.clear();

        assertFalse(options.isEmpty());
        assertTrue(options.contains(option));
        assertEquals("configured", options.get(option));
        assertEquals("configured", options.getOrDefault(option, "fallback"));
        assertNull(options.get(ArchiveOption.of("test", "missing", String.class)));
        assertEquals(
                "fallback",
                options.getOrDefault(ArchiveOption.of("test", "missing", String.class), "fallback")
        );
        assertEquals(options, ArchiveOptions.fromEnvironment(Map.of(option.key(), "configured")));
        assertEquals(options.hashCode(), ArchiveOptions.fromEnvironment(Map.of(option.key(), "configured")).hashCode());
        assertNotEquals(options, ArchiveOptions.EMPTY);
        assertTrue(options.toString().contains(option.key()));

        assertSame(ArchiveOptions.EMPTY, ArchiveOptions.fromEnvironment(Map.of()));
        LinkedHashMap<String, Object> onlyNull = new LinkedHashMap<>();
        onlyNull.put("test.null", null);
        assertSame(ArchiveOptions.EMPTY, ArchiveOptions.fromEnvironment(onlyNull));
    }

    /// Verifies invalid raw keys and null strongly typed values are rejected.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void rejectsInvalidEnvironmentEntries() {
        LinkedHashMap<String, Object> blank = new LinkedHashMap<>();
        blank.put(" ", "value");
        assertThrows(IllegalArgumentException.class, () -> ArchiveOptions.fromEnvironment(blank));

        LinkedHashMap<String, Object> nullKey = new LinkedHashMap<>();
        nullKey.put(null, "value");
        assertThrows(NullPointerException.class, () -> ArchiveOptions.fromEnvironment(nullKey));

        ArchiveOption<String> option = ArchiveOption.of("test", "value", String.class);
        assertThrows(NullPointerException.class, () -> ArchiveOptions.EMPTY.with(option, null));
        assertThrows(NullPointerException.class, () -> ArchiveOptions.EMPTY.getOrDefault(option, null));
    }

    /// Verifies every supported raw representation of NIO open options is normalized into an immutable set.
    @Test
    void normalizesOpenOptionShapes() {
        String key = ArchiveEnvironmentOptions.OPEN_OPTIONS.key();
        Set<OpenOption> single = ArchiveOptions.fromEnvironment(
                Map.of(key, StandardOpenOption.READ)
        ).get(ArchiveEnvironmentOptions.OPEN_OPTIONS);
        assertEquals(Set.of(StandardOpenOption.READ), single);

        OpenOption[] array = {
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ
        };
        Set<OpenOption> fromArray = ArchiveOptions.fromEnvironment(
                Map.of(key, array)
        ).get(ArchiveEnvironmentOptions.OPEN_OPTIONS);
        array[0] = StandardOpenOption.APPEND;
        assertEquals(Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE), fromArray);

        ArrayList<OpenOption> collection = new ArrayList<>(List.of(
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        ));
        Set<OpenOption> fromCollection = ArchiveOptions.fromEnvironment(
                Map.of(key, collection)
        ).get(ArchiveEnvironmentOptions.OPEN_OPTIONS);
        collection.clear();
        assertEquals(Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING), fromCollection);
        assertThrows(UnsupportedOperationException.class, () -> fromCollection.add(StandardOpenOption.WRITE));

        assertThrows(
                IllegalArgumentException.class,
                () -> ArchiveOptions.fromEnvironment(Map.of(key, "READ")).get(ArchiveEnvironmentOptions.OPEN_OPTIONS)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ArchiveOptions.fromEnvironment(Map.of(key, List.of(StandardOpenOption.READ, "WRITE")))
                        .get(ArchiveEnvironmentOptions.OPEN_OPTIONS)
        );
    }

    /// Verifies format-specific metadata detector options normalize detector, charset, and charset-name values.
    @Test
    void normalizesMetadataCharsetDetectorShapes() throws IOException {
        ArchiveOption<ArchiveMetadataCharsetDetector> option =
                ArchiveEnvironmentOptions.metadataCharsetDetectorOption("format.test", "metadataCharsetDetector");
        ArchiveMetadataCharsetDetector direct = ArchiveMetadataCharsetDetector.fixed(StandardCharsets.US_ASCII);

        assertSame(
                direct,
                ArchiveOptions.fromEnvironment(Map.of(option.key(), direct)).get(option)
        );
        assertEquals(
                StandardCharsets.UTF_16LE,
                ArchiveOptions.fromEnvironment(Map.of(option.key(), StandardCharsets.UTF_16LE))
                        .get(option)
                        .detect(new byte[0])
        );
        assertEquals(
                StandardCharsets.ISO_8859_1,
                ArchiveOptions.fromEnvironment(Map.of(option.key(), "ISO-8859-1"))
                        .get(option)
                        .detect(new byte[0])
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ArchiveOptions.fromEnvironment(Map.of(option.key(), 1)).get(option)
        );
    }

    /// Verifies thread-safety strings and public operation options map to the internal descriptors.
    @Test
    void convertsEnvironmentAndPublicOperationOptions() {
        String threadSafetyKey = ArchiveEnvironmentOptions.THREAD_SAFETY.key();
        assertEquals(
                ArkivoFileSystemThreadSafety.STRICT,
                ArchiveOptions.fromEnvironment(Map.of(threadSafetyKey, " Strict "))
                        .get(ArchiveEnvironmentOptions.THREAD_SAFETY)
        );
        assertEquals(
                ArkivoFileSystemThreadSafety.NONE,
                ArchiveOptions.fromEnvironment(Map.of(threadSafetyKey, ArkivoFileSystemThreadSafety.NONE))
                        .get(ArchiveEnvironmentOptions.THREAD_SAFETY)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ArchiveOptions.fromEnvironment(Map.of(threadSafetyKey, 1))
                        .get(ArchiveEnvironmentOptions.THREAD_SAFETY)
        );

        ArkivoEditStorageFactory storageFactory = ArkivoEditStorageFactory.memory();
        ArchiveReadLimits limits = ArchiveReadLimits.builder().maximumEntryCount(7L).build();
        ArkivoCommitTarget commitTarget = ArkivoCommitTarget.writeTo(Path.of("target.arc"));

        ArchiveOptions read = ArchiveOptions.fromReadOptions(ArchiveReadOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT)
                .withEditStorageFactory(storageFactory)
                .withLimits(limits));
        assertSame(ArkivoFileSystemThreadSafety.STRICT, read.get(ArchiveEnvironmentOptions.THREAD_SAFETY));
        assertSame(storageFactory, read.get(ArchiveEnvironmentOptions.EDIT_STORAGE_FACTORY));
        assertSame(limits, read.get(ArchiveEnvironmentOptions.READ_LIMITS));

        ArchiveOptions create = ArchiveOptions.fromCreateOptions(ArchiveCreateOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.NONE)
                .withEditStorageFactory(storageFactory));
        assertSame(ArkivoFileSystemThreadSafety.NONE, create.get(ArchiveEnvironmentOptions.THREAD_SAFETY));
        assertSame(storageFactory, create.get(ArchiveEnvironmentOptions.EDIT_STORAGE_FACTORY));
        assertFalse(create.contains(ArchiveEnvironmentOptions.READ_LIMITS));

        ArchiveOptions update = ArchiveOptions.fromUpdateOptions(ArchiveUpdateOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT)
                .withEditStorageFactory(storageFactory)
                .withCommitTarget(commitTarget)
                .withLimits(limits));
        assertSame(ArkivoFileSystemThreadSafety.STRICT, update.get(ArchiveEnvironmentOptions.THREAD_SAFETY));
        assertSame(storageFactory, update.get(ArchiveEnvironmentOptions.EDIT_STORAGE_FACTORY));
        assertSame(commitTarget, update.get(ArchiveEnvironmentOptions.COMMIT_TARGET));
        assertSame(limits, update.get(ArchiveEnvironmentOptions.READ_LIMITS));
    }
}
