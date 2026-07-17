// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// Defines immutable configuration for reading RAR archives.
@NotNullByDefault
public final class RarArchiveOptions {
    /// The default detector for legacy RAR4 names.
    public static final ArchiveMetadataCharsetDetector DEFAULT_LEGACY_CHARSET_DETECTOR =
            ArchiveMetadataCharsetDetector.fixed(StandardCharsets.UTF_8);

    /// The default read configuration.
    public static final Read READ_DEFAULTS = new Read(
            ArchiveReadOptions.DEFAULT,
            null,
            DEFAULT_LEGACY_CHARSET_DETECTOR
    );

    /// Creates no instances.
    private RarArchiveOptions() {
    }

    /// Configures reading RAR archives.
    ///
    /// @param common                the format-independent read configuration
    /// @param passwordProvider      the password provider, or `null` when encrypted archives are not expected
    /// @param legacyCharsetDetector the detector for legacy non-Unicode names
    @NotNullByDefault
    public record Read(
            ArchiveReadOptions common,
            @Nullable ArkivoPasswordProvider passwordProvider,
            ArchiveMetadataCharsetDetector legacyCharsetDetector
    ) {
        /// Validates the read configuration.
        public Read {
            Objects.requireNonNull(common, "common");
            Objects.requireNonNull(legacyCharsetDetector, "legacyCharsetDetector");
        }

        /// Returns a copy with common read settings.
        public Read withCommon(ArchiveReadOptions value) {
            return new Read(value, passwordProvider, legacyCharsetDetector);
        }

        /// Returns a copy with the password provider.
        public Read withPasswordProvider(@Nullable ArkivoPasswordProvider value) {
            return new Read(common, value, legacyCharsetDetector);
        }

        /// Returns a copy with the legacy charset detector.
        public Read withLegacyCharsetDetector(ArchiveMetadataCharsetDetector value) {
            return new Read(common, passwordProvider, value);
        }
    }
}
