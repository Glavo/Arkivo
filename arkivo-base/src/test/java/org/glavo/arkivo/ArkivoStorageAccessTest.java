// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests Arkivo storage access values.
@NotNullByDefault
public final class ArkivoStorageAccessTest {
    /// Verifies that storage access values parse stable option strings.
    @Test
    public void parseAccess() {
        assertEquals(ArkivoStorageAccess.RANDOM_READ, ArkivoStorageAccess.parse("random-read"));
        assertEquals(ArkivoStorageAccess.RANDOM_WRITE, ArkivoStorageAccess.parse("random_write"));
        assertEquals(ArkivoStorageAccess.STREAM_READ, ArkivoStorageAccess.parse("stream-read"));
        assertEquals(ArkivoStorageAccess.STREAM_WRITE, ArkivoStorageAccess.parse("stream-write"));
    }

    /// Verifies that storage access sets parse combined stable option strings.
    @Test
    public void parseAccessSet() {
        ArkivoStorageAccessSet access = ArkivoStorageAccessSet.parse("random-read,stream-read");

        assertEquals(true, access.randomReadable());
        assertEquals(true, access.streamReadable());
        assertEquals(false, access.writable());
    }

    /// Verifies that the common storage access option accepts string values.
    @Test
    public void optionStringValue() {
        Map<String, Object> environment = new HashMap<>();

        ArkivoFileSystem.STORAGE_ACCESS.putString(environment, "random-read+stream-write");

        assertEquals(
                ArkivoStorageAccessSet.of(
                        ArkivoStorageAccess.RANDOM_READ,
                        ArkivoStorageAccess.STREAM_WRITE
                ),
                ArkivoFileSystem.STORAGE_ACCESS.read(environment)
        );
    }

    /// Verifies that unknown storage access names are rejected.
    @Test
    public void unknownAccess() {
        assertThrows(IllegalArgumentException.class, () -> ArkivoStorageAccess.parse("stream-random"));
    }
}
