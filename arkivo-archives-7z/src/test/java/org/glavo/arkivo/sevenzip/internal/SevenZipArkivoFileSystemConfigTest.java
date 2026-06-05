// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.sevenzip.SevenZipArkivoFileSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests 7z file system environment configuration parsing.
@NotNullByDefault
public final class SevenZipArkivoFileSystemConfigTest {
    /// Verifies that default 7z configuration uses the split size sentinel.
    @Test
    public void defaultSplitSizeUsesSentinel() {
        assertEquals(SevenZipArkivoFileSystemConfig.NO_SPLIT_SIZE, SevenZipArkivoFileSystemConfig.DEFAULTS.splitSize());
    }

    /// Verifies that 7z file system option keys use the 7z namespace.
    @Test
    public void sevenZipOptionKeysUseSevenZipNamespace() {
        assertEquals("arkivo.7z.passwordProvider", SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key());
        assertEquals("arkivo.7z.password", SevenZipArkivoFileSystem.PASSWORD.key());
        assertEquals("arkivo.7z.splitSize", SevenZipArkivoFileSystem.SPLIT_SIZE.key());
        assertEquals("arkivo.7z.encryptHeaders", SevenZipArkivoFileSystem.ENCRYPT_HEADERS.key());
        assertEquals("arkivo.threadSafety", ArkivoFileSystem.THREAD_SAFETY.key());
    }

    /// Verifies that string environment values are parsed through typed 7z options.
    @Test
    public void stringValues() {
        Map<String, Object> environment = new HashMap<>();
        ArkivoFileSystem.THREAD_SAFETY.putString(environment, "strict");
        SevenZipArkivoFileSystem.SPLIT_SIZE.putString(environment, "1024");
        SevenZipArkivoFileSystem.ENCRYPT_HEADERS.putString(environment, "true");

        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromEnvironment(environment);

        assertEquals(1024L, config.splitSize());
        assertEquals(true, config.encryptHeaders());
        assertEquals(ArkivoFileSystemThreadSafety.STRICT, config.threadSafety());
    }

    /// Verifies that fixed password bytes are converted into a password provider.
    @Test
    public void passwordBytes() throws Exception {
        Map<String, Object> environment = Map.of(SevenZipArkivoFileSystem.PASSWORD.key(), new byte[]{1, 2, 3});

        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromEnvironment(environment);

        assertArrayEquals(new byte[]{1, 2, 3}, config.passwordProvider().passwordForArchive());
    }

    /// Verifies that 7z write mode is rejected until writing is implemented.
    @Test
    public void writeModeIsNotImplementedYet() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> SevenZipArkivoFileSystemConfig.fromEnvironment(Map.of(
                        ArkivoFileSystem.OPEN_OPTIONS.key(),
                        Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                ))
        );
    }
}
