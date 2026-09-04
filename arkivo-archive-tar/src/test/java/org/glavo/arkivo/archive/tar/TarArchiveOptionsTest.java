// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.deflate.DeflateCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies immutable TAR compression-policy and operation-option derivation.
@NotNullByDefault
final class TarArchiveOptionsTest {
    /// Verifies read and creation helpers select the requested outer-compression policies without losing common state.
    @Test
    void derivesReadAndCreationPolicies() {
        CompressionCodec<?> codec = DeflateCodec.DEFAULT;
        ArchiveMetadataCharsetDetector detector =
                ArchiveMetadataCharsetDetector.fixed(StandardCharsets.UTF_16LE);
        ArchiveReadOptions readCommon = ArchiveReadOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.CONCURRENT_READ);

        TarArchiveOptions.Read compressedRead = TarArchiveOptions.READ_DEFAULTS
                .withCommon(readCommon)
                .withCompression(codec)
                .withMetadataCharsetDetector(detector);
        assertSame(codec, assertInstanceOf(TarCompression.Codec.class, compressedRead.compression()).codec());
        assertSame(detector, compressedRead.metadataCharsetDetector());
        assertSame(TarCompression.DETECT, compressedRead.withCompressionDetection().compression());
        assertSame(TarCompression.UNCOMPRESSED, compressedRead.withoutCompression().compression());

        ArchiveCreateOptions createCommon = ArchiveCreateOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT);
        TarArchiveOptions.Create compressedCreate = TarArchiveOptions.CREATE_DEFAULTS
                .withCommon(createCommon)
                .withCompression(codec);
        assertSame(createCommon, compressedCreate.common());
        assertSame(codec, assertInstanceOf(TarCompression.Codec.class, compressedCreate.compression()).codec());
        assertSame(TarCompression.UNCOMPRESSED, compressedCreate.withoutCompression().compression());
    }

    /// Verifies update helpers independently select source, target, and metadata policies.
    @Test
    void derivesUpdatePolicies() {
        CompressionCodec<?> codec = DeflateCodec.DEFAULT;
        ArchiveMetadataCharsetDetector detector =
                ArchiveMetadataCharsetDetector.fixed(StandardCharsets.UTF_16BE);
        ArchiveUpdateOptions common = ArchiveUpdateOptions.DEFAULT
                .withThreadSafety(ArkivoFileSystemThreadSafety.STRICT);
        TarArchiveOptions.Update base = TarArchiveOptions.UPDATE_DEFAULTS.withCommon(common);

        TarArchiveOptions.Update sourceCodec = base.withSourceCompression(codec);
        assertSame(codec, assertInstanceOf(TarCompression.Codec.class, sourceCodec.sourceCompression()).codec());
        assertSame(TarCompression.DETECT, sourceCodec.withSourceCompressionDetection().sourceCompression());
        assertSame(TarCompression.UNCOMPRESSED, sourceCodec.withUncompressedSource().sourceCompression());

        TarArchiveOptions.Update targetCodec = base.withTargetCompression(codec);
        assertSame(codec, assertInstanceOf(TarCompression.Codec.class, targetCodec.targetCompression()).codec());
        assertSame(TarCompression.PRESERVE, targetCodec.withPreservedSourceCompression().targetCompression());
        assertSame(TarCompression.UNCOMPRESSED, targetCodec.withUncompressedTarget().targetCompression());

        TarArchiveOptions.Update named = base.withMetadataCharsetDetector(detector);
        assertSame(detector, named.metadataCharsetDetector());
        assertEquals(common, named.common().withMetadataCharsetDetector(null));
    }

    /// Verifies records and non-null policy helpers reject absent configuration values.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void rejectsNullConfigurationValues() {
        assertThrows(
                NullPointerException.class,
                () -> new TarArchiveOptions.Read(null, TarCompression.DETECT)
        );
        assertThrows(
                NullPointerException.class,
                () -> new TarArchiveOptions.Create(ArchiveCreateOptions.DEFAULT, null)
        );
        assertThrows(
                NullPointerException.class,
                () -> new TarArchiveOptions.Update(
                        ArchiveUpdateOptions.DEFAULT,
                        null,
                        TarCompression.PRESERVE
                )
        );
        assertThrows(NullPointerException.class, () -> TarArchiveOptions.READ_DEFAULTS.withCompression(null));
        assertThrows(
                NullPointerException.class,
                () -> TarArchiveOptions.READ_DEFAULTS.withMetadataCharsetDetector(null)
        );
        assertThrows(NullPointerException.class, () -> TarArchiveOptions.CREATE_DEFAULTS.withCommon(null));
        assertThrows(NullPointerException.class, () -> TarArchiveOptions.CREATE_DEFAULTS.withCompression(null));
        assertThrows(NullPointerException.class, () -> TarArchiveOptions.UPDATE_DEFAULTS.withSourceCompression(null));
        assertThrows(NullPointerException.class, () -> TarArchiveOptions.UPDATE_DEFAULTS.withTargetCompression(null));
        assertThrows(
                NullPointerException.class,
                () -> TarArchiveOptions.UPDATE_DEFAULTS.withMetadataCharsetDetector(null)
        );
    }
}
