// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies immutable CPIO operation-option derivation and validation.
@NotNullByDefault
final class CPIOArchiveOptionsTest {
    /// Verifies read and creation derivation retains every independently configured value.
    @Test
    void derivesReadAndCreationConfiguration() {
        ArchiveMetadataCharsetDetector detector =
                ArchiveMetadataCharsetDetector.fixed(StandardCharsets.UTF_16LE);
        ArchiveReadOptions readCommon = ArchiveReadOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.CONCURRENT_READ);
        CPIOArchiveOptions.Read read = CPIOArchiveOptions.READ_DEFAULTS
                .withCommon(readCommon)
                .withMetadataCharsetDetector(detector);

        ArchiveCreateOptions createCommon = ArchiveCreateOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT);
        CPIOArchiveOptions.Create create = CPIOArchiveOptions.CREATE_DEFAULTS
                .withCommon(createCommon)
                .withDialect(CPIODialect.OLD_BINARY)
                .withBinaryByteOrder(CPIOBinaryByteOrder.LITTLE_ENDIAN)
                .withMetadataCharset(StandardCharsets.UTF_16BE)
                .withBlockSize(1024);

        assertEquals(readCommon, read.common().withMetadataCharsetDetector(null));
        assertSame(detector, read.metadataCharsetDetector());
        assertSame(createCommon, create.common());
        assertSame(CPIODialect.OLD_BINARY, create.dialect());
        assertSame(CPIOBinaryByteOrder.LITTLE_ENDIAN, create.binaryByteOrder());
        assertSame(StandardCharsets.UTF_16BE, create.metadataCharset());
        assertEquals(1024, create.blockSize());
    }

    /// Verifies creation records reject absent fields and non-positive final-padding sizes.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void rejectsInvalidCreationConfiguration() {
        assertThrows(
                NullPointerException.class,
                () -> new CPIOArchiveOptions.Create(
                        null,
                        CPIODialect.NEW_ASCII,
                        CPIOBinaryByteOrder.BIG_ENDIAN,
                        StandardCharsets.UTF_8,
                        1
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> CPIOArchiveOptions.CREATE_DEFAULTS.withDialect(null)
        );
        assertThrows(
                NullPointerException.class,
                () -> CPIOArchiveOptions.CREATE_DEFAULTS.withBinaryByteOrder(null)
        );
        assertThrows(
                NullPointerException.class,
                () -> CPIOArchiveOptions.CREATE_DEFAULTS.withMetadataCharset(null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CPIOArchiveOptions.CREATE_DEFAULTS.withBlockSize(0)
        );
    }
}
