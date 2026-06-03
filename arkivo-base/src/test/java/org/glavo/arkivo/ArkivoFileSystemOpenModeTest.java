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
    /// Verifies that open modes parse stable option strings.
    @Test
    public void parse() {
        assertEquals(ArkivoFileSystemOpenMode.RANDOM_READ, ArkivoFileSystemOpenMode.parse("random-read"));
        assertEquals(ArkivoFileSystemOpenMode.RANDOM_WRITE, ArkivoFileSystemOpenMode.parse("random_write"));
        assertEquals(ArkivoFileSystemOpenMode.RANDOM_READ_WRITE, ArkivoFileSystemOpenMode.parse("random-read-write"));
        assertEquals(ArkivoFileSystemOpenMode.STREAM_READ, ArkivoFileSystemOpenMode.parse("stream-read"));
        assertEquals(ArkivoFileSystemOpenMode.STREAM_WRITE, ArkivoFileSystemOpenMode.parse("stream-write"));
    }

    /// Verifies that the common open mode option accepts string values.
    @Test
    public void optionStringValue() {
        Map<String, Object> environment = new HashMap<>();

        ArkivoFileSystem.OPEN_MODE.putString(environment, "stream-write");

        assertEquals(ArkivoFileSystemOpenMode.STREAM_WRITE, ArkivoFileSystem.OPEN_MODE.read(environment));
    }

    /// Verifies that unknown open mode names are rejected.
    @Test
    public void unknownMode() {
        assertThrows(IllegalArgumentException.class, () -> ArkivoFileSystemOpenMode.parse("stream-random"));
    }
}
