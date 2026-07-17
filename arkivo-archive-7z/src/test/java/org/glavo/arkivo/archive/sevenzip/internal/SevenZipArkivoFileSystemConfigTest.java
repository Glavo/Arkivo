// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.sevenzip.SevenZipArchiveOptions;
import org.glavo.arkivo.archive.sevenzip.SevenZipCompression;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilter;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilterChain;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies strongly typed 7z operation options map to internal file-system configuration.
@NotNullByDefault
final class SevenZipArkivoFileSystemConfigTest {
    /// Verifies read options preserve common synchronization, storage, and resource-limit settings.
    @Test
    void mapsReadOptions() {
        ArkivoEditStorage storage = ArkivoEditStorage.memory();
        ArchiveReadLimits limits = ArchiveReadLimits.builder()
                .maximumEntryCount(2L)
                .maximumEntrySize(3L)
                .maximumTotalEntrySize(4L)
                .maximumMetadataSize(5L)
                .maximumCompressionWindowSize(6L)
                .maximumDecoderMemorySize(7L)
                .build();
        ArkivoPasswordProvider passwordProvider = request -> new byte[]{1, 2, 3};
        SevenZipArchiveOptions.Read options = new SevenZipArchiveOptions.Read(
                new ArchiveReadOptions(ArkivoFileSystemThreadSafety.STRICT, storage, limits),
                passwordProvider
        );

        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromReadOptions(options);

        assertFalse(config.archiveWritable());
        assertFalse(config.archiveUpdate());
        assertEquals(java.util.Set.of(StandardOpenOption.READ), config.openOptions());
        assertSame(passwordProvider, config.passwordProvider());
        assertSame(storage, config.editStorage());
        assertSame(limits, config.readLimits());
        assertEquals(ArkivoFileSystemThreadSafety.STRICT, config.threadSafety());
        assertNull(config.commitTarget());
    }

    /// Verifies creation options preserve format-specific output configuration.
    @Test
    void mapsCreateOptions() {
        ArkivoEditStorage storage = ArkivoEditStorage.memory();
        SevenZipCompression compression = SevenZipCompression.lzma2(1 << 20);
        SevenZipFilterChain filters = SevenZipFilterChain.of(SevenZipFilter.delta(4));
        SevenZipArchiveOptions.Create options = new SevenZipArchiveOptions.Create(
                new ArchiveCreateOptions(ArkivoFileSystemThreadSafety.STRICT, storage),
                null,
                compression,
                filters,
                4,
                true
        );

        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromCreateOptions(options);

        assertTrue(config.archiveWritable());
        assertFalse(config.archiveUpdate());
        assertSame(compression, config.compression());
        assertSame(filters, config.filters());
        assertEquals(4, config.solidFileCount());
        assertTrue(config.encryptHeaders());
        assertSame(storage, config.editStorage());
        assertSame(ArchiveReadLimits.UNLIMITED, config.readLimits());
    }

    /// Verifies update options combine common publication and read limits with 7z output settings.
    @Test
    void mapsUpdateOptions() {
        ArkivoCommitTarget commitTarget = ArkivoCommitTarget.writeTo(
                java.nio.file.Path.of("build", "sevenzip-config-test.7z")
        );
        ArchiveReadLimits limits = ArchiveReadLimits.builder()
                .maximumCompressionWindowSize(1L << 20)
                .maximumDecoderMemorySize(2L << 20)
                .build();
        SevenZipCompression compression = SevenZipCompression.ppmd(4, 1 << 20);
        SevenZipArchiveOptions.Update options = new SevenZipArchiveOptions.Update(
                ArchiveUpdateOptions.DEFAULT
                        .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT)
                        .withCommitTarget(commitTarget)
                        .withLimits(limits),
                null,
                compression,
                SevenZipFilterChain.EMPTY,
                2,
                false
        );

        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromUpdateOptions(options);

        assertTrue(config.archiveWritable());
        assertTrue(config.archiveUpdate());
        assertSame(commitTarget, config.commitTarget());
        assertSame(limits, config.readLimits());
        assertSame(compression, config.compression());
        assertEquals(2, config.solidFileCount());
        assertEquals(ArkivoFileSystemThreadSafety.STRICT, config.threadSafety());
    }
}
