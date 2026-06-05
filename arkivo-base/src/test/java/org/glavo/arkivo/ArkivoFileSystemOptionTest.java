// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

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

    /// Verifies that floating-point values are not converted to integral option types.
    @Test
    public void rejectFloatingPointToIntegralConversion() {
        ArkivoFileSystemOption<Long> option = longOption();

        assertThrows(IllegalArgumentException.class, () -> option.read(Map.of("test.size", 1.5)));
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
