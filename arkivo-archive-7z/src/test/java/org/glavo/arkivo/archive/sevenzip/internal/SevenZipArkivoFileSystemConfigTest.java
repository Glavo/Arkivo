// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem;
import org.glavo.arkivo.archive.sevenzip.SevenZipCompression;
import org.glavo.arkivo.archive.sevenzip.SevenZipCompressionMethod;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilter;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilterChain;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilterMethod;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests 7z file system environment configuration parsing.
@NotNullByDefault
public final class SevenZipArkivoFileSystemConfigTest {
    /// Verifies that default 7z configuration uses the split size sentinel.
    @Test
    public void defaultSplitSizeUsesSentinel() {
        assertEquals(SevenZipArkivoFileSystemConfig.NO_SPLIT_SIZE, SevenZipArkivoFileSystemConfig.DEFAULTS.splitSize());
    }


    /// Verifies that explicit 7z output factories receive writable default open options.
    @Test
    public void writerEnvironmentUsesWritableDefaults() {
        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromWriterEnvironment(Map.of());

        assertEquals(true, config.archiveWritable());
        assertEquals(SevenZipCompression.copy(), config.compression());
        assertEquals(SevenZipFilterChain.EMPTY, config.filters());
        assertEquals(SevenZipArkivoFileSystemConfig.DEFAULT_SOLID_FILE_COUNT, config.solidFileCount());
        assertEquals(
                Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                ),
                config.openOptions()
        );
    }


    /// Verifies that 7z file system option keys use the 7z namespace.
    @Test
    public void sevenZipOptionKeysUseSevenZipNamespace() {
        assertEquals("arkivo.7z.passwordProvider", SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key());
        assertEquals("arkivo.7z.compression", SevenZipArkivoFileSystem.COMPRESSION.key());
        assertEquals("arkivo.7z.filter", SevenZipArkivoFileSystem.FILTER.key());
        assertEquals("arkivo.7z.solidFileCount", SevenZipArkivoFileSystem.SOLID_FILE_COUNT.key());
        assertEquals("arkivo.7z.splitSize", SevenZipArkivoFileSystem.SPLIT_SIZE.key());
        assertEquals("arkivo.7z.encryptHeaders", SevenZipArkivoFileSystem.ENCRYPT_HEADERS.key());
        assertEquals("arkivo.threadSafety", ArkivoFileSystem.THREAD_SAFETY.key());
        assertEquals("arkivo.editStorage", ArkivoFileSystem.EDIT_STORAGE.key());
    }


    /// Verifies that string environment values are parsed through typed 7z options.
    @Test
    public void stringValues() {
        Map<String, Object> environment = new HashMap<>();
        ArkivoFileSystem.THREAD_SAFETY.putString(environment, "strict");
        SevenZipArkivoFileSystem.SPLIT_SIZE.putString(environment, "1024");
        SevenZipArkivoFileSystem.SOLID_FILE_COUNT.putString(environment, "4");
        SevenZipArkivoFileSystem.ENCRYPT_HEADERS.putString(environment, "true");
        SevenZipArkivoFileSystem.COMPRESSION.putString(environment, "lzma2");
        SevenZipArkivoFileSystem.FILTER.putString(environment, "bcj-x86");

        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromWriterEnvironment(environment);

        assertEquals(1024L, config.splitSize());
        assertEquals(4, config.solidFileCount());
        assertEquals(true, config.encryptHeaders());
        assertEquals(SevenZipCompression.lzma2(), config.compression());
        assertEquals(SevenZipFilterChain.of(SevenZipFilter.bcjX86()), config.filters());
        assertEquals(ArkivoFileSystemThreadSafety.STRICT, config.threadSafety());
    }


    /// Verifies that password providers are preserved by 7z configuration parsing.
    @Test
    public void passwordProvider() {
        ArkivoPasswordProvider passwordProvider = ArkivoPasswordProvider.fixed(new byte[]{1, 2, 3});
        Map<String, Object> environment = Map.of(
                SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                passwordProvider
        );

        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromEnvironment(environment);

        assertSame(passwordProvider, config.passwordProvider());
    }


    /// Verifies typed compression methods and complete configurations are normalized from environment values.
    @Test
    public void compressionValues() {
        SevenZipArkivoFileSystemConfig methodConfig = SevenZipArkivoFileSystemConfig.fromWriterEnvironment(Map.of(
                SevenZipArkivoFileSystem.COMPRESSION.key(),
                SevenZipCompressionMethod.BZIP2
        ));
        SevenZipCompression customCompression = SevenZipCompression.deflate(2);
        SevenZipArkivoFileSystemConfig completeConfig = SevenZipArkivoFileSystemConfig.fromWriterEnvironment(Map.of(
                SevenZipArkivoFileSystem.COMPRESSION.key(),
                customCompression
        ));
        SevenZipArkivoFileSystemConfig namedConfig = SevenZipArkivoFileSystemConfig.fromWriterEnvironment(Map.of(
                SevenZipArkivoFileSystem.COMPRESSION.key(),
                "zstd"
        ));

        assertEquals(SevenZipCompression.bzip2(), methodConfig.compression());
        assertSame(customCompression, completeConfig.compression());
        assertEquals(SevenZipCompression.zstandard(), namedConfig.compression());
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipArkivoFileSystemConfig.fromEnvironment(Map.of(
                        SevenZipArkivoFileSystem.COMPRESSION.key(),
                        SevenZipCompression.lzma2()
                ))
        );
    }


    /// Verifies typed filter methods and complete configurations are normalized from environment values.
    @Test
    public void filterValues() {
        SevenZipArkivoFileSystemConfig methodConfig = SevenZipArkivoFileSystemConfig.fromWriterEnvironment(Map.of(
                SevenZipArkivoFileSystem.FILTER.key(),
                SevenZipFilterMethod.BCJ_ARM_THUMB
        ));
        SevenZipFilter customFilter = SevenZipFilter.delta(7);
        SevenZipArkivoFileSystemConfig completeConfig = SevenZipArkivoFileSystemConfig.fromWriterEnvironment(Map.of(
                SevenZipArkivoFileSystem.FILTER.key(),
                customFilter
        ));

        assertEquals(SevenZipFilterChain.of(SevenZipFilter.bcjArmThumb()), methodConfig.filters());
        assertEquals(SevenZipFilterChain.of(customFilter), completeConfig.filters());

        SevenZipFilterChain chain = SevenZipFilterChain.of(
                SevenZipFilter.bcjArm64(0x1000),
                SevenZipFilter.delta(4)
        );
        assertEquals(
                chain,
                SevenZipArkivoFileSystemConfig.fromWriterEnvironment(Map.of(
                        SevenZipArkivoFileSystem.FILTERS.key(),
                        chain.filters()
                )).filters()
        );
        assertEquals(
                SevenZipFilterChain.EMPTY,
                SevenZipArkivoFileSystemConfig.fromWriterEnvironment(Map.of(
                        SevenZipArkivoFileSystem.FILTERS.key(),
                        List.of()
                )).filters()
        );
        assertEquals(
                SevenZipFilterChain.of(SevenZipFilter.bcjRiscV()),
                SevenZipArkivoFileSystemConfig.fromWriterEnvironment(Map.of(
                        SevenZipArkivoFileSystem.FILTER.key(),
                        "bcj-riscv"
                )).filters()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipArkivoFileSystemConfig.fromEnvironment(Map.of(
                        SevenZipArkivoFileSystem.FILTER.key(),
                        SevenZipFilter.bcjX86()
                ))
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipArkivoFileSystemConfig.fromWriterEnvironment(Map.of(
                        SevenZipArkivoFileSystem.FILTER.key(),
                        SevenZipFilter.bcjX86(),
                        SevenZipArkivoFileSystem.FILTERS.key(),
                        chain
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipArkivoFileSystemConfig.fromWriterEnvironment(Map.of(
                        SevenZipArkivoFileSystem.FILTERS.key(),
                        List.of("bcj-x86")
                ))
        );
    }


    /// Verifies solid file-count normalization, validation, and write-only scope.
    @Test
    public void solidFileCountValues() {
        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromWriterEnvironment(Map.of(
                SevenZipArkivoFileSystem.SOLID_FILE_COUNT.key(),
                12L
        ));

        assertEquals(12, config.solidFileCount());
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipArkivoFileSystemConfig.fromWriterEnvironment(Map.of(
                        SevenZipArkivoFileSystem.SOLID_FILE_COUNT.key(),
                        0
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipArkivoFileSystemConfig.fromWriterEnvironment(Map.of(
                        SevenZipArkivoFileSystem.SOLID_FILE_COUNT.key(),
                        (long) Integer.MAX_VALUE + 1L
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipArkivoFileSystemConfig.fromEnvironment(Map.of(
                        SevenZipArkivoFileSystem.SOLID_FILE_COUNT.key(),
                        2
                ))
        );
    }

    /// Verifies that encrypted headers are accepted only for write configurations.
    @Test
    public void encryptedHeadersRequireWriteOptions() {
        SevenZipArkivoFileSystemConfig writerConfig = SevenZipArkivoFileSystemConfig.fromWriterEnvironment(Map.of(
                SevenZipArkivoFileSystem.ENCRYPT_HEADERS.key(),
                true
        ));

        assertEquals(true, writerConfig.encryptHeaders());
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipArkivoFileSystemConfig.fromEnvironment(Map.of(
                        SevenZipArkivoFileSystem.ENCRYPT_HEADERS.key(),
                        true
                ))
        );
    }


    /// Verifies that the removed fixed password option is rejected with migration guidance.
    @Test
    public void legacyFixedPasswordOptionIsRejected() {
        Map<String, Object> environment = Map.of("arkivo.7z.password", new byte[]{1, 2, 3});

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipArkivoFileSystemConfig.fromEnvironment(environment)
        );

        assertEquals(
                "The arkivo.7z.password option has been removed; use arkivo.7z.passwordProvider instead",
                exception.getMessage()
        );
    }


    /// Verifies that 7z write mode open options are accepted for archive creation.
    @Test
    public void writeModeOpenOptions() {
        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromEnvironment(Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                )
        ));

        assertEquals(true, config.archiveWritable());
        assertEquals(
                Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                ),
                config.openOptions()
        );
    }


    /// Verifies that 7z read/write options select complete-rewrite update mode.
    @Test
    public void updateModeOpenOptions() {
        SevenZipArkivoFileSystemConfig config = SevenZipArkivoFileSystemConfig.fromEnvironment(Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
        ));

        assertEquals(true, config.archiveWritable());
        assertEquals(true, config.archiveUpdate());
        assertEquals(
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                config.openOptions()
        );
    }


    /// Verifies that edit storage is accepted for reads and updates but rejected in forward-only write mode.
    @Test
    public void editStorageSupportsReadAndUpdateModes() {
        ArkivoEditStorage storage = ArkivoEditStorage.memory();
        SevenZipArkivoFileSystemConfig readConfig = SevenZipArkivoFileSystemConfig.fromEnvironment(Map.of(
                ArkivoFileSystem.EDIT_STORAGE.key(),
                storage
        ));
        SevenZipArkivoFileSystemConfig updateConfig = SevenZipArkivoFileSystemConfig.fromUpdateEnvironment(Map.of(
                ArkivoFileSystem.EDIT_STORAGE.key(),
                storage
        ));

        assertSame(storage, readConfig.editStorage());
        assertSame(storage, updateConfig.editStorage());
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipArkivoFileSystemConfig.fromWriterEnvironment(Map.of(
                        ArkivoFileSystem.EDIT_STORAGE.key(),
                        storage
                ))
        );
    }


    /// Verifies that 7z write mode requires a creation or truncation boundary.
    @Test
    public void writeModeRequiresCreationBoundary() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> SevenZipArkivoFileSystemConfig.fromEnvironment(Map.of(
                        ArkivoFileSystem.OPEN_OPTIONS.key(),
                        Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                ))
        );
    }
}
