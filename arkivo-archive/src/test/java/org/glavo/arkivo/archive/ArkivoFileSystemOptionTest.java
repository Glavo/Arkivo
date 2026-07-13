// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests typed file system environment options.
@NotNullByDefault
public final class ArkivoFileSystemOptionTest {
    /// Verifies that common file system option keys use the Arkivo namespace.
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

    /// Verifies that typed values can be written and read.
    @Test
    public void typedValue() {
        ArkivoFileSystemOption<Boolean> option = ArkivoFileSystemOption.of("test", "enabled", Boolean.class);
        Map<String, Object> environment = new HashMap<>();

        option.put(environment, true);

        assertEquals(Boolean.TRUE, option.read(environment));
    }

    /// Verifies that string values can be written and parsed.
    @Test
    public void stringValue() {
        ArkivoFileSystemOption<Long> option = longOption();
        Map<String, Object> environment = new HashMap<>();

        option.putString(environment, "4096");

        assertEquals(4096L, option.read(environment));
    }

    /// Verifies that compatible integral values are converted to the option type.
    @Test
    public void integralValueConversion() {
        ArkivoFileSystemOption<Long> option = longOption();

        assertEquals(1L, option.read(Map.of("test.size", (byte) 1)));
        assertEquals(2L, option.read(Map.of("test.size", (short) 2)));
        assertEquals(3L, option.read(Map.of("test.size", 3)));
    }

    /// Verifies that file system open options are converted to immutable sets.
    @Test
    public void openOptionsConversion() {
        assertEquals(
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                ArkivoFileSystem.OPEN_OPTIONS.convert(Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE))
        );
        assertEquals(
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                ArkivoFileSystem.OPEN_OPTIONS.convert(new OpenOption[]{
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE
                })
        );
        assertEquals(
                Set.of(StandardOpenOption.READ),
                ArkivoFileSystem.OPEN_OPTIONS.convert(StandardOpenOption.READ)
        );

        Set<OpenOption> converted = ArkivoFileSystem.OPEN_OPTIONS.convert(Set.of(StandardOpenOption.READ));
        assertThrows(UnsupportedOperationException.class, () -> converted.add(StandardOpenOption.WRITE));
    }

    /// Verifies that invalid file system open option values are rejected.
    @Test
    public void rejectInvalidOpenOptionsConversion() {
        assertThrows(IllegalArgumentException.class, () -> ArkivoFileSystem.OPEN_OPTIONS.convert(Set.of("read")));
        assertThrows(IllegalArgumentException.class, () -> ArkivoFileSystem.OPEN_OPTIONS.convert("read"));
    }

    /// Verifies that floating-point values are not converted to integral option types.
    @Test
    public void rejectFloatingPointToIntegralConversion() {
        ArkivoFileSystemOption<Long> option = longOption();

        assertThrows(IllegalArgumentException.class, () -> option.read(Map.of("test.size", 1.5)));
    }

    /// Verifies common archive read limits accept compatible non-negative integral values.
    @Test
    public void commonReadLimitConversion() {
        assertEquals(0L, ArkivoFileSystem.MAX_ENTRY_COUNT.read(Map.of("arkivo.maxEntryCount", (byte) 0)));
        assertEquals(12L, ArkivoFileSystem.MAX_ENTRY_SIZE.read(Map.of("arkivo.maxEntrySize", 12)));
        assertEquals(
                4096L,
                ArkivoFileSystem.MAX_TOTAL_ENTRY_SIZE.read(Map.of("arkivo.maxTotalEntrySize", "4096"))
        );
        assertEquals(
                8192L,
                ArkivoFileSystem.MAX_METADATA_SIZE.read(Map.of("arkivo.maxMetadataSize", (short) 8192))
        );
    }

    /// Verifies common archive read limits reject negative and non-integral values.
    @Test
    public void rejectInvalidCommonReadLimits() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ArkivoFileSystem.MAX_ENTRY_COUNT.read(Map.of("arkivo.maxEntryCount", -1L))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ArkivoFileSystem.MAX_ENTRY_SIZE.read(Map.of("arkivo.maxEntrySize", 1.5))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ArkivoFileSystem.MAX_TOTAL_ENTRY_SIZE.read(Map.of("arkivo.maxTotalEntrySize", "large"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ArkivoFileSystem.MAX_METADATA_SIZE.read(Map.of("arkivo.maxMetadataSize", -1))
        );
    }

    /// Verifies that unsupported string values are rejected before being written.
    @Test
    public void unsupportedStringValue() {
        ArkivoFileSystemOption<Boolean> option = ArkivoFileSystemOption.of("test", "enabled", Boolean.class);
        Map<String, Object> environment = new HashMap<>();

        assertThrows(IllegalArgumentException.class, () -> option.putString(environment, "true"));
    }

    /// Verifies that invalid string values are rejected before being written.
    @Test
    public void invalidStringValue() {
        ArkivoFileSystemOption<Long> option = longOption();
        Map<String, Object> environment = new HashMap<>();

        assertThrows(NumberFormatException.class, () -> option.putString(environment, "large"));
    }

    /// Verifies that incompatible raw values are rejected when read.
    @Test
    public void incompatibleValue() {
        ArkivoFileSystemOption<Boolean> option = ArkivoFileSystemOption.of("test", "enabled", Boolean.class);
        Map<String, Object> environment = Map.of("test.enabled", 1);

        assertThrows(IllegalArgumentException.class, () -> option.read(environment));
    }

    /// Returns a Long option that parses string values.
    private static ArkivoFileSystemOption<Long> longOption() {
        return ArkivoFileSystemOption.of("test", "size", Long.class, value -> {
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
