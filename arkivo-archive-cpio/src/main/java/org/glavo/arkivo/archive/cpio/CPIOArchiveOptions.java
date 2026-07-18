// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// Defines immutable CPIO configuration for streaming archive operations.
///
/// Read limits in the common options are enforced as headers and bodies advance. A metadata detector may reject a name
/// by throwing `IOException`; returning `null` selects the UTF-8 fallback. Creation stages regular-file bodies through
/// the common edit storage, which becomes owned by the writer. Header numeric ranges and encoded metadata are validated
/// when an entry is committed because their representability depends on the selected dialect.
@NotNullByDefault
public final class CPIOArchiveOptions {
    /// The traditional CPIO archive block size used for final stream padding.
    public static final int DEFAULT_BLOCK_SIZE = 512;

    /// The default detector for CPIO entry names.
    public static final ArchiveMetadataCharsetDetector DEFAULT_METADATA_CHARSET_DETECTOR =
            ArchiveMetadataCharsetDetector.fixed(StandardCharsets.UTF_8);

    /// The default CPIO metadata charset used for writing.
    public static final Charset DEFAULT_METADATA_CHARSET = StandardCharsets.UTF_8;

    /// The default CPIO read configuration.
    public static final Read READ_DEFAULTS = new Read(
            ArchiveReadOptions.DEFAULT,
            DEFAULT_METADATA_CHARSET_DETECTOR
    );

    /// The default CPIO creation configuration.
    public static final Create CREATE_DEFAULTS = new Create(
            ArchiveCreateOptions.DEFAULT,
            CPIODialect.NEW_ASCII,
            CPIOBinaryByteOrder.BIG_ENDIAN,
            DEFAULT_METADATA_CHARSET,
            DEFAULT_BLOCK_SIZE
    );

    /// Creates no instances.
    private CPIOArchiveOptions() {
    }

    /// Configures reading CPIO archives.
    ///
    /// @param common the format-independent read configuration
    /// @param metadataCharsetDetector the detector used to decode entry names
    @NotNullByDefault
    public record Read(
            ArchiveReadOptions common,
            ArchiveMetadataCharsetDetector metadataCharsetDetector
    ) {
        /// Validates the read configuration.
        public Read {
            Objects.requireNonNull(common, "common");
            Objects.requireNonNull(metadataCharsetDetector, "metadataCharsetDetector");
        }

        /// Returns a copy with common read settings.
        ///
        /// @param value the replacement format-independent read configuration
        /// @return an immutable read configuration containing `value`
        public Read withCommon(ArchiveReadOptions value) {
            return new Read(value, metadataCharsetDetector);
        }

        /// Returns a copy with the metadata charset detector.
        ///
        /// @param value the replacement detector for entry names
        /// @return an immutable read configuration containing `value`
        public Read withMetadataCharsetDetector(ArchiveMetadataCharsetDetector value) {
            return new Read(common, value);
        }
    }

    /// Configures creation of CPIO archives.
    ///
    /// @param common the format-independent creation configuration
    /// @param dialect the header representation to write
    /// @param binaryByteOrder the 16-bit word byte order used by `OLD_BINARY`
    /// @param metadataCharset the charset used to encode entry names and symbolic-link targets; encoded values must
    /// not contain a NUL byte
    /// @param blockSize the positive archive block size used for final padding
    @NotNullByDefault
    public record Create(
            ArchiveCreateOptions common,
            CPIODialect dialect,
            CPIOBinaryByteOrder binaryByteOrder,
            Charset metadataCharset,
            int blockSize
    ) {
        /// Validates the creation configuration.
        public Create {
            Objects.requireNonNull(common, "common");
            Objects.requireNonNull(dialect, "dialect");
            Objects.requireNonNull(binaryByteOrder, "binaryByteOrder");
            Objects.requireNonNull(metadataCharset, "metadataCharset");
            if (blockSize <= 0) {
                throw new IllegalArgumentException("blockSize must be positive");
            }
        }

        /// Returns a copy with common creation settings.
        ///
        /// @param value the replacement format-independent creation configuration
        /// @return an immutable creation configuration containing `value`
        public Create withCommon(ArchiveCreateOptions value) {
            return new Create(value, dialect, binaryByteOrder, metadataCharset, blockSize);
        }

        /// Returns a copy with the requested header dialect.
        ///
        /// @param value the header dialect to write
        /// @return an immutable creation configuration containing `value`
        public Create withDialect(CPIODialect value) {
            return new Create(common, value, binaryByteOrder, metadataCharset, blockSize);
        }

        /// Returns a copy with the old binary 16-bit word byte order.
        ///
        /// @param value the word byte order used when the selected dialect is `OLD_BINARY`
        /// @return an immutable creation configuration containing `value`
        public Create withBinaryByteOrder(CPIOBinaryByteOrder value) {
            return new Create(common, dialect, value, metadataCharset, blockSize);
        }

        /// Returns a copy with the metadata charset.
        ///
        /// @param value the charset used to encode entry names and symbolic-link targets
        /// @return an immutable creation configuration containing `value`
        public Create withMetadataCharset(Charset value) {
            return new Create(common, dialect, binaryByteOrder, value, blockSize);
        }

        /// Returns a copy with the archive block size.
        ///
        /// @param value the positive final-padding block size, in bytes
        /// @return an immutable creation configuration containing `value`
        /// @throws IllegalArgumentException if `value` is not positive
        public Create withBlockSize(int value) {
            return new Create(common, dialect, binaryByteOrder, metadataCharset, value);
        }
    }
}
