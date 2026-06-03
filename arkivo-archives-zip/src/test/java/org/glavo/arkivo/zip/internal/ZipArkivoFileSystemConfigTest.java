// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

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
        ZipArkivoFileSystem.READ_ONLY_OPTION.putString(environment, "true");
        ZipArkivoFileSystem.DEFAULT_ENCRYPTION_OPTION.putString(environment, "winzip-aes-256");
        ZipArkivoFileSystem.SPLIT_SIZE_OPTION.putString(environment, "1024");
        ZipArkivoFileSystem.ENTRY_NAME_ENCODING_OPTION.putString(environment, "gb18030");

        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);

        assertEquals(true, config.readOnly());
        assertEquals(ZipEncryption.winZipAes256(), config.defaultEncryption());
        assertEquals(1024L, config.splitSize());
        assertEquals(ZipEntryNameEncoding.parse("gb18030"), config.entryNameEncoding());
    }

    /// Verifies that invalid boolean string values are rejected.
    @Test
    public void invalidBooleanString() {
        Map<String, Object> environment = Map.of(ZipArkivoFileSystem.READ_ONLY, "yes");

        assertThrows(IllegalArgumentException.class, () -> ZipArkivoFileSystemConfig.fromEnvironment(environment));
    }
}
