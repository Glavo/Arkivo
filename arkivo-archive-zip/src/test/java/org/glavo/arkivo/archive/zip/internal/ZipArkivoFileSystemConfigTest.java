// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.internal.ArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests ZIP file system environment configuration parsing.
@NotNullByDefault
public final class ZipArkivoFileSystemConfigTest {
    /// Verifies that default ZIP configuration uses the split size sentinel.
    @Test
    public void defaultSplitSizeUsesSentinel() {
        assertEquals(ZipArkivoFileSystemConfig.NO_SPLIT_SIZE, ZipArkivoFileSystemConfig.DEFAULTS.splitSize());
    }

    /// Verifies that string environment values are parsed through typed ZIP options.
    @Test
    public void stringValues() throws Exception {
        Map<String, Object> environment = new HashMap<>();
        environment.put("arkivo.threadSafety", "strict");
        environment.put("arkivo.zip.defaultEncryption", "zipcrypto");
        environment.put("arkivo.zip.splitSize", "65536");
        environment.put("arkivo.zip.legacyCharsetDetector", "gb18030");

        ZipArkivoFileSystemConfig config = fromEnvironment(environment);

        assertEquals(ZipEncryption.ZIP_CRYPTO, config.defaultEncryption());
        assertEquals(65536L, config.splitSize());
        assertEquals(
                Charset.forName("GB18030"),
                config.legacyCharsetDetector().detect(ByteBuffer.allocate(0))
        );
        assertEquals(ArkivoFileSystemThreadSafety.STRICT, config.threadSafety());
    }

    /// Verifies that ZIP encryption configuration rejects unrecognized identifiers.
    @Test
    public void unrecognizedEncryptionValue() {
        assertThrows(
                IllegalArgumentException.class,
                () -> fromEnvironment(Map.of("arkivo.zip.defaultEncryption", "traditional"))
        );
    }

    /// Verifies that split size accepts compatible integral environment values.
    @Test
    public void integralSplitSize() {
        Map<String, Object> environment = Map.of("arkivo.zip.splitSize", 65536);

        ZipArkivoFileSystemConfig config = fromEnvironment(environment);

        assertEquals(65536L, config.splitSize());
    }

    /// Verifies that writable ZIP configuration preserves split output settings.
    @Test
    public void writableSplitSize() {
        Map<String, Object> environment = Map.of(
                "arkivo.openOptions",
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                "arkivo.zip.splitSize",
                65536L
        );

        ZipArkivoFileSystemConfig config = fromEnvironment(environment);

        assertEquals(65536L, config.splitSize());
    }

    /// Verifies that ZIP configuration rejects split sizes outside the format bounds.
    @Test
    public void splitSizeBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> fromEnvironment(Map.of(
                        "arkivo.zip.splitSize",
                        ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE - 1L
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> fromEnvironment(Map.of(
                        "arkivo.zip.splitSize",
                        ZipArkivoFileSystem.MAXIMUM_SPLIT_SIZE + 1L
                ))
        );
    }

    /// Verifies that writable ZIP configuration accepts append mode.
    @Test
    public void writableAppendMode() {
        Map<String, Object> environment = Map.of(
                "arkivo.openOptions",
                Set.of(StandardOpenOption.APPEND, StandardOpenOption.WRITE)
        );

        ZipArkivoFileSystemConfig config = fromEnvironment(environment);

        assertEquals(true, config.archiveWritable());
        assertEquals(true, config.openOptions().contains(StandardOpenOption.APPEND));
    }

    /// Verifies that read/write update mode is normalized to writable append output options.
    @Test
    public void writableUpdateModeNormalizesToAppend() {
        Map<String, Object> environment = Map.of(
                "arkivo.openOptions",
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
        );

        ZipArkivoFileSystemConfig config = fromEnvironment(environment);

        assertEquals(true, config.archiveWritable());
        assertEquals(false, config.openOptions().contains(StandardOpenOption.READ));
        assertEquals(true, config.openOptions().contains(StandardOpenOption.WRITE));
        assertEquals(true, config.openOptions().contains(StandardOpenOption.APPEND));
    }

    /// Verifies that append and truncate modes cannot be combined.
    @Test
    public void writableAppendRejectsTruncate() {
        Map<String, Object> environment = Map.of(
                "arkivo.openOptions",
                Set.of(StandardOpenOption.APPEND, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        );

        assertThrows(IllegalArgumentException.class, () -> fromEnvironment(environment));
    }

    /// Verifies that password providers are preserved by ZIP configuration parsing.
    @Test
    public void passwordProvider() {
        ArkivoPasswordProvider passwordProvider = ArkivoPasswordProvider.fixed(new byte[]{1, 2, 3});
        Map<String, Object> environment = Map.of(
                "arkivo.zip.passwordProvider",
                passwordProvider
        );

        ZipArkivoFileSystemConfig config = fromEnvironment(environment);

        assertSame(passwordProvider, config.passwordProvider());
    }


    /// Verifies that ZIP file system configuration accepts common edit strategy options.
    @Test
    public void editStrategies() {
        ArkivoEditStorage storage = ArkivoEditStorage.memory();
        ArkivoCommitTarget commitTarget = ArkivoCommitTarget.replaceOriginal();
        Map<String, Object> environment = Map.of(
                "arkivo.editStorage", storage,
                "arkivo.commitTarget", commitTarget
        );

        ZipArkivoFileSystemConfig config = fromEnvironment(environment);

        assertSame(storage, config.editStorage());
        assertSame(commitTarget, config.commitTarget());
    }

    /// Parses raw NIO environment values through the public immutable option boundary.
    private static ZipArkivoFileSystemConfig fromEnvironment(Map<String, ?> environment) {
        return ZipArkivoFileSystemConfig.fromOptions(ArchiveOptions.fromEnvironment(environment));
    }
}
