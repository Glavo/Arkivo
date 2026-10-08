// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// Defines immutable AR configuration for each archive operation lifecycle.
///
/// The decoder is invoked for member names without an authoritative charset and may throw `IOException` to reject
/// decoding. The default decoder strictly decodes UTF-8. The common read and update options supply archive-wide
/// resource limits; the common create and update options select staging storage owned by the returned file system.
@NotNullByDefault
public final class ArArchiveOptions {
    /// The default decoder for AR member names.
    public static final ArchiveMetadataDecoder DEFAULT_METADATA_DECODER =
            ArchiveMetadataDecoder.forCharset(StandardCharsets.UTF_8);

    /// The default read configuration.
    public static final Read READ_DEFAULTS = new Read(ArchiveReadOptions.DEFAULT);

    /// The default creation configuration.
    public static final Create CREATE_DEFAULTS = new Create(ArchiveCreateOptions.DEFAULT);

    /// The default update configuration.
    public static final Update UPDATE_DEFAULTS = new Update(ArchiveUpdateOptions.DEFAULT);

    /// Creates no instances.
    private ArArchiveOptions() {
    }

    /// Configures reading AR archives.
    ///
    /// @param common the format-independent read configuration
    @NotNullByDefault
    public record Read(ArchiveReadOptions common) {
        /// Validates the read configuration.
        public Read {
            Objects.requireNonNull(common, "common");
        }

        /// Returns a copy with common read settings.
        ///
        /// @param value the replacement format-independent read configuration
        /// @return an immutable read configuration containing `value`
        public Read withCommon(ArchiveReadOptions value) {
            return new Read(value);
        }

        /// Returns the configured metadata decoder or the AR default.
        ///
        /// @return the effective decoder for member names
        public ArchiveMetadataDecoder metadataDecoder() {
            @Nullable ArchiveMetadataDecoder metadataDecoder = common.metadataDecoder();
            return metadataDecoder != null ? metadataDecoder : DEFAULT_METADATA_DECODER;
        }

        /// Returns a copy with the metadata decoder.
        ///
        /// @param value the replacement decoder for member names
        /// @return an immutable read configuration containing `value`
        public Read withMetadataDecoder(ArchiveMetadataDecoder value) {
            return new Read(common.withMetadataDecoder(Objects.requireNonNull(value, "value")));
        }
    }

    /// Configures creation of AR archives.
    ///
    /// @param common the format-independent creation configuration
    @NotNullByDefault
    public record Create(ArchiveCreateOptions common) {
        /// Validates the creation configuration.
        public Create {
            Objects.requireNonNull(common, "common");
        }

        /// Returns a copy with common creation settings.
        ///
        /// @param value the replacement format-independent creation configuration
        /// @return an immutable creation configuration containing `value`
        public Create withCommon(ArchiveCreateOptions value) {
            return new Create(value);
        }

        /// Returns the configured metadata decoder or the AR default.
        ///
        /// @return the effective decoder used by file-system metadata views
        public ArchiveMetadataDecoder metadataDecoder() {
            @Nullable ArchiveMetadataDecoder metadataDecoder = common.metadataDecoder();
            return metadataDecoder != null ? metadataDecoder : DEFAULT_METADATA_DECODER;
        }

        /// Returns a copy with the metadata decoder.
        ///
        /// @param value the replacement decoder used by file-system metadata views
        /// @return an immutable creation configuration containing `value`
        public Create withMetadataDecoder(ArchiveMetadataDecoder value) {
            return new Create(common.withMetadataDecoder(Objects.requireNonNull(value, "value")));
        }
    }

    /// Configures complete-rewrite updates of AR archives.
    ///
    /// @param common the format-independent update configuration
    @NotNullByDefault
    public record Update(ArchiveUpdateOptions common) {
        /// Validates the update configuration.
        public Update {
            Objects.requireNonNull(common, "common");
        }

        /// Returns a copy with common update settings.
        ///
        /// @param value the replacement format-independent update configuration
        /// @return an immutable update configuration containing `value`
        public Update withCommon(ArchiveUpdateOptions value) {
            return new Update(value);
        }

        /// Returns the configured metadata decoder or the AR default.
        ///
        /// @return the effective decoder for source member names
        public ArchiveMetadataDecoder metadataDecoder() {
            @Nullable ArchiveMetadataDecoder metadataDecoder = common.metadataDecoder();
            return metadataDecoder != null ? metadataDecoder : DEFAULT_METADATA_DECODER;
        }

        /// Returns a copy with the metadata decoder.
        ///
        /// @param value the replacement decoder for source member names
        /// @return an immutable update configuration containing `value`
        public Update withMetadataDecoder(ArchiveMetadataDecoder value) {
            return new Update(common.withMetadataDecoder(Objects.requireNonNull(value, "value")));
        }
    }
}
