// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies immutable ZIP operation-option derivation and validation.
@NotNullByDefault
final class ZipArchiveOptionsTest {
    /// Verifies read, creation, and update helpers preserve independent common and format-specific settings.
    @Test
    void derivesOperationConfiguration() {
        ArkivoPasswordProvider passwordProvider = ArkivoPasswordProvider.none();
        ArchiveMetadataCharsetDetector detector =
                ArchiveMetadataCharsetDetector.fixed(StandardCharsets.UTF_16LE);
        ArchiveReadOptions readCommon = ArchiveReadOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.CONCURRENT_READ);
        ZipArchiveOptions.Read read = ZipArchiveOptions.READ_DEFAULTS
                .withCommon(readCommon)
                .withPasswordProvider(passwordProvider)
                .withLegacyCharsetDetector(detector);
        assertSame(passwordProvider, read.passwordProvider());
        assertSame(detector, read.legacyCharsetDetector());
        assertNull(read.withPasswordProvider(null).passwordProvider());

        ArchiveCreateOptions createCommon = ArchiveCreateOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT);
        ZipArchiveOptions.Create create = ZipArchiveOptions.CREATE_DEFAULTS
                .withCommon(createCommon)
                .withPasswordProvider(passwordProvider)
                .withDefaultEncryption(ZipEncryption.WINZIP_AES_256);
        assertSame(passwordProvider, create.passwordProvider());
        assertSame(ZipEncryption.WINZIP_AES_256, create.defaultEncryption());
        assertSame(ArkivoFileSystemThreadSafety.STRICT, create.common().threadSafety());

        ArchiveUpdateOptions updateCommon = ArchiveUpdateOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT);
        ZipArchiveOptions.Update update = ZipArchiveOptions.UPDATE_DEFAULTS
                .withCommon(updateCommon)
                .withPasswordProvider(passwordProvider)
                .withDefaultEncryption(ZipEncryption.ZIP_CRYPTO)
                .withLegacyCharsetDetector(detector);
        assertSame(passwordProvider, update.passwordProvider());
        assertSame(ZipEncryption.ZIP_CRYPTO, update.defaultEncryption());
        assertSame(detector, update.legacyCharsetDetector());
        assertSame(ArkivoFileSystemThreadSafety.STRICT, update.common().threadSafety());
    }

    /// Verifies records and non-null derivation methods reject absent configuration values.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void rejectsNullConfigurationValues() {
        assertThrows(NullPointerException.class, () -> new ZipArchiveOptions.Read(null));
        assertThrows(
                NullPointerException.class,
                () -> new ZipArchiveOptions.Create(ArchiveCreateOptions.DEFAULT, null)
        );
        assertThrows(
                NullPointerException.class,
                () -> new ZipArchiveOptions.Update(ArchiveUpdateOptions.DEFAULT, null)
        );
        assertThrows(
                NullPointerException.class,
                () -> ZipArchiveOptions.READ_DEFAULTS.withLegacyCharsetDetector(null)
        );
        assertThrows(NullPointerException.class, () -> ZipArchiveOptions.CREATE_DEFAULTS.withCommon(null));
        assertThrows(
                NullPointerException.class,
                () -> ZipArchiveOptions.CREATE_DEFAULTS.withDefaultEncryption(null)
        );
        assertThrows(
                NullPointerException.class,
                () -> ZipArchiveOptions.UPDATE_DEFAULTS.withDefaultEncryption(null)
        );
        assertThrows(
                NullPointerException.class,
                () -> ZipArchiveOptions.UPDATE_DEFAULTS.withLegacyCharsetDetector(null)
        );
    }
}
