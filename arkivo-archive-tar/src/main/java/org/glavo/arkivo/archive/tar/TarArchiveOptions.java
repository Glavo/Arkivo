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
/// Compression policy is explicit: reads detect by default, creation writes an uncompressed TAR by default, and
/// updates preserve the detected source wrapper by default. The metadata detector handles legacy header text and PAX
/// binary values and falls back to UTF-8 when it returns `null`.
/// A seekable-capable compression configuration writes its default indexed representation, allowing a later TAR file
/// system to read contiguous entry bodies without expanding the complete compressed stream.
@NotNullByDefault
public final class TarArchiveOptions {
    /// The default detector for TAR header text.
    public static final ArchiveMetadataCharsetDetector DEFAULT_METADATA_CHARSET_DETECTOR =
            ArchiveMetadataCharsetDetector.fixed(StandardCharsets.UTF_8);

    /// The default read configuration.
    public static final Read READ_DEFAULTS = new Read(ArchiveReadOptions.DEFAULT, TarCompression.DETECT);

    /// The default creation configuration.
    public static final Create CREATE_DEFAULTS = new Create(
            ArchiveCreateOptions.DEFAULT,
            TarCompression.UNCOMPRESSED
    );

    /// The default update configuration.
    public static final Update UPDATE_DEFAULTS = new Update(
            ArchiveUpdateOptions.DEFAULT,
            TarCompression.DETECT,
            TarCompression.PRESERVE
    );

    /// Creates no instances.
    private TarArchiveOptions() {
    }

    /// Configures reading TAR archives.
    ///
    /// @param common                  the format-independent read configuration
    /// @param compression             the explicit read-time outer-compression policy
    @NotNullByDefault
    public record Read(
            ArchiveReadOptions common,
            TarCompression.Read compression
    ) {
        /// Validates the read configuration.
        public Read {
            Objects.requireNonNull(common, "common");
            Objects.requireNonNull(compression, "compression");
        }

        /// Returns a copy with common read settings.
        ///
        /// @param value the replacement format-independent read configuration
        /// @return a new read configuration retaining this compression and metadata detector
        public Read withCommon(ArchiveReadOptions value) {
            return new Read(value, compression);
        }

        /// Returns a copy with an explicitly configured outer compression codec.
        ///
        /// @param value the immutable outer compression codec
        /// @return a new read configuration retaining this common configuration and metadata detector
        public Read withCompression(CompressionCodec<?> value) {
            return new Read(common, TarCompression.using(value));
        }

        /// Returns a copy that detects a signed outer compression format.
        ///
        /// @return a new read configuration using automatic detection
        public Read withCompressionDetection() {
            return new Read(common, TarCompression.DETECT);
        }

        /// Returns a copy that treats the source as a plain TAR stream.
        ///
        /// @return a new read configuration that performs no outer decompression
        public Read withoutCompression() {
            return new Read(common, TarCompression.UNCOMPRESSED);
        }

        /// Returns the configured metadata charset detector or the TAR default.
        ///
        /// @return the effective detector for legacy header text
        public ArchiveMetadataCharsetDetector metadataCharsetDetector() {
            @Nullable ArchiveMetadataCharsetDetector detector = common.metadataCharsetDetector();
            return detector != null ? detector : DEFAULT_METADATA_CHARSET_DETECTOR;
        }

        /// Returns a copy with the metadata charset detector.
        ///
        /// @param value the detector used for ambiguous legacy TAR metadata bytes
        /// @return a new read configuration retaining this common configuration and compression selection
        public Read withMetadataCharsetDetector(ArchiveMetadataCharsetDetector value) {
            return new Read(common.withMetadataCharsetDetector(Objects.requireNonNull(value, "value")), compression);
        }
    }

    /// Configures creation of TAR archives.
    ///
    /// @param common      the format-independent creation configuration
    /// @param compression the explicit creation-time outer-compression policy
    @NotNullByDefault
    public record Create(ArchiveCreateOptions common, TarCompression.Create compression) {
        /// Validates the creation configuration.
        public Create {
            Objects.requireNonNull(common, "common");
            Objects.requireNonNull(compression, "compression");
        }

