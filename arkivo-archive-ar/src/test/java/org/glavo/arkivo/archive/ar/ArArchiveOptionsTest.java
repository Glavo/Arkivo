// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
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
    /// Verifies every operation role preserves common options and exposes its effective metadata decoder.
    @Test
    void derivesCommonAndMetadataConfiguration() {
        ArchiveMetadataDecoder metadataDecoder =
                ArchiveMetadataDecoder.forCharset(StandardCharsets.UTF_16LE);
        ArchiveReadOptions readCommon = ArchiveReadOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.CONCURRENT_READ);
        ArchiveCreateOptions createCommon = ArchiveCreateOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT);
        ArchiveUpdateOptions updateCommon = ArchiveUpdateOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT);

        ArArchiveOptions.Read read = ArArchiveOptions.READ_DEFAULTS
                .withCommon(readCommon)
                .withMetadataDecoder(metadataDecoder);
        ArArchiveOptions.Create create = ArArchiveOptions.CREATE_DEFAULTS
                .withCommon(createCommon)
                .withMetadataDecoder(metadataDecoder);
        ArArchiveOptions.Update update = ArArchiveOptions.UPDATE_DEFAULTS
                .withCommon(updateCommon)
                .withMetadataDecoder(metadataDecoder);

        assertEquals(readCommon, read.common().withMetadataDecoder(null));
        assertSame(metadataDecoder, read.metadataDecoder());
        assertEquals(createCommon, create.common().withMetadataDecoder(null));
        assertSame(metadataDecoder, create.metadataDecoder());
        assertEquals(updateCommon, update.common().withMetadataDecoder(null));
        assertSame(metadataDecoder, update.metadataDecoder());
        assertSame(
                ArArchiveOptions.DEFAULT_METADATA_DECODER,
                ArArchiveOptions.CREATE_DEFAULTS.metadataDecoder()
        );
        assertSame(
                ArArchiveOptions.DEFAULT_METADATA_DECODER,
                ArArchiveOptions.UPDATE_DEFAULTS.metadataDecoder()
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
                () -> ArArchiveOptions.READ_DEFAULTS.withMetadataDecoder(null)
        );
        assertThrows(NullPointerException.class, () -> ArArchiveOptions.CREATE_DEFAULTS.withCommon(null));
        assertThrows(
                NullPointerException.class,
                () -> ArArchiveOptions.CREATE_DEFAULTS.withMetadataDecoder(null)
        );
        assertThrows(NullPointerException.class, () -> ArArchiveOptions.UPDATE_DEFAULTS.withCommon(null));
        assertThrows(
                NullPointerException.class,
                () -> ArArchiveOptions.UPDATE_DEFAULTS.withMetadataDecoder(null)
        );
    }
}
