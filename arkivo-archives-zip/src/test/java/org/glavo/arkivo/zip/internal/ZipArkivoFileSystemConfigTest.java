// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoStorageAccess;
import org.glavo.arkivo.ArkivoStorageAccessSet;
import org.glavo.arkivo.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.zip.ZipEntryNameEncoding;
import org.glavo.arkivo.zip.ZipEncryption;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests ZIP file system environment configuration parsing.
@NotNullByDefault
public final class ZipArkivoFileSystemConfigTest {
    /// Verifies that string environment values are parsed through typed ZIP options.
    @Test
    public void stringValues() {
        Map<String, Object> environment = new HashMap<>();
        ArkivoFileSystem.STORAGE_ACCESS.putString(environment, "random-read,stream-read");
        ZipArkivoFileSystem.DEFAULT_ENCRYPTION.putString(environment, "winzip-aes-256");
        ZipArkivoFileSystem.SPLIT_SIZE.putString(environment, "1024");
        ZipArkivoFileSystem.ENTRY_NAME_ENCODING.putString(environment, "gb18030");

        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);

        assertEquals(
                ArkivoStorageAccessSet.of(
                        ArkivoStorageAccess.RANDOM_READ,
                        ArkivoStorageAccess.STREAM_READ
                ),
                config.storageAccess()
        );
        assertEquals(ZipEncryption.winZipAes256(), config.defaultEncryption());
        assertEquals(1024L, config.splitSize());
        assertEquals(ZipEntryNameEncoding.parse("gb18030"), config.entryNameEncoding());
    }

    /// Verifies that invalid storage access string values are rejected.
    @Test
    public void invalidStorageAccessString() {
        Map<String, Object> environment = Map.of(ArkivoFileSystem.STORAGE_ACCESS.key(), "stream-random");

        assertThrows(IllegalArgumentException.class, () -> ZipArkivoFileSystemConfig.fromEnvironment(environment));
    }

    /// Verifies that split size accepts compatible integral environment values.
    @Test
    public void integralSplitSize() {
        Map<String, Object> environment = Map.of(ZipArkivoFileSystem.SPLIT_SIZE.key(), 1024);

        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);

        assertEquals(1024L, config.splitSize());
    }
}
