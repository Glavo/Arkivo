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
    public static final Read READ_DEFAULTS = new Read(ArchiveReadOptions.DEFAULT);

    /// The default creation configuration.
    public static final Create CREATE_DEFAULTS = new Create(
            ArchiveCreateOptions.DEFAULT,
            ZipEncryption.NONE
    );

    /// The default update configuration.
    public static final Update UPDATE_DEFAULTS = new Update(
            ArchiveUpdateOptions.DEFAULT,
            ZipEncryption.NONE
    );

    /// Creates no instances.
    private ZipArchiveOptions() {
    }

    /// Configures reading ZIP archives.
    ///
    /// Password and metadata-charset services are obtained from `common`. A missing detector selects
    /// [#DEFAULT_LEGACY_CHARSET_DETECTOR].
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
        /// @return a new configuration with the replacement common settings and all other values unchanged
        /// @throws NullPointerException if `value` is `null`
        public Read withCommon(ArchiveReadOptions value) {
            return new Read(value);
        }

        /// Returns the password provider inherited from the common options.
        ///
        /// @return the provider, or `null` when password lookup is disabled
        public @Nullable ArkivoPasswordProvider passwordProvider() {
            return common.passwordProvider();
        }

        /// Returns the configured legacy charset detector or the ZIP default.
        ///
        /// @return the effective detector for non-Unicode names and comments
        public ArchiveMetadataCharsetDetector legacyCharsetDetector() {
            @Nullable ArchiveMetadataCharsetDetector detector = common.metadataCharsetDetector();
            return detector != null ? detector : DEFAULT_LEGACY_CHARSET_DETECTOR;
        }

        /// Returns a copy with the password provider.
        ///
        /// @param value the replacement password provider, or `null` to disable password lookup
        /// @return a new configuration with the replacement password provider and all other values unchanged
        public Read withPasswordProvider(@Nullable ArkivoPasswordProvider value) {
            return new Read(common.withPasswordProvider(value));
        }

        /// Returns a copy with the legacy charset detector.
        ///
        /// @param value the replacement detector for non-Unicode names and comments
        /// @return a new configuration with the replacement detector and all other values unchanged
        /// @throws NullPointerException if `value` is `null`
        public Read withLegacyCharsetDetector(ArchiveMetadataCharsetDetector value) {
            return new Read(common.withMetadataCharsetDetector(Objects.requireNonNull(value, "value")));
        }
    }

    /// Configures creation of ZIP archives.
    ///
    /// @param common            the format-independent creation configuration
    /// @param defaultEncryption the encryption used by new entries without an override
    @NotNullByDefault
    public record Create(
            ArchiveCreateOptions common,
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
            return new Create(value, defaultEncryption);
        }

        /// Returns the password provider inherited from the common options.
        ///
        /// @return the provider, or `null` when password lookup is disabled
        public @Nullable ArkivoPasswordProvider passwordProvider() {
            return common.passwordProvider();
        }

        /// Returns a copy with the password provider.
        ///
        /// @param value the replacement password provider, or `null` to disable password lookup
        /// @return a new configuration with the replacement password provider and all other values unchanged
        public Create withPasswordProvider(@Nullable ArkivoPasswordProvider value) {
            return new Create(common.withPasswordProvider(value), defaultEncryption);
        }

        /// Returns a copy with default encryption.
        ///
        /// @param value the replacement encryption for entries without an explicit override
        /// @return a new configuration with the replacement default encryption and all other values unchanged
        /// @throws NullPointerException if `value` is `null`
        public Create withDefaultEncryption(ZipEncryption value) {
            return new Create(common, value);
        }
    }

    /// Configures complete-rewrite updates of ZIP archives.
    ///
    /// @param common                the format-independent update configuration
    /// @param defaultEncryption     the encryption used by new entries without an override
    @NotNullByDefault
    public record Update(
            ArchiveUpdateOptions common,
            ZipEncryption defaultEncryption
    ) {
        /// Validates the update configuration.
        public Update {
            Objects.requireNonNull(common, "common");
            Objects.requireNonNull(defaultEncryption, "defaultEncryption");
        }

        /// Returns a copy with common update settings.
        ///
        /// @param value the replacement format-independent update configuration
        /// @return a new configuration with the replacement common settings and all other values unchanged
        /// @throws NullPointerException if `value` is `null`
        public Update withCommon(ArchiveUpdateOptions value) {
            return new Update(value, defaultEncryption);
        }

        /// Returns the password provider inherited from the common options.
        ///
        /// @return the provider, or `null` when password lookup is disabled
        public @Nullable ArkivoPasswordProvider passwordProvider() {
            return common.passwordProvider();
        }

        /// Returns the configured legacy charset detector or the ZIP default.
        ///
        /// @return the effective detector for non-Unicode names and comments
        public ArchiveMetadataCharsetDetector legacyCharsetDetector() {
            @Nullable ArchiveMetadataCharsetDetector detector = common.metadataCharsetDetector();
            return detector != null ? detector : DEFAULT_LEGACY_CHARSET_DETECTOR;
        }

        /// Returns a copy with the password provider.
        ///
        /// @param value the replacement password provider, or `null` to disable password lookup
        /// @return a new configuration with the replacement password provider and all other values unchanged
        public Update withPasswordProvider(@Nullable ArkivoPasswordProvider value) {
            return new Update(common.withPasswordProvider(value), defaultEncryption);
        }

        /// Returns a copy with default encryption.
        ///
        /// @param value the replacement encryption for new entries without an explicit override
        /// @return a new configuration with the replacement default encryption and all other values unchanged
        /// @throws NullPointerException if `value` is `null`
        public Update withDefaultEncryption(ZipEncryption value) {
            return new Update(common, value);
        }

        /// Returns a copy with the legacy charset detector.
        ///
        /// @param value the replacement detector for non-Unicode names and comments
        /// @return a new configuration with the replacement detector and all other values unchanged
        /// @throws NullPointerException if `value` is `null`
        public Update withLegacyCharsetDetector(ArchiveMetadataCharsetDetector value) {
            return new Update(
                    common.withMetadataCharsetDetector(Objects.requireNonNull(value, "value")), defaultEncryption
            );
        }
    }
}
