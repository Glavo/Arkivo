// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests typed immutable archive options and raw NIO environment conversion.
@NotNullByDefault
public final class ArchiveOptionsTest {
    /// Verifies that common archive option keys use the Arkivo namespace.
    @Test
    public void commonOptionKeysUseArkivoNamespace() {
        assertEquals("arkivo", ArkivoFileSystem.THREAD_SAFETY.namespace());
        assertEquals("threadSafety", ArkivoFileSystem.THREAD_SAFETY.name());
        assertEquals("arkivo.threadSafety", ArkivoFileSystem.THREAD_SAFETY.key());
        assertEquals("arkivo", ArkivoFileSystem.OPEN_OPTIONS.namespace());
        assertEquals("openOptions", ArkivoFileSystem.OPEN_OPTIONS.name());
        assertEquals("arkivo.openOptions", ArkivoFileSystem.OPEN_OPTIONS.key());
        assertEquals(Set.class, ArkivoFileSystem.OPEN_OPTIONS.type());
        assertEquals("arkivo.maxEntryCount", ArkivoFileSystem.MAX_ENTRY_COUNT.key());
        assertEquals("arkivo.maxEntrySize", ArkivoFileSystem.MAX_ENTRY_SIZE.key());
        assertEquals("arkivo.maxTotalEntrySize", ArkivoFileSystem.MAX_TOTAL_ENTRY_SIZE.key());
        assertEquals("arkivo.maxMetadataSize", ArkivoFileSystem.MAX_METADATA_SIZE.key());
    }

    /// Verifies that file system thread-safety values parse stable option strings.
    @Test
    public void parseThreadSafety() {
        assertEquals(ArkivoFileSystemThreadSafety.NONE, ArkivoFileSystemThreadSafety.parse("none"));
        assertEquals(ArkivoFileSystemThreadSafety.CONCURRENT_READ, ArkivoFileSystemThreadSafety.parse("concurrent_read"));
        assertEquals(ArkivoFileSystemThreadSafety.STRICT, ArkivoFileSystemThreadSafety.parse("strict"));
    }

    /// Verifies typed persistent updates and lookups.
    @Test
    public void typedValue() {
        ArchiveOption<Boolean> option = ArchiveOption.of("test", "enabled", Boolean.class);
        ArchiveOptions options = ArchiveOptions.of(option, true);

        assertEquals(Boolean.TRUE, options.get(option));
        assertTrue(options.contains(option));
        assertSame(options, options.with(option, true));
        assertSame(ArchiveOptions.EMPTY, options.without(option));
    }

    /// Verifies that strings are normalized through the option converter before storage.
    @Test
    public void stringValue() {
        ArchiveOption<Long> option = longOption();
        ArchiveOptions options = ArchiveOptions.EMPTY.withString(option, "4096");

        assertEquals(4096L, options.get(option));
        assertEquals(4096L, options.toEnvironment().get(option.key()));
    }

    /// Verifies that compatible raw integral values are converted on typed lookup.
    @Test
    public void integralValueConversion() {
        ArchiveOption<Long> option = longOption();

        assertEquals(1L, ArchiveOptions.fromEnvironment(Map.of("test.size", (byte) 1)).get(option));
        assertEquals(2L, ArchiveOptions.fromEnvironment(Map.of("test.size", (short) 2)).get(option));
        assertEquals(3L, ArchiveOptions.fromEnvironment(Map.of("test.size", 3)).get(option));
    }

    /// Verifies that file system open options are normalized to immutable sets.
    @Test
    public void openOptionsConversion() {
        ArchiveOptions options = ArchiveOptions.fromEnvironment(Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE}
        ));

        Set<OpenOption> converted = options.get(ArkivoFileSystem.OPEN_OPTIONS);
        assertEquals(Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE), converted);
        assertThrows(UnsupportedOperationException.class, () -> converted.add(StandardOpenOption.READ));
    }

    /// Verifies that invalid raw option values fail at typed lookup.
    @Test
    public void invalidRawValue() {
        ArchiveOption<Boolean> option = ArchiveOption.of("test", "enabled", Boolean.class);
        ArchiveOptions options = ArchiveOptions.fromEnvironment(Map.of(option.key(), 1));

        assertThrows(IllegalArgumentException.class, () -> options.get(option));
    }

    /// Verifies common archive read limits accept compatible non-negative integral values.
    @Test
    public void commonReadLimitConversion() {
        ArchiveOptions options = ArchiveOptions.fromEnvironment(Map.of(
                ArkivoFileSystem.MAX_ENTRY_COUNT.key(), (byte) 0,
                ArkivoFileSystem.MAX_ENTRY_SIZE.key(), 12,
                ArkivoFileSystem.MAX_TOTAL_ENTRY_SIZE.key(), "4096",
                ArkivoFileSystem.MAX_METADATA_SIZE.key(), (short) 8192
        ));

        assertEquals(0L, options.getOrDefault(ArkivoFileSystem.MAX_ENTRY_COUNT, -1L));
        assertEquals(12L, options.getOrDefault(ArkivoFileSystem.MAX_ENTRY_SIZE, -1L));
        assertEquals(4096L, options.getOrDefault(ArkivoFileSystem.MAX_TOTAL_ENTRY_SIZE, -1L));
        assertEquals(8192L, options.getOrDefault(ArkivoFileSystem.MAX_METADATA_SIZE, -1L));
    }

    /// Verifies common archive read limits reject negative and non-integral values.
    @Test
    public void rejectInvalidCommonReadLimits() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ArchiveOptions.fromEnvironment(
                        Map.of(ArkivoFileSystem.MAX_ENTRY_COUNT.key(), -1L)
                ).get(ArkivoFileSystem.MAX_ENTRY_COUNT)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ArchiveOptions.fromEnvironment(
                        Map.of(ArkivoFileSystem.MAX_ENTRY_SIZE.key(), 1.5)
                ).get(ArkivoFileSystem.MAX_ENTRY_SIZE)
        );
    }

    /// Verifies builders produce independent immutable snapshots.
    @Test
    public void builderSnapshots() {
        ArchiveOption<Boolean> first = ArchiveOption.of("test", "first", Boolean.class);
        ArchiveOption<Boolean> second = ArchiveOption.of("test", "second", Boolean.class);
        ArchiveOptions.Builder builder = ArchiveOptions.builder().set(first, true);
        ArchiveOptions snapshot = builder.build();

        builder.set(second, true);

        assertTrue(snapshot.contains(first));
        assertFalse(snapshot.contains(second));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.toEnvironment().clear());
    }

    /// Verifies that malformed raw environment keys are rejected.
    @Test
    public void rejectMalformedEnvironmentKey() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ArchiveOptions.fromEnvironment(Map.of(" ", true))
        );
    }

    /// Returns a Long option that parses compatible integral and string values.
    private static ArchiveOption<Long> longOption() {
        return ArchiveOption.of("test", "size", Long.class, value -> {
            if (value instanceof Long longValue) {
                return longValue;
            }
            if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
                return ((Number) value).longValue();
            }
            if (value instanceof String stringValue) {
                return Long.parseLong(stringValue);
            }
            throw new IllegalArgumentException("Expected Long, compatible integral number, or String for key: test.size");
        });
    }
}
