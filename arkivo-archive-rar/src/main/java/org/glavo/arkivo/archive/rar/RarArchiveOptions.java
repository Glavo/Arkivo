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
///
/// Archive-wide limits in the common options are enforced during header parsing, decompressor setup, and logical body
/// decoding. Encrypted headers may require the password while opening or advancing; encrypted entry data requires it
/// when its body is opened. A missing, rejected, or incorrect password causes the affected operation to fail with
/// `IOException`. The legacy detector is used only for RAR4 metadata without an encoded Unicode value and falls back to
/// UTF-8 when it returns `null`.
@NotNullByDefault
public final class RarArchiveOptions {
    /// The default detector for legacy RAR4 names.
    public static final ArchiveMetadataCharsetDetector DEFAULT_LEGACY_CHARSET_DETECTOR =
            ArchiveMetadataCharsetDetector.fixed(StandardCharsets.UTF_8);

    /// The default read configuration.
    public static final Read READ_DEFAULTS = new Read(ArchiveReadOptions.DEFAULT);

    /// Creates no instances.
    private RarArchiveOptions() {
    }

    /// Configures reading RAR archives.
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
        /// @param value the format-independent read configuration
        /// @return a read configuration equal to this one except for `common`
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

        /// Returns the configured legacy charset detector or the RAR default.
        ///
        /// @return the effective detector for legacy non-Unicode names
        public ArchiveMetadataCharsetDetector legacyCharsetDetector() {
            @Nullable ArchiveMetadataCharsetDetector detector = common.metadataCharsetDetector();
            return detector != null ? detector : DEFAULT_LEGACY_CHARSET_DETECTOR;
        }

        /// Returns a copy with the password provider.
        ///
        /// @param value the password provider, or `null` to disable encrypted-archive password lookup
        /// @return a read configuration equal to this one except for `passwordProvider`
        public Read withPasswordProvider(@Nullable ArkivoPasswordProvider value) {
            return new Read(common.withPasswordProvider(value));
        }

        /// Returns a copy with the legacy charset detector.
        ///
        /// @param value the detector for legacy non-Unicode entry names
        /// @return a read configuration equal to this one except for `legacyCharsetDetector`
        /// @throws NullPointerException if `value` is `null`
        public Read withLegacyCharsetDetector(ArchiveMetadataCharsetDetector value) {
            return new Read(common.withMetadataCharsetDetector(Objects.requireNonNull(value, "value")));
        }
    }
}
