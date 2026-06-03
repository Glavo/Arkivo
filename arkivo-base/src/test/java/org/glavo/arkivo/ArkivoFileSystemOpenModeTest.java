// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests Arkivo file system open modes.
@NotNullByDefault
public final class ArkivoFileSystemOpenModeTest {
    /// Verifies that open mode flags parse stable option strings.
    @Test
    public void parseMode() {
        assertEquals(ArkivoFileSystemOpenMode.RANDOM_READ, ArkivoFileSystemOpenMode.parse("random-read"));
        assertEquals(ArkivoFileSystemOpenMode.RANDOM_WRITE, ArkivoFileSystemOpenMode.parse("random_write"));
        assertEquals(ArkivoFileSystemOpenMode.STREAM_READ, ArkivoFileSystemOpenMode.parse("stream-read"));
        assertEquals(ArkivoFileSystemOpenMode.STREAM_WRITE, ArkivoFileSystemOpenMode.parse("stream-write"));
    }

    /// Verifies that open mode sets parse combined stable option strings.
    @Test
    public void parseModes() {
        ArkivoFileSystemOpenModes modes = ArkivoFileSystemOpenModes.parse("random-read,stream-read");

        assertEquals(true, modes.randomReadable());
        assertEquals(true, modes.streamReadable());
        assertEquals(false, modes.writable());
    }

    /// Verifies that the common open modes option accepts string values.
    @Test
    public void optionStringValue() {
        Map<String, Object> environment = new HashMap<>();

        ArkivoFileSystem.OPEN_MODES.putString(environment, "random-read+stream-write");

        assertEquals(
                ArkivoFileSystemOpenModes.of(
                        ArkivoFileSystemOpenMode.RANDOM_READ,
                        ArkivoFileSystemOpenMode.STREAM_WRITE
                ),
                ArkivoFileSystem.OPEN_MODES.read(environment)
        );
    }

    /// Verifies that unknown open mode flag names are rejected.
    @Test
    public void unknownMode() {
        assertThrows(IllegalArgumentException.class, () -> ArkivoFileSystemOpenMode.parse("stream-random"));
    }
}
