// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.Charset;
import java.util.Objects;

/// Defines immutable ZIP configuration for each archive operation lifecycle.
@NotNullByDefault
public final class ZipArchiveOptions {
    /// The ZIP default for legacy names without an explicit Unicode representation.
    public static final ArchiveMetadataCharsetDetector DEFAULT_LEGACY_CHARSET_DETECTOR =
            ArchiveMetadataCharsetDetector.fixed(Charset.forName("IBM437"));

    /// The default read configuration.
    public static final Read READ_DEFAULTS = new Read(
            ArchiveReadOptions.DEFAULT,
            null,
            DEFAULT_LEGACY_CHARSET_DETECTOR
    );

    /// The default creation configuration.
    public static final Create CREATE_DEFAULTS = new Create(
            ArchiveCreateOptions.DEFAULT,
            null,
            ZipEncryption.none()
    );

    /// The default update configuration.
    public static final Update UPDATE_DEFAULTS = new Update(
            ArchiveUpdateOptions.DEFAULT,
            null,
            ZipEncryption.none(),
            DEFAULT_LEGACY_CHARSET_DETECTOR
    );

    /// Creates no instances.
    private ZipArchiveOptions() {
    }

    /// Configures reading ZIP archives.
    ///
    /// @param common                the format-independent read configuration
    /// @param passwordProvider      the password provider, or `null` when encrypted entries are not expected
    /// @param legacyCharsetDetector the detector for non-Unicode names and comments
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

    /// Configures creation of ZIP archives.
    ///
    /// @param common            the format-independent creation configuration
    /// @param passwordProvider  the password provider used for encrypted entries, or `null` when encryption is disabled
    /// @param defaultEncryption the encryption used by new entries without an override
    @NotNullByDefault
    public record Create(
            ArchiveCreateOptions common,
            @Nullable ArkivoPasswordProvider passwordProvider,
            ZipEncryption defaultEncryption
    ) {
        /// Validates the creation configuration.
        public Create {
            Objects.requireNonNull(common, "common");
            Objects.requireNonNull(defaultEncryption, "defaultEncryption");
        }

        /// Returns a copy with common creation settings.
        public Create withCommon(ArchiveCreateOptions value) {
            return new Create(value, passwordProvider, defaultEncryption);
        }

        /// Returns a copy with the password provider.
        public Create withPasswordProvider(@Nullable ArkivoPasswordProvider value) {
            return new Create(common, value, defaultEncryption);
        }

        /// Returns a copy with default encryption.
        public Create withDefaultEncryption(ZipEncryption value) {
            return new Create(common, passwordProvider, value);
        }
    }

    /// Configures complete-rewrite updates of ZIP archives.
    ///
    /// @param common                the format-independent update configuration
    /// @param passwordProvider      the password provider, or `null` when encryption is not used
    /// @param defaultEncryption     the encryption used by new entries without an override
    /// @param legacyCharsetDetector the detector for non-Unicode names and comments
    @NotNullByDefault
    public record Update(
            ArchiveUpdateOptions common,
            @Nullable ArkivoPasswordProvider passwordProvider,
            ZipEncryption defaultEncryption,
            ArchiveMetadataCharsetDetector legacyCharsetDetector
    ) {
        /// Validates the update configuration.
        public Update {
            Objects.requireNonNull(common, "common");
            Objects.requireNonNull(defaultEncryption, "defaultEncryption");
            Objects.requireNonNull(legacyCharsetDetector, "legacyCharsetDetector");
        }

        /// Returns a copy with common update settings.
        public Update withCommon(ArchiveUpdateOptions value) {
            return new Update(value, passwordProvider, defaultEncryption, legacyCharsetDetector);
        }

        /// Returns a copy with the password provider.
        public Update withPasswordProvider(@Nullable ArkivoPasswordProvider value) {
            return new Update(common, value, defaultEncryption, legacyCharsetDetector);
        }

        /// Returns a copy with default encryption.
        public Update withDefaultEncryption(ZipEncryption value) {
            return new Update(common, passwordProvider, value, legacyCharsetDetector);
        }

        /// Returns a copy with the legacy charset detector.
        public Update withLegacyCharsetDetector(ArchiveMetadataCharsetDetector value) {
            return new Update(common, passwordProvider, defaultEncryption, value);
        }
    }
}
