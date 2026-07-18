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
///
/// Read limits in the common options are enforced while central-directory or local-header metadata and entry bodies
/// are decoded. A password is requested only when encrypted entry data is accessed. For output, selecting an encryption
/// other than [ZipEncryption#NONE] requires a provider when that entry is committed; a missing or rejected password
/// fails the write with `IOException`. The update default applies to new and replaced entries, while unchanged local
/// records retain their existing bytes.
///
/// The legacy detector is consulted only after the UTF-8 flag and valid Info-ZIP Unicode extra fields. Returning `null`
/// selects CP437.
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
            ZipEncryption.NONE
    );

    /// The default update configuration.
    public static final Update UPDATE_DEFAULTS = new Update(
            ArchiveUpdateOptions.DEFAULT,
            null,
            ZipEncryption.NONE,
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
        ///
        /// @param value the replacement format-independent read configuration
        /// @return a new configuration with the replacement common settings and all other values unchanged
        /// @throws NullPointerException if `value` is `null`
        public Read withCommon(ArchiveReadOptions value) {
            return new Read(value, passwordProvider, legacyCharsetDetector);
        }

        /// Returns a copy with the password provider.
        ///
        /// @param value the replacement password provider, or `null` to disable password lookup
        /// @return a new configuration with the replacement password provider and all other values unchanged
        public Read withPasswordProvider(@Nullable ArkivoPasswordProvider value) {
            return new Read(common, value, legacyCharsetDetector);
        }

        /// Returns a copy with the legacy charset detector.
        ///
        /// @param value the replacement detector for non-Unicode names and comments
        /// @return a new configuration with the replacement detector and all other values unchanged
        /// @throws NullPointerException if `value` is `null`
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
        ///
        /// @param value the replacement format-independent creation configuration
        /// @return a new configuration with the replacement common settings and all other values unchanged
        /// @throws NullPointerException if `value` is `null`
        public Create withCommon(ArchiveCreateOptions value) {
            return new Create(value, passwordProvider, defaultEncryption);
        }

        /// Returns a copy with the password provider.
        ///
        /// @param value the replacement password provider, or `null` to disable password lookup
        /// @return a new configuration with the replacement password provider and all other values unchanged
        public Create withPasswordProvider(@Nullable ArkivoPasswordProvider value) {
            return new Create(common, value, defaultEncryption);
        }

        /// Returns a copy with default encryption.
        ///
        /// @param value the replacement encryption for entries without an explicit override
        /// @return a new configuration with the replacement default encryption and all other values unchanged
        /// @throws NullPointerException if `value` is `null`
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
        ///
        /// @param value the replacement format-independent update configuration
        /// @return a new configuration with the replacement common settings and all other values unchanged
        /// @throws NullPointerException if `value` is `null`
        public Update withCommon(ArchiveUpdateOptions value) {
            return new Update(value, passwordProvider, defaultEncryption, legacyCharsetDetector);
        }

        /// Returns a copy with the password provider.
        ///
        /// @param value the replacement password provider, or `null` to disable password lookup
        /// @return a new configuration with the replacement password provider and all other values unchanged
        public Update withPasswordProvider(@Nullable ArkivoPasswordProvider value) {
            return new Update(common, value, defaultEncryption, legacyCharsetDetector);
        }

        /// Returns a copy with default encryption.
        ///
        /// @param value the replacement encryption for new entries without an explicit override
        /// @return a new configuration with the replacement default encryption and all other values unchanged
        /// @throws NullPointerException if `value` is `null`
        public Update withDefaultEncryption(ZipEncryption value) {
            return new Update(common, passwordProvider, value, legacyCharsetDetector);
        }

        /// Returns a copy with the legacy charset detector.
        ///
        /// @param value the replacement detector for non-Unicode names and comments
        /// @return a new configuration with the replacement detector and all other values unchanged
        /// @throws NullPointerException if `value` is `null`
        public Update withLegacyCharsetDetector(ArchiveMetadataCharsetDetector value) {
            return new Update(common, passwordProvider, defaultEncryption, value);
        }
    }
}
