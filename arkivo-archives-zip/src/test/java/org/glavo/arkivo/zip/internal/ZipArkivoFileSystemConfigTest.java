// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoCommitTarget;
import org.glavo.arkivo.ArkivoEditStorage;
import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.ArkivoSourceMutationPolicy;
import org.glavo.arkivo.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.zip.ZipEntryNameEncoding;
import org.glavo.arkivo.zip.ZipEncryption;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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
