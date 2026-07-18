// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Defines immutable 7z configuration for each archive operation lifecycle.
///
/// Read limits in the common options are enforced while headers and entry bodies are decoded. A read password is
/// requested only for encrypted material. For creation, a non-null password provider encrypts every non-empty entry;
/// `encryptHeaders` additionally hides header metadata and requires that provider. Output password bytes must be valid
/// UTF-16LE and a missing or invalid password makes writer or writable-file-system initialization fail with
/// `IOException`.
///
/// Update options use the provider both to read protected source content and to encrypt rewritten non-empty entries.
/// Compression, filters, and solid grouping apply to newly encoded folders rather than preserving source coder bytes.
@NotNullByDefault
public final class SevenZipArchiveOptions {
    /// The default number of non-empty files in one solid folder.
    public static final int DEFAULT_SOLID_FILE_COUNT = 1;

    /// The default read configuration.
    public static final Read READ_DEFAULTS = new Read(ArchiveReadOptions.DEFAULT, null);

    /// The default creation configuration.
    public static final Create CREATE_DEFAULTS = new Create(
            ArchiveCreateOptions.DEFAULT,
            null,
            SevenZipCompression.copy(),
            SevenZipFilterChain.EMPTY,
            DEFAULT_SOLID_FILE_COUNT,
            false
    );

    /// The default update configuration.
    public static final Update UPDATE_DEFAULTS = new Update(
            ArchiveUpdateOptions.DEFAULT,
            null,
            SevenZipCompression.copy(),
            SevenZipFilterChain.EMPTY,
            DEFAULT_SOLID_FILE_COUNT,
            false
    );

    /// Creates no instances.
    private SevenZipArchiveOptions() {
    }

    /// Configures reading 7z archives.
    ///
    /// @param common           the format-independent read configuration
    /// @param passwordProvider the password provider, or `null` when encrypted archives are not expected
    @NotNullByDefault
    public record Read(ArchiveReadOptions common, @Nullable ArkivoPasswordProvider passwordProvider) {
        /// Validates the read configuration.
        public Read {
            Objects.requireNonNull(common, "common");
        }

        /// Returns a copy with common read settings.
        ///
        /// @param value the replacement format-independent read configuration
        /// @return a copy with `common` replaced by `value`
        public Read withCommon(ArchiveReadOptions value) {
            return new Read(value, passwordProvider);
        }

        /// Returns a copy with the password provider.
        ///
        /// @param value the replacement provider, or `null` to disable password requests
        /// @return a copy with `passwordProvider` replaced by `value`
        public Read withPasswordProvider(@Nullable ArkivoPasswordProvider value) {
            return new Read(common, value);
        }
    }

    /// Configures creation of 7z archives.
    ///
    /// @param common           the format-independent creation configuration
    /// @param passwordProvider the password provider, or `null` when encryption is disabled
    /// @param compression      the compression applied to new folders
    /// @param filters          the filters applied before compression
    /// @param solidFileCount   the positive maximum number of non-empty files in one folder
    /// @param encryptHeaders   whether archive headers are encrypted
    @NotNullByDefault
    public record Create(
            ArchiveCreateOptions common,
            @Nullable ArkivoPasswordProvider passwordProvider,
            SevenZipCompression compression,
            SevenZipFilterChain filters,
            int solidFileCount,
            boolean encryptHeaders
    ) {
        /// Validates the creation configuration.
        public Create {
            Objects.requireNonNull(common, "common");
            Objects.requireNonNull(compression, "compression");
            Objects.requireNonNull(filters, "filters");
            if (solidFileCount <= 0) {
                throw new IllegalArgumentException("solidFileCount must be positive");
            }
        }

        /// Returns a copy with common creation settings.
        ///
        /// @param value the replacement format-independent creation configuration
        /// @return a copy with `common` replaced by `value`
        public Create withCommon(ArchiveCreateOptions value) {
            return new Create(value, passwordProvider, compression, filters, solidFileCount, encryptHeaders);
        }

        /// Returns a copy with the password provider.
        ///
        /// @param value the replacement provider, or `null` to disable content encryption
        /// @return a copy with `passwordProvider` replaced by `value`
        public Create withPasswordProvider(@Nullable ArkivoPasswordProvider value) {
            return new Create(common, value, compression, filters, solidFileCount, encryptHeaders);
        }

        /// Returns a copy with the compression configuration.
        ///
        /// @param value the compression for newly encoded folders
        /// @return a copy with `compression` replaced by `value`
        public Create withCompression(SevenZipCompression value) {
            return new Create(common, passwordProvider, value, filters, solidFileCount, encryptHeaders);
        }

        /// Returns a copy with the filter chain.
        ///
        /// @param value the preprocessing filters in execution order
        /// @return a copy with `filters` replaced by `value`
        public Create withFilters(SevenZipFilterChain value) {
            return new Create(common, passwordProvider, compression, value, solidFileCount, encryptHeaders);
        }

        /// Returns a copy with the maximum number of non-empty files in one solid folder.
        ///
        /// @param value the positive solid-folder file limit
        /// @return a copy with `solidFileCount` replaced by `value`
        /// @throws IllegalArgumentException if `value` is not positive
        public Create withSolidFileCount(int value) {
            return new Create(common, passwordProvider, compression, filters, value, encryptHeaders);
        }

        /// Returns a copy that enables or disables header encryption.
        ///
        /// @param value whether to encrypt archive header metadata
        /// @return a copy with `encryptHeaders` replaced by `value`
        public Create withEncryptHeaders(boolean value) {
            return new Create(common, passwordProvider, compression, filters, solidFileCount, value);
        }
    }

