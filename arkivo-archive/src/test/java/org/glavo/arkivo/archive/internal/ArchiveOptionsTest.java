// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.OpenOption;
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

/// Verifies typed archive option descriptors and NIO environment conversion.
@NotNullByDefault
final class ArchiveOptionsTest {
    /// Verifies environment keys and runtime value validation.
    @Test
    void validatesOptionNamesAndRuntimeTypes() {
        ArchiveOption<String> option = ArchiveOption.of("format.zip.commentCharset", String.class);

        assertEquals("format.zip.commentCharset", option.key());
        assertEquals(option.key(), option.toString());
        ArchiveOption<String> sameKey = ArchiveOption.of(option.key(), String.class);
        assertEquals("UTF-8", ArchiveOptions.fromEnvironment(Map.of(option.key(), "UTF-8")).get(sameKey));
        assertThrows(
                IllegalArgumentException.class,
                () -> ArchiveOptions.fromEnvironment(Map.of(option.key(), 1)).get(option)
        );

        for (String key : List.of("", " ", ".zip", "zip.", "format..zip", "format zip", "entry\tname")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ArchiveOption.of(key, String.class),
                    key
            );
        }
    }

    /// Verifies converters normalize values and must return a non-null value of the declared type.
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void enforcesConverterResultContract() {
        ArchiveOption<String> normalized = ArchiveOption.of(
                "test.normalized",
                String.class,
                value -> ((String) value).trim()
        );
        ArchiveOptions options = ArchiveOptions.fromEnvironment(Map.of(normalized.key(), " value "));

        assertEquals("value", options.get(normalized));

        ArchiveOption<String> nullResult = ArchiveOption.of(
                "test.nullResult",
                String.class,
                value -> null
        );
        assertThrows(NullPointerException.class, () -> ArchiveOptions.fromEnvironment(Map.of(nullResult.key(), "value")).get(nullResult));

        Function wrongConverter = (Function<Object, Integer>) value -> 1;
        ArchiveOption<String> wrongResult = ArchiveOption.of(
                "test.wrongResult",
                String.class,
                wrongConverter
        );
        assertThrows(IllegalStateException.class, () -> ArchiveOptions.fromEnvironment(Map.of(wrongResult.key(), "value")).get(wrongResult));
    }

    /// Verifies raw environments are copied, null values are absent, and value semantics use stored entries.
    @Test
    void copiesRawEnvironmentAndExposesValueSemantics() {
        ArchiveOption<String> option = ArchiveOption.of("test.value", String.class);
        LinkedHashMap<String, Object> environment = new LinkedHashMap<>();
        environment.put(option.key(), "configured");
        environment.put("test.ignored", null);
        ArchiveOptions options = ArchiveOptions.fromEnvironment(environment);
        environment.clear();

        assertFalse(options.isEmpty());
        assertTrue(options.contains(option));
        assertEquals("configured", options.get(option));
        assertEquals("configured", options.getOrDefault(option, "fallback"));
        assertNull(options.get(ArchiveOption.of("test.missing", String.class)));
        assertEquals(
                "fallback",
                options.getOrDefault(ArchiveOption.of("test.missing", String.class), "fallback")
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

    /// Verifies invalid raw keys and null default values are rejected.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void rejectsInvalidEnvironmentEntries() {
        LinkedHashMap<String, Object> blank = new LinkedHashMap<>();
        blank.put(" ", "value");
        assertThrows(IllegalArgumentException.class, () -> ArchiveOptions.fromEnvironment(blank));

        LinkedHashMap<String, Object> nullKey = new LinkedHashMap<>();
        nullKey.put(null, "value");
        assertThrows(NullPointerException.class, () -> ArchiveOptions.fromEnvironment(nullKey));

        ArchiveOption<String> option = ArchiveOption.of("test.value", String.class);
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

    /// Verifies format-specific metadata decoder options normalize decoder, charset, and charset-name values.
    @Test
    void normalizesMetadataDecoderShapes() throws IOException {
        ArchiveOption<ArchiveMetadataDecoder> option =
                ArchiveEnvironmentOptions.metadataDecoderOption("format.test.metadataDecoder");
        ArchiveMetadataDecoder direct = ArchiveMetadataDecoder.forCharset(StandardCharsets.US_ASCII);

        assertSame(
                direct,
                ArchiveOptions.fromEnvironment(Map.of(option.key(), direct)).get(option)
        );
        assertEquals(
                "AB",
                ArchiveOptions.fromEnvironment(Map.of(option.key(), StandardCharsets.UTF_16LE))
                        .get(option)
                        .decode(new byte[]{65, 0, 66, 0})
        );
        assertEquals(
                "\u00e9",
                ArchiveOptions.fromEnvironment(Map.of(option.key(), "ISO-8859-1"))
                        .get(option)
                        .decode(new byte[]{(byte) 0xe9})
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ArchiveOptions.fromEnvironment(Map.of(option.key(), 1)).get(option)
        );
    }

    /// Verifies thread-safety strings resolve to the configured policy.
    @Test
    void convertsThreadSafetyEnvironmentValues() {
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
    }
}