        /// Returns a copy with common creation settings.
        ///
        /// @param value the replacement format-independent creation configuration
        /// @return a new creation configuration retaining this compression selection
        public Create withCommon(ArchiveCreateOptions value) {
            return new Create(value, compression);
        }

        /// Returns a copy with an explicitly configured outer compression codec.
        ///
        /// @param value the immutable outer compression codec
        /// @return a new creation configuration retaining this common configuration
        public Create withCompression(CompressionCodec<?> value) {
            return new Create(common, TarCompression.using(value));
        }

        /// Returns a copy that writes a plain TAR stream.
        ///
        /// @return a new creation configuration that performs no outer compression
        public Create withoutCompression() {
            return new Create(common, TarCompression.UNCOMPRESSED);
        }
    }

    /// Configures complete-rewrite updates of TAR archives.
    ///
    /// @param common            the format-independent update configuration
    /// @param sourceCompression the policy used to decode the existing archive
    /// @param targetCompression the policy used to encode the replacement archive
    @NotNullByDefault
    public record Update(
            ArchiveUpdateOptions common,
            TarCompression.Read sourceCompression,
            TarCompression.Update targetCompression
    ) {
        /// Validates the update configuration.
        public Update {
            Objects.requireNonNull(common, "common");
            Objects.requireNonNull(sourceCompression, "sourceCompression");
            Objects.requireNonNull(targetCompression, "targetCompression");
        }

        /// Returns a copy with common update settings.
        ///
        /// @param value the replacement format-independent complete-rewrite configuration
        /// @return a new update configuration retaining this compression choice and metadata detector
        public Update withCommon(ArchiveUpdateOptions value) {
            return new Update(value, sourceCompression, targetCompression);
        }

        /// Returns a copy with an explicitly configured source compression codec.
        ///
        /// @param value the immutable codec used to decode the existing archive
        /// @return a new update configuration retaining the target compression
        public Update withSourceCompression(CompressionCodec<?> value) {
            return new Update(common, TarCompression.using(value), targetCompression);
        }

        /// Returns a copy that detects the source compression.
        ///
        /// @return a new update configuration using automatic source detection
        public Update withSourceCompressionDetection() {
            return new Update(common, TarCompression.DETECT, targetCompression);
        }

        /// Returns a copy that treats the source as a plain TAR stream.
        ///
        /// @return a new update configuration that performs no source decompression
        public Update withUncompressedSource() {
            return new Update(common, TarCompression.UNCOMPRESSED, targetCompression);
        }

        /// Returns a copy with an explicitly configured target compression codec.
        ///
        /// @param value the immutable codec used to encode the replacement archive
        /// @return a new update configuration retaining the source decoding policy
        public Update withTargetCompression(CompressionCodec<?> value) {
            return new Update(common, sourceCompression, TarCompression.using(value));
        }

        /// Returns a copy that preserves the resolved source compression on output.
        ///
        /// @return a new update configuration preserving the source wrapper
        public Update withPreservedSourceCompression() {
            return new Update(common, sourceCompression, TarCompression.PRESERVE);
        }

        /// Returns a copy that publishes a plain TAR stream.
        ///
        /// @return a new update configuration that performs no target compression
        public Update withUncompressedTarget() {
            return new Update(common, sourceCompression, TarCompression.UNCOMPRESSED);
        }

        /// Returns the configured metadata charset detector or the TAR default.
        ///
        /// @return the effective detector for legacy source header text
        public ArchiveMetadataCharsetDetector metadataCharsetDetector() {
            @Nullable ArchiveMetadataCharsetDetector detector = common.metadataCharsetDetector();
            return detector != null ? detector : DEFAULT_METADATA_CHARSET_DETECTOR;
        }

        /// Returns a copy with the metadata charset detector.
        ///
        /// @param value the detector used for ambiguous legacy source metadata bytes
        /// @return a new update configuration retaining this common configuration and compression choice
        public Update withMetadataCharsetDetector(ArchiveMetadataCharsetDetector value) {
            return new Update(
                    common.withMetadataCharsetDetector(Objects.requireNonNull(value, "value")),
                    sourceCompression,
                    targetCompression
            );
        }
    }
}
