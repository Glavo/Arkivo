// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies immutable AR operation-option derivation and validation.
@NotNullByDefault
final class ArArchiveOptionsTest {
    /// Verifies every operation role preserves common options and exposes its effective metadata detector.
    @Test
    void derivesCommonAndMetadataConfiguration() {
        ArchiveMetadataCharsetDetector detector =
                ArchiveMetadataCharsetDetector.fixed(StandardCharsets.UTF_16LE);
        ArchiveReadOptions readCommon = ArchiveReadOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.CONCURRENT_READ);
        ArchiveCreateOptions createCommon = ArchiveCreateOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT);
        ArchiveUpdateOptions updateCommon = ArchiveUpdateOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT);

        ArArchiveOptions.Read read = ArArchiveOptions.READ_DEFAULTS
                .withCommon(readCommon)
                .withMetadataCharsetDetector(detector);
        ArArchiveOptions.Create create = ArArchiveOptions.CREATE_DEFAULTS
                .withCommon(createCommon)
                .withMetadataCharsetDetector(detector);
        ArArchiveOptions.Update update = ArArchiveOptions.UPDATE_DEFAULTS
                .withCommon(updateCommon)
                .withMetadataCharsetDetector(detector);

        assertEquals(readCommon, read.common().withMetadataCharsetDetector(null));
        assertSame(detector, read.metadataCharsetDetector());
        assertEquals(createCommon, create.common().withMetadataCharsetDetector(null));
        assertSame(detector, create.metadataCharsetDetector());
        assertEquals(updateCommon, update.common().withMetadataCharsetDetector(null));
        assertSame(detector, update.metadataCharsetDetector());
        assertSame(
                ArArchiveOptions.DEFAULT_METADATA_CHARSET_DETECTOR,
                ArArchiveOptions.CREATE_DEFAULTS.metadataCharsetDetector()
        );
        assertSame(
                ArArchiveOptions.DEFAULT_METADATA_CHARSET_DETECTOR,
                ArArchiveOptions.UPDATE_DEFAULTS.metadataCharsetDetector()
        );
    }

    /// Verifies records and non-null derivation methods reject absent configuration values.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void rejectsNullConfigurationValues() {
        assertThrows(NullPointerException.class, () -> new ArArchiveOptions.Read(null));
        assertThrows(NullPointerException.class, () -> new ArArchiveOptions.Create(null));
        assertThrows(NullPointerException.class, () -> new ArArchiveOptions.Update(null));
        assertThrows(NullPointerException.class, () -> ArArchiveOptions.READ_DEFAULTS.withCommon(null));
        assertThrows(
                NullPointerException.class,
                () -> ArArchiveOptions.READ_DEFAULTS.withMetadataCharsetDetector(null)
        );
        assertThrows(NullPointerException.class, () -> ArArchiveOptions.CREATE_DEFAULTS.withCommon(null));
        assertThrows(
                NullPointerException.class,
                () -> ArArchiveOptions.CREATE_DEFAULTS.withMetadataCharsetDetector(null)
        );
        assertThrows(NullPointerException.class, () -> ArArchiveOptions.UPDATE_DEFAULTS.withCommon(null));
        assertThrows(
                NullPointerException.class,
                () -> ArArchiveOptions.UPDATE_DEFAULTS.withMetadataCharsetDetector(null)
        );
    }
}
