// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.zip.ZipEntryNameEncoding;
import org.glavo.arkivo.zip.ZipEncryption;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests ZIP file system environment configuration parsing.
@NotNullByDefault
public final class ZipArkivoFileSystemConfigTest {
    /// Verifies that default ZIP configuration uses the split size sentinel.
    @Test
    public void defaultSplitSizeUsesSentinel() {
        assertEquals(ZipArkivoFileSystemConfig.NO_SPLIT_SIZE, ZipArkivoFileSystemConfig.DEFAULTS.splitSize());
    }

    /// Verifies that ZIP file system option keys use the ZIP namespace.
    @Test
    public void zipOptionKeysUseZipNamespace() {
        assertEquals("arkivo.zip", ZipArkivoFileSystem.PASSWORD_PROVIDER.namespace());
        assertEquals("passwordProvider", ZipArkivoFileSystem.PASSWORD_PROVIDER.name());
        assertEquals("arkivo.zip.passwordProvider", ZipArkivoFileSystem.PASSWORD_PROVIDER.key());
        assertEquals("arkivo.zip.password", ZipArkivoFileSystem.PASSWORD.key());
        assertEquals("arkivo.zip.defaultEncryption", ZipArkivoFileSystem.DEFAULT_ENCRYPTION.key());
        assertEquals("arkivo.zip.splitSize", ZipArkivoFileSystem.SPLIT_SIZE.key());
        assertEquals("arkivo.zip.entryNameEncoding", ZipArkivoFileSystem.ENTRY_NAME_ENCODING.key());
        assertEquals("arkivo.threadSafety", ArkivoFileSystem.THREAD_SAFETY.key());
    }

    /// Verifies that string environment values are parsed through typed ZIP options.
    @Test
    public void stringValues() {
        Map<String, Object> environment = new HashMap<>();
        ArkivoFileSystem.THREAD_SAFETY.putString(environment, "strict");
        ZipArkivoFileSystem.DEFAULT_ENCRYPTION.putString(environment, "winzip-aes-256");
        ZipArkivoFileSystem.SPLIT_SIZE.putString(environment, "1024");
        ZipArkivoFileSystem.ENTRY_NAME_ENCODING.putString(environment, "gb18030");

        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);

        assertEquals(ZipEncryption.winZipAes256(), config.defaultEncryption());
        assertEquals(1024L, config.splitSize());
        assertEquals(ZipEntryNameEncoding.parse("gb18030"), config.entryNameEncoding());
        assertEquals(ArkivoFileSystemThreadSafety.STRICT, config.threadSafety());
    }

    /// Verifies that split size accepts compatible integral environment values.
    @Test
    public void integralSplitSize() {
        Map<String, Object> environment = Map.of(ZipArkivoFileSystem.SPLIT_SIZE.key(), 1024);

        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);

        assertEquals(1024L, config.splitSize());
    }

    /// Verifies that fixed password bytes are converted into a password provider.
    @Test
    public void passwordBytes() throws Exception {
        Map<String, Object> environment = Map.of(ZipArkivoFileSystem.PASSWORD.key(), new byte[]{1, 2, 3});

        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);

        assertArrayEquals(new byte[]{1, 2, 3}, config.passwordProvider().passwordForArchive());
    }
}
