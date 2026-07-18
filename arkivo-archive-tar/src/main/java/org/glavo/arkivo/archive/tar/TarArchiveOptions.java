// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.codec.CompressionCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// Defines immutable TAR configuration for each archive operation lifecycle.
///
/// A `null` read compression requests detection by seekable file systems and streaming-reader factories. A `null`
/// create compression writes an uncompressed TAR stream; a `null` update compression preserves the source codec
/// resolved when the archive was opened. The metadata detector handles legacy header text and PAX
/// binary values and falls back to UTF-8 when it returns `null`.
@NotNullByDefault
public final class TarArchiveOptions {
    /// The default detector for TAR header text.
    public static final ArchiveMetadataCharsetDetector DEFAULT_METADATA_CHARSET_DETECTOR =
            ArchiveMetadataCharsetDetector.fixed(StandardCharsets.UTF_8);

    /// The default read configuration.
    public static final Read READ_DEFAULTS = new Read(
            ArchiveReadOptions.DEFAULT,
            null,
            DEFAULT_METADATA_CHARSET_DETECTOR
    );

    /// The default creation configuration.
    public static final Create CREATE_DEFAULTS = new Create(ArchiveCreateOptions.DEFAULT, null);

    /// The default update configuration.
    public static final Update UPDATE_DEFAULTS = new Update(
            ArchiveUpdateOptions.DEFAULT,
            null,
            DEFAULT_METADATA_CHARSET_DETECTOR
    );

    /// Creates no instances.
    private TarArchiveOptions() {
    }

    /// Configures reading TAR archives.
    ///
    /// @param common                  the format-independent read configuration
    /// @param compression             the explicitly selected outer compression, or `null` for automatic detection
    /// @param metadataCharsetDetector the detector for legacy header text
    @NotNullByDefault
    public record Read(
            ArchiveReadOptions common,
            @Nullable CompressionCodec<?> compression,
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
        /// @return a new read configuration retaining this compression and metadata detector
        public Read withCommon(ArchiveReadOptions value) {
            return new Read(value, compression, metadataCharsetDetector);
        }

        /// Returns a copy with the outer compression, or automatic detection when `null`.
        ///
        /// @param value the explicitly selected outer compression, or `null` to detect it from the seekable source
        /// @return a new read configuration retaining this common configuration and metadata detector
        public Read withCompression(@Nullable CompressionCodec<?> value) {
            return new Read(common, value, metadataCharsetDetector);
        }

        /// Returns a copy with the metadata charset detector.
        ///
        /// @param value the detector used for ambiguous legacy TAR metadata bytes
        /// @return a new read configuration retaining this common configuration and compression selection
        public Read withMetadataCharsetDetector(ArchiveMetadataCharsetDetector value) {
            return new Read(common, compression, value);
        }
    }

    /// Configures creation of TAR archives.
    ///
    /// @param common      the format-independent creation configuration
    /// @param compression the outer compression, or `null` for an uncompressed TAR stream
    @NotNullByDefault
    public record Create(ArchiveCreateOptions common, @Nullable CompressionCodec<?> compression) {
        /// Validates the creation configuration.
        public Create {
            Objects.requireNonNull(common, "common");
        }

        /// Returns a copy with common creation settings.
        ///
        /// @param value the replacement format-independent creation configuration
        /// @return a new creation configuration retaining this compression selection
        public Create withCommon(ArchiveCreateOptions value) {
            return new Create(value, compression);
        }

        /// Returns a copy with the outer compression, or no compression when `null`.
        ///
        /// @param value the outer compression codec, or `null` for an uncompressed TAR stream
        /// @return a new creation configuration retaining this common configuration
        public Create withCompression(@Nullable CompressionCodec<?> value) {
            return new Create(common, value);
        }
    }

    /// Configures complete-rewrite updates of TAR archives.
    ///
    /// @param common                  the format-independent update configuration
    /// @param compression             the outer compression, or `null` to preserve the detected source compression
    /// @param metadataCharsetDetector the detector for legacy source header text
    @NotNullByDefault
    public record Update(
            ArchiveUpdateOptions common,
            @Nullable CompressionCodec<?> compression,
            ArchiveMetadataCharsetDetector metadataCharsetDetector
    ) {
        /// Validates the update configuration.
        public Update {
            Objects.requireNonNull(common, "common");
            Objects.requireNonNull(metadataCharsetDetector, "metadataCharsetDetector");
        }

        /// Returns a copy with common update settings.
        ///
        /// @param value the replacement format-independent complete-rewrite configuration
        /// @return a new update configuration retaining this compression choice and metadata detector
        public Update withCommon(ArchiveUpdateOptions value) {
            return new Update(value, compression, metadataCharsetDetector);
        }

        /// Returns a copy with the outer compression, or source-compression preservation when `null`.
        ///
        /// @param value the replacement outer compression, or `null` to preserve the detected source codec
        /// @return a new update configuration retaining this common configuration and metadata detector
        public Update withCompression(@Nullable CompressionCodec<?> value) {
            return new Update(common, value, metadataCharsetDetector);
        }

        /// Returns a copy with the metadata charset detector.
        ///
        /// @param value the detector used for ambiguous legacy source metadata bytes
        /// @return a new update configuration retaining this common configuration and compression choice
        public Update withMetadataCharsetDetector(ArchiveMetadataCharsetDetector value) {
            return new Update(common, compression, value);
        }
    }
}
