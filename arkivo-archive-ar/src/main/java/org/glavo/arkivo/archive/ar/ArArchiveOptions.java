// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// Defines immutable AR configuration for each archive operation lifecycle.
@NotNullByDefault
public final class ArArchiveOptions {
    /// The default detector for AR member names.
    public static final ArchiveMetadataCharsetDetector DEFAULT_METADATA_CHARSET_DETECTOR =
            ArchiveMetadataCharsetDetector.fixed(StandardCharsets.UTF_8);

    /// The default read configuration.
    public static final Read READ_DEFAULTS = new Read(
            ArchiveReadOptions.DEFAULT,
            DEFAULT_METADATA_CHARSET_DETECTOR
    );

    /// The default creation configuration.
    public static final Create CREATE_DEFAULTS = new Create(
            ArchiveCreateOptions.DEFAULT,
            DEFAULT_METADATA_CHARSET_DETECTOR
    );

    /// The default update configuration.
    public static final Update UPDATE_DEFAULTS = new Update(
            ArchiveUpdateOptions.DEFAULT,
            DEFAULT_METADATA_CHARSET_DETECTOR
    );

    /// Creates no instances.
    private ArArchiveOptions() {
    }

    /// Configures reading AR archives.
    ///
    /// @param common                  the format-independent read configuration
    /// @param metadataCharsetDetector the detector for member names
    @NotNullByDefault
    public record Read(ArchiveReadOptions common, ArchiveMetadataCharsetDetector metadataCharsetDetector) {
        /// Validates the read configuration.
        public Read {
            Objects.requireNonNull(common, "common");
            Objects.requireNonNull(metadataCharsetDetector, "metadataCharsetDetector");
        }

        /// Returns a copy with common read settings.
        public Read withCommon(ArchiveReadOptions value) {
            return new Read(value, metadataCharsetDetector);
        }

        /// Returns a copy with the metadata charset detector.
        public Read withMetadataCharsetDetector(ArchiveMetadataCharsetDetector value) {
            return new Read(common, value);
        }
    }

    /// Configures creation of AR archives.
    ///
    /// @param common                  the format-independent creation configuration
    /// @param metadataCharsetDetector the detector used by file-system metadata views
    @NotNullByDefault
    public record Create(ArchiveCreateOptions common, ArchiveMetadataCharsetDetector metadataCharsetDetector) {
        /// Validates the creation configuration.
        public Create {
            Objects.requireNonNull(common, "common");
            Objects.requireNonNull(metadataCharsetDetector, "metadataCharsetDetector");
        }

        /// Returns a copy with common creation settings.
        public Create withCommon(ArchiveCreateOptions value) {
            return new Create(value, metadataCharsetDetector);
        }

        /// Returns a copy with the metadata charset detector.
        public Create withMetadataCharsetDetector(ArchiveMetadataCharsetDetector value) {
            return new Create(common, value);
        }
    }

    /// Configures complete-rewrite updates of AR archives.
    ///
    /// @param common                  the format-independent update configuration
    /// @param metadataCharsetDetector the detector for source member names
    @NotNullByDefault
    public record Update(ArchiveUpdateOptions common, ArchiveMetadataCharsetDetector metadataCharsetDetector) {
        /// Validates the update configuration.
        public Update {
            Objects.requireNonNull(common, "common");
            Objects.requireNonNull(metadataCharsetDetector, "metadataCharsetDetector");
        }

        /// Returns a copy with common update settings.
        public Update withCommon(ArchiveUpdateOptions value) {
            return new Update(value, metadataCharsetDetector);
        }

        /// Returns a copy with the metadata charset detector.
        public Update withMetadataCharsetDetector(ArchiveMetadataCharsetDetector value) {
            return new Update(common, value);
        }
    }
}
