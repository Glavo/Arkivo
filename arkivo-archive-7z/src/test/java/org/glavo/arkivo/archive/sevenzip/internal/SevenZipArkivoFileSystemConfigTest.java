// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoEditStorageFactory;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.internal.ArchiveOptions;
import org.glavo.arkivo.archive.sevenzip.SevenZipArchiveOptions;
import org.glavo.arkivo.archive.sevenzip.SevenZipCompression;
import org.glavo.arkivo.archive.sevenzip.SevenZipCompressionMethod;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilter;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilterChain;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilterMethod;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        ArkivoEditStorageFactory storageFactory = () -> storage;
        SevenZipArchiveOptions.Read options = new SevenZipArchiveOptions.Read(
                new ArchiveReadOptions(
                        ArkivoFileSystemThreadSafety.STRICT,
                        storageFactory,
                        passwordProvider,
                        null,
                        limits
                )
        );

        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromReadOptions(options);

        assertFalse(config.archiveWritable());
        assertFalse(config.archiveUpdate());
        assertEquals(java.util.Set.of(StandardOpenOption.READ), config.openOptions());
        assertSame(passwordProvider, config.passwordProvider());
        assertSame(storageFactory, config.editStorageFactory());
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
        ArkivoEditStorageFactory storageFactory = () -> storage;
        SevenZipArchiveOptions.Create options = new SevenZipArchiveOptions.Create(
                new ArchiveCreateOptions(ArkivoFileSystemThreadSafety.STRICT, storageFactory, null, null),
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
        assertSame(storageFactory, config.editStorageFactory());
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

    /// Verifies NIO environment values accept every documented scalar and format-specific representation.
    @Test
    void parsesDocumentedEnvironmentValueForms() {
        SevenZipCompression compression = SevenZipCompression.lzma2(1 << 20);
        assertSame(compression, writeConfig("arkivo.7z.compression", compression).compression());
        assertEquals(
                SevenZipCompression.bzip2(),
                writeConfig("arkivo.7z.compression", SevenZipCompressionMethod.BZIP2).compression()
        );
        assertEquals(
                SevenZipCompression.deflate64(),
                writeConfig("arkivo.7z.compression", "deflate64").compression()
        );

        SevenZipFilter filter = SevenZipFilter.delta(4);
        assertEquals(
                SevenZipFilterChain.of(filter),
                writeConfig("arkivo.7z.filter", filter).filters()
        );
        assertEquals(
                SevenZipFilterChain.of(SevenZipFilter.bcjX86()),
                writeConfig("arkivo.7z.filter", SevenZipFilterMethod.BCJ_X86).filters()
        );
        assertEquals(
                SevenZipFilterChain.of(SevenZipFilter.bcjArm()),
                writeConfig("arkivo.7z.filter", "bcj-arm").filters()
        );

        SevenZipFilterChain chain = SevenZipFilterChain.of(filter, SevenZipFilter.bcjX86());
        assertSame(chain, writeConfig("arkivo.7z.filters", chain).filters());
        assertEquals(
                chain,
                writeConfig("arkivo.7z.filters", List.of(filter, SevenZipFilter.bcjX86())).filters()
        );
        assertSame(
                SevenZipFilterChain.EMPTY,
                writeConfig("arkivo.7z.filters", List.of()).filters()
        );
        assertEquals(
                SevenZipFilterChain.of(SevenZipFilter.bcjRiscV()),
                writeConfig("arkivo.7z.filters", "bcj-riscv").filters()
        );

        assertEquals(10L, writeConfig("arkivo.7z.splitSize", 10L).splitSize());
        assertEquals(11L, writeConfig("arkivo.7z.splitSize", (byte) 11).splitSize());
        assertEquals(12L, writeConfig("arkivo.7z.splitSize", (short) 12).splitSize());
        assertEquals(13L, writeConfig("arkivo.7z.splitSize", 13).splitSize());
        assertEquals(14L, writeConfig("arkivo.7z.splitSize", "14").splitSize());

        assertEquals(2, writeConfig("arkivo.7z.solidFileCount", (byte) 2).solidFileCount());
        assertEquals(3, writeConfig("arkivo.7z.solidFileCount", (short) 3).solidFileCount());
        assertEquals(4, writeConfig("arkivo.7z.solidFileCount", 4).solidFileCount());
        assertEquals(5, writeConfig("arkivo.7z.solidFileCount", 5L).solidFileCount());
        assertEquals(6, writeConfig("arkivo.7z.solidFileCount", "6").solidFileCount());

        assertTrue(writeConfig("arkivo.7z.encryptHeaders", true).encryptHeaders());
        assertTrue(writeConfig("arkivo.7z.encryptHeaders", "true").encryptHeaders());
        assertFalse(writeConfig("arkivo.7z.encryptHeaders", "false").encryptHeaders());
    }

    /// Verifies malformed NIO environment representations fail before any archive is opened or created.
    @Test
    void rejectsMalformedEnvironmentValueForms() {
        assertThrows(
                IllegalArgumentException.class,
                () -> writeConfig("arkivo.7z.compression", new Object())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> writeConfig("arkivo.7z.filter", new Object())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> writeConfig("arkivo.7z.filters", List.of(SevenZipFilter.delta(), "invalid"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> writeConfig(Map.of(
                        "arkivo.7z.filter", SevenZipFilter.delta(),
                        "arkivo.7z.filters", SevenZipFilterChain.EMPTY
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> writeConfig("arkivo.7z.splitSize", 1.5)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> writeConfig("arkivo.7z.solidFileCount", Long.MAX_VALUE)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> writeConfig("arkivo.7z.solidFileCount", 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> writeConfig("arkivo.7z.solidFileCount", new Object())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> writeConfig("arkivo.7z.encryptHeaders", 1)
        );
    }

    /// Verifies the public configuration constructor normalizes default access and validates split sizes.
    @Test
    void constructsConfigurationDirectly() {
        ArkivoPasswordProvider passwordProvider = request -> new byte[]{1, 2, 3};
        SevenZipCompression compression = SevenZipCompression.lzma2(1 << 20);
        SevenZipFilterChain filters = SevenZipFilterChain.of(SevenZipFilter.delta(4));
        SevenZipArkivoFileSystemConfig config = new SevenZipArkivoFileSystemConfig(
                Set.of(),
                passwordProvider,
                compression,
                filters,
                4096L,
                true,
                ArkivoFileSystemThreadSafety.STRICT
        );

        assertEquals(Set.of(StandardOpenOption.READ), config.openOptions());
        assertFalse(config.archiveWritable());
        assertFalse(config.archiveUpdate());
        assertSame(passwordProvider, config.passwordProvider());
        assertSame(compression, config.compression());
        assertSame(filters, config.filters());
        assertEquals(4096L, config.splitSize());
        assertTrue(config.splitSizeConfigured());
        assertTrue(config.encryptHeaders());
        assertEquals(ArkivoFileSystemThreadSafety.STRICT, config.threadSafety());
        assertSame(ArchiveReadLimits.UNLIMITED, config.readLimits());

        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipArkivoFileSystemConfig(
                        Set.of(StandardOpenOption.READ),
                        null,
                        SevenZipCompression.copy(),
                        SevenZipFilterChain.EMPTY,
                        0L,
                        false,
                        ArkivoFileSystemThreadSafety.CONCURRENT_READ
                )
        );
    }

    /// Verifies generic writer and update parsers select their documented default access modes.
    @Test
    void selectsOperationSpecificDefaultAccessModes() {
        ArchiveOptions empty = ArchiveOptions.fromEnvironment(Map.of());

        SevenZipArkivoFileSystemConfig writer = SevenZipArkivoFileSystemConfig.fromWriterOptions(empty);
        assertEquals(Set.of(
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        ), writer.openOptions());
        assertTrue(writer.archiveWritable());
        assertFalse(writer.archiveUpdate());

        SevenZipArkivoFileSystemConfig update = SevenZipArkivoFileSystemConfig.fromUpdateOptions(empty);
        assertEquals(Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE), update.openOptions());
        assertTrue(update.archiveWritable());
        assertTrue(update.archiveUpdate());
    }

    /// Verifies raw archive open options reject every unsupported access-mode combination.
    @Test
    void rejectsInvalidArchiveOpenOptionCombinations() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> configWithOpenOptions(Set.of(StandardOpenOption.APPEND))
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> configWithOpenOptions(Set.of(
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                ))
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> configWithOpenOptions(Set.of(
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.DELETE_ON_CLOSE
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> configWithOpenOptions(Set.of(StandardOpenOption.READ, StandardOpenOption.CREATE))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> configWithOpenOptions(Set.of(StandardOpenOption.CREATE_NEW))
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> configWithOpenOptions(Set.of(StandardOpenOption.WRITE))
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> configWithOpenOptions(Set.of(
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.DELETE_ON_CLOSE
                ))
        );
    }

    /// Verifies output-only options cannot leak into incompatible archive access modes.
    @Test
    void rejectsOptionsInIncompatibleArchiveModes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> readConfig("arkivo.7z.compression", SevenZipCompression.copy())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> readConfig("arkivo.7z.filter", SevenZipFilter.delta())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> readConfig("arkivo.7z.filters", SevenZipFilterChain.EMPTY)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> readConfig("arkivo.7z.solidFileCount", 2)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> readConfig("arkivo.7z.encryptHeaders", true)
        );

        ArkivoCommitTarget commitTarget = ArkivoCommitTarget.writeTo(
                Path.of("build", "sevenzip-config-target.7z")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> readConfig("arkivo.commitTarget", commitTarget)
        );

        ArkivoEditStorageFactory storageFactory = ArkivoEditStorage::memory;
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipArkivoFileSystemConfig.fromOptions(ArchiveOptions.fromEnvironment(Map.of(
                        "arkivo.openOptions",
                        Set.of(
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE
                        ),
                        "arkivo.editStorageFactory",
                        storageFactory
                )))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipArkivoFileSystemConfig.fromOptions(ArchiveOptions.fromEnvironment(Map.of(
                        "arkivo.openOptions",
                        Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                        "arkivo.7z.splitSize",
                        1024L,
                        "arkivo.commitTarget",
                        commitTarget
                )))
        );
    }

    /// Parses one read-only configuration containing a single environment value.
    private static SevenZipArkivoFileSystemConfig readConfig(String key, Object value) {
        return SevenZipArkivoFileSystemConfig.fromOptions(ArchiveOptions.fromEnvironment(Map.of(key, value)));
    }

    /// Parses one configuration containing explicit archive open options.
    private static SevenZipArkivoFileSystemConfig configWithOpenOptions(Set<? extends OpenOption> openOptions) {
        return SevenZipArkivoFileSystemConfig.fromOptions(ArchiveOptions.fromEnvironment(Map.of(
                "arkivo.openOptions",
                openOptions
        )));
    }

    /// Parses one writable configuration containing a single format-specific environment value.
    private static SevenZipArkivoFileSystemConfig writeConfig(String key, Object value) {
        return writeConfig(Map.of(key, value));
    }

    /// Parses one writable configuration containing the supplied format-specific environment values.
    private static SevenZipArkivoFileSystemConfig writeConfig(Map<String, ?> formatOptions) {
        LinkedHashMap<String, Object> environment = new LinkedHashMap<>();
        environment.put(
                "arkivo.openOptions",
                Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                )
        );
        environment.putAll(formatOptions);
        return SevenZipArkivoFileSystemConfig.fromOptions(ArchiveOptions.fromEnvironment(environment));
    }
}
