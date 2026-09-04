// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies immutable 7z operation-option derivation and validation.
@NotNullByDefault
final class SevenZipArchiveOptionsTest {
    /// Verifies read and creation helpers preserve every independently configured setting.
    @Test
    void derivesReadAndCreationConfiguration() {
        ArkivoPasswordProvider passwordProvider = ArkivoPasswordProvider.none();
        ArchiveReadOptions readCommon = ArchiveReadOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.CONCURRENT_READ);
        SevenZipArchiveOptions.Read read = SevenZipArchiveOptions.READ_DEFAULTS
                .withCommon(readCommon)
                .withPasswordProvider(passwordProvider);
        assertSame(passwordProvider, read.passwordProvider());
        assertSame(ArkivoFileSystemThreadSafety.CONCURRENT_READ, read.common().threadSafety());
        assertNull(read.withPasswordProvider(null).passwordProvider());

        ArchiveCreateOptions createCommon = ArchiveCreateOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT);
        SevenZipCompression compression = SevenZipCompression.lzma2();
        SevenZipFilterChain filters = SevenZipFilterChain.of(SevenZipFilter.delta(2));
        SevenZipArchiveOptions.Create create = SevenZipArchiveOptions.CREATE_DEFAULTS
                .withCommon(createCommon)
                .withPasswordProvider(passwordProvider)
                .withCompression(compression)
                .withFilters(filters)
                .withSolidFileCount(3)
                .withEncryptHeaders(true);
        assertSame(passwordProvider, create.passwordProvider());
        assertSame(compression, create.compression());
        assertSame(filters, create.filters());
        assertEquals(3, create.solidFileCount());
        assertTrue(create.encryptHeaders());
        assertSame(ArkivoFileSystemThreadSafety.STRICT, create.common().threadSafety());
    }

    /// Verifies update helpers preserve every independently configured setting.
    @Test
    void derivesUpdateConfiguration() {
        ArkivoPasswordProvider passwordProvider = ArkivoPasswordProvider.none();
        ArchiveUpdateOptions common = ArchiveUpdateOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT);
        SevenZipCompression compression = SevenZipCompression.zstandard(5);
        SevenZipFilterChain filters = SevenZipFilterChain.of(SevenZipFilter.bcjX86());

        SevenZipArchiveOptions.Update update = SevenZipArchiveOptions.UPDATE_DEFAULTS
                .withCommon(common)
                .withPasswordProvider(passwordProvider)
                .withCompression(compression)
                .withFilters(filters)
                .withSolidFileCount(4)
                .withEncryptHeaders(true);

        assertSame(passwordProvider, update.passwordProvider());
        assertSame(compression, update.compression());
        assertSame(filters, update.filters());
        assertEquals(4, update.solidFileCount());
        assertTrue(update.encryptHeaders());
        assertSame(ArkivoFileSystemThreadSafety.STRICT, update.common().threadSafety());
        assertNull(update.withPasswordProvider(null).passwordProvider());
    }

    /// Verifies records and non-null derivation methods reject invalid configuration values.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void rejectsInvalidConfigurationValues() {
        assertThrows(NullPointerException.class, () -> new SevenZipArchiveOptions.Read(null));
        assertThrows(
                NullPointerException.class,
                () -> SevenZipArchiveOptions.CREATE_DEFAULTS.withCommon(null)
        );
        assertThrows(
                NullPointerException.class,
                () -> SevenZipArchiveOptions.CREATE_DEFAULTS.withCompression(null)
        );
        assertThrows(
                NullPointerException.class,
                () -> SevenZipArchiveOptions.CREATE_DEFAULTS.withFilters(null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipArchiveOptions.CREATE_DEFAULTS.withSolidFileCount(0)
        );
        assertThrows(
                NullPointerException.class,
                () -> SevenZipArchiveOptions.UPDATE_DEFAULTS.withCompression(null)
        );
        assertThrows(
                NullPointerException.class,
                () -> SevenZipArchiveOptions.UPDATE_DEFAULTS.withFilters(null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipArchiveOptions.UPDATE_DEFAULTS.withSolidFileCount(-1)
        );
    }
}
