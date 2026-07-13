// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoSourceMutationPolicy;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipEntryNameEncoding;
import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

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

    /// Verifies that ZIP file system option keys use the ZIP namespace.
    @Test
    public void zipOptionKeysUseZipNamespace() {
        assertEquals("arkivo.zip", ZipArkivoFileSystem.PASSWORD_PROVIDER.namespace());
        assertEquals("passwordProvider", ZipArkivoFileSystem.PASSWORD_PROVIDER.name());
        assertEquals("arkivo.zip.passwordProvider", ZipArkivoFileSystem.PASSWORD_PROVIDER.key());
        assertEquals("arkivo.zip.defaultEncryption", ZipArkivoFileSystem.DEFAULT_ENCRYPTION.key());
        assertEquals("arkivo.zip.splitSize", ZipArkivoFileSystem.SPLIT_SIZE.key());
        assertEquals("arkivo.zip.entryNameEncoding", ZipArkivoFileSystem.ENTRY_NAME_ENCODING.key());
        assertEquals("arkivo.threadSafety", ArkivoFileSystem.THREAD_SAFETY.key());
        assertEquals("arkivo.editStorage", ArkivoFileSystem.EDIT_STORAGE.key());
        assertEquals("arkivo.commitTarget", ArkivoFileSystem.COMMIT_TARGET.key());
        assertEquals("arkivo.sourceMutationPolicy", ArkivoFileSystem.SOURCE_MUTATION_POLICY.key());
    }

    /// Verifies that string environment values are parsed through typed ZIP options.
    @Test
    public void stringValues() {
        Map<String, Object> environment = new HashMap<>();
        ArkivoFileSystem.THREAD_SAFETY.putString(environment, "strict");
        ZipArkivoFileSystem.DEFAULT_ENCRYPTION.putString(environment, "winzip-aes-256");
        ZipArkivoFileSystem.SPLIT_SIZE.putString(environment, "65536");
        ZipArkivoFileSystem.ENTRY_NAME_ENCODING.putString(environment, "gb18030");

        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);

        assertEquals(ZipEncryption.winZipAes256(), config.defaultEncryption());
        assertEquals(65536L, config.splitSize());
        assertEquals(ZipEntryNameEncoding.parse("gb18030"), config.entryNameEncoding());
        assertEquals(ArkivoFileSystemThreadSafety.STRICT, config.threadSafety());
    }

    /// Verifies that split size accepts compatible integral environment values.
    @Test
    public void integralSplitSize() {
        Map<String, Object> environment = Map.of(ZipArkivoFileSystem.SPLIT_SIZE.key(), 65536);

        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);

        assertEquals(65536L, config.splitSize());
    }

    /// Verifies that writable ZIP configuration preserves split output settings.
    @Test
    public void writableSplitSize() {
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                ZipArkivoFileSystem.SPLIT_SIZE.key(),
                65536L
        );

        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);

        assertEquals(65536L, config.splitSize());
    }

    /// Verifies that ZIP configuration rejects split sizes outside the format bounds.
    @Test
    public void splitSizeBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ZipArkivoFileSystemConfig.fromEnvironment(Map.of(
                        ZipArkivoFileSystem.SPLIT_SIZE.key(),
                        ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE - 1L
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ZipArkivoFileSystemConfig.fromEnvironment(Map.of(
                        ZipArkivoFileSystem.SPLIT_SIZE.key(),
                        ZipArkivoFileSystem.MAXIMUM_SPLIT_SIZE + 1L
                ))
        );
    }
    /// Verifies that writable ZIP configuration accepts append mode.
    @Test
    public void writableAppendMode() {
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.APPEND, StandardOpenOption.WRITE)
        );

        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);

        assertEquals(true, config.archiveWritable());
        assertEquals(true, config.openOptions().contains(StandardOpenOption.APPEND));
    }

    /// Verifies that read/write update mode is normalized to writable append output options.
    @Test
    public void writableUpdateModeNormalizesToAppend() {
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
        );

        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);

        assertEquals(true, config.archiveWritable());
        assertEquals(false, config.openOptions().contains(StandardOpenOption.READ));
        assertEquals(true, config.openOptions().contains(StandardOpenOption.WRITE));
        assertEquals(true, config.openOptions().contains(StandardOpenOption.APPEND));
    }

    /// Verifies that append and truncate modes cannot be combined.
    @Test
    public void writableAppendRejectsTruncate() {
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.APPEND, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        );

        assertThrows(IllegalArgumentException.class, () -> ZipArkivoFileSystemConfig.fromEnvironment(environment));
    }

    /// Verifies that password providers are preserved by ZIP configuration parsing.
    @Test
    public void passwordProvider() {
        ArkivoPasswordProvider passwordProvider = ArkivoPasswordProvider.fixed(new byte[]{1, 2, 3});
        Map<String, Object> environment = Map.of(
                ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                passwordProvider
        );

        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);

        assertSame(passwordProvider, config.passwordProvider());
    }

    /// Verifies that the removed fixed password option is rejected.
    @Test
    public void legacyFixedPasswordOptionIsRejected() {
        Map<String, Object> environment = Map.of("arkivo.zip.password", new byte[]{1, 2, 3});

        assertThrows(IllegalArgumentException.class, () -> ZipArkivoFileSystemConfig.fromEnvironment(environment));
    }

    /// Verifies that ZIP file system configuration accepts common edit strategy options.
    @Test
    public void editStrategies() {
        ArkivoEditStorage storage = ArkivoEditStorage.memory();
        ArkivoCommitTarget commitTarget = ArkivoCommitTarget.replaceOriginal();
        ArkivoSourceMutationPolicy sourceMutationPolicy = ArkivoSourceMutationPolicy.patchWhenSafe();
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.EDIT_STORAGE.key(), storage,
                ArkivoFileSystem.COMMIT_TARGET.key(), commitTarget,
                ArkivoFileSystem.SOURCE_MUTATION_POLICY.key(), sourceMutationPolicy
        );

        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(environment);

        assertSame(storage, config.editStorage());
        assertSame(commitTarget, config.commitTarget());
        assertSame(sourceMutationPolicy, config.sourceMutationPolicy());
    }
}