    /// Configures complete-rewrite updates of 7z archives.
    ///
    /// @param common           the format-independent update configuration
    /// @param passwordProvider the provider used to decrypt source data and encrypt rewritten non-empty entries, or
    /// `null` when encryption is not used
    /// @param compression      the compression applied to rewritten folders
    /// @param filters          the filters applied before compression
    /// @param solidFileCount   the positive maximum number of non-empty files in one folder
    /// @param encryptHeaders   whether archive headers are encrypted
    @NotNullByDefault
    public record Update(
            ArchiveUpdateOptions common,
            @Nullable ArkivoPasswordProvider passwordProvider,
            SevenZipCompression compression,
            SevenZipFilterChain filters,
            int solidFileCount,
            boolean encryptHeaders
    ) {
        /// Validates the update configuration.
        public Update {
            Objects.requireNonNull(common, "common");
            Objects.requireNonNull(compression, "compression");
            Objects.requireNonNull(filters, "filters");
            if (solidFileCount <= 0) {
                throw new IllegalArgumentException("solidFileCount must be positive");
            }
        }

        /// Returns a copy with common update settings.
        ///
        /// @param value the replacement format-independent update configuration
        /// @return a copy with `common` replaced by `value`
        public Update withCommon(ArchiveUpdateOptions value) {
            return new Update(value, passwordProvider, compression, filters, solidFileCount, encryptHeaders);
        }

        /// Returns a copy with the password provider.
        ///
        /// @param value the replacement provider, or `null` to disable decryption and output encryption
        /// @return a copy with `passwordProvider` replaced by `value`
        public Update withPasswordProvider(@Nullable ArkivoPasswordProvider value) {
            return new Update(common, value, compression, filters, solidFileCount, encryptHeaders);
        }

        /// Returns a copy with the compression configuration.
        ///
        /// @param value the compression for rewritten folders
        /// @return a copy with `compression` replaced by `value`
        public Update withCompression(SevenZipCompression value) {
            return new Update(common, passwordProvider, value, filters, solidFileCount, encryptHeaders);
        }

        /// Returns a copy with the filter chain.
        ///
        /// @param value the preprocessing filters in execution order
        /// @return a copy with `filters` replaced by `value`
        public Update withFilters(SevenZipFilterChain value) {
            return new Update(common, passwordProvider, compression, value, solidFileCount, encryptHeaders);
        }

        /// Returns a copy with the maximum number of non-empty files in one solid folder.
        ///
        /// @param value the positive solid-folder file limit
        /// @return a copy with `solidFileCount` replaced by `value`
        /// @throws IllegalArgumentException if `value` is not positive
        public Update withSolidFileCount(int value) {
            return new Update(common, passwordProvider, compression, filters, value, encryptHeaders);
        }

        /// Returns a copy that enables or disables header encryption.
        ///
        /// @param value whether to encrypt rewritten archive header metadata
        /// @return a copy with `encryptHeaders` replaced by `value`
        public Update withEncryptHeaders(boolean value) {
            return new Update(common, passwordProvider, compression, filters, solidFileCount, value);
        }
    }
}
