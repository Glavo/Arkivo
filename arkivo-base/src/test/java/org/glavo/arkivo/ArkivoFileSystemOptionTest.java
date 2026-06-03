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
    /// Verifies that typed values can be written and read.
    @Test
    public void typedValue() {
        ArkivoFileSystemOption<Boolean> option = ArkivoFileSystemOption.of("enabled", Boolean.class);
        Map<String, Object> environment = new HashMap<>();

        option.put(environment, true);

        assertEquals(Boolean.TRUE, option.read(environment));
    }

    /// Verifies that string values can be written and parsed.
    @Test
    public void stringValue() {
        ArkivoFileSystemOption<Long> option = ArkivoFileSystemOption.of("size", Long.class, Long::parseLong);
        Map<String, Object> environment = new HashMap<>();

        option.putString(environment, "4096");

        assertEquals(4096L, option.read(environment));
    }

    /// Verifies that compatible integral values are converted to the option type.
    @Test
    public void integralValueConversion() {
        ArkivoFileSystemOption<Long> option = ArkivoFileSystemOption.of("size", Long.class, Long::parseLong);

        assertEquals(1L, option.read(Map.of("size", (byte) 1)));
        assertEquals(2L, option.read(Map.of("size", (short) 2)));
        assertEquals(3L, option.read(Map.of("size", 3)));
    }

    /// Verifies that floating-point values are not converted to integral option types.
    @Test
    public void rejectFloatingPointToIntegralConversion() {
        ArkivoFileSystemOption<Long> option = ArkivoFileSystemOption.of("size", Long.class, Long::parseLong);

        assertThrows(IllegalArgumentException.class, () -> option.read(Map.of("size", 1.5)));
    }

    /// Verifies that unsupported string values are rejected before being written.
    @Test
    public void unsupportedStringValue() {
        ArkivoFileSystemOption<Boolean> option = ArkivoFileSystemOption.of("enabled", Boolean.class);
        Map<String, Object> environment = new HashMap<>();

        assertThrows(IllegalArgumentException.class, () -> option.putString(environment, "true"));
    }

    /// Verifies that invalid string values are rejected before being written.
    @Test
    public void invalidStringValue() {
        ArkivoFileSystemOption<Long> option = ArkivoFileSystemOption.of("size", Long.class, Long::parseLong);
        Map<String, Object> environment = new HashMap<>();

        assertThrows(NumberFormatException.class, () -> option.putString(environment, "large"));
    }

    /// Verifies that incompatible raw values are rejected when read.
    @Test
    public void incompatibleValue() {
        ArkivoFileSystemOption<Boolean> option = ArkivoFileSystemOption.of("enabled", Boolean.class);
        Map<String, Object> environment = Map.of("enabled", 1);

        assertThrows(IllegalArgumentException.class, () -> option.read(environment));
    }
}
