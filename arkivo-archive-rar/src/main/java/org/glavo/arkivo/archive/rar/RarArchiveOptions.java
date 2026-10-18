// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
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
/// `IOException`. The legacy decoder is used only for RAR4 entry names without an encoded Unicode value. The default
/// decoder strictly decodes UTF-8; a custom decoder supplies the final text without an implicit charset fallback.
///
/// The decoder-memory limit for RAR5 covers the byte payload of retained dictionaries, Huffman and repetition
/// arrays, compressed-input and decoded-output buffers, queued filter data, and filter transformation copies.
/// It excludes objects and their headers, reference arrays, shared tables, reader adapters, encryption buffers, and memory awaiting
/// garbage collection. For RAR 1.5 and 2.x, the limit covers dictionaries, adaptive symbol and audio state,
/// Huffman tables and their construction buffers, and compressed-input and decoded-output buffers.
/// For RAR 3.x and 4.x, it also covers PPMd model arrays, filter descriptors, VM memory, retained filter data,
/// and intermediate transformation results. Replacing an array counts both the old and new payloads until the
/// replacement is installed; resetting a model does not reduce the charge for retained capacity.
///
/// @param common the format-independent read configuration
@NotNullByDefault
public record RarArchiveOptions(ArchiveReadOptions common) {
    /// The default decoder for legacy RAR4 names.
    public static final ArchiveMetadataDecoder DEFAULT_LEGACY_METADATA_DECODER =
            ArchiveMetadataDecoder.forCharset(StandardCharsets.UTF_8);

    /// The default read configuration.
    public static final RarArchiveOptions DEFAULT = new RarArchiveOptions(ArchiveReadOptions.DEFAULT);

    /// Validates the read configuration.
    public RarArchiveOptions {
        Objects.requireNonNull(common, "common");
    }

    /// Returns a copy with common read settings.
    ///
    /// @param value the format-independent read configuration
    /// @return a read configuration equal to this one except for `common`
    /// @throws NullPointerException if `value` is `null`
    public RarArchiveOptions withCommon(ArchiveReadOptions value) {
        return new RarArchiveOptions(value);
    }

    /// Returns the password provider inherited from the common options.
    ///
    /// @return the provider, or `null` when password lookup is disabled
    public @Nullable ArkivoPasswordProvider passwordProvider() {
        return common.passwordProvider();
    }

    /// Returns the configured legacy decoder or the RAR default.
    ///
    /// @return the effective decoder for legacy non-Unicode names
    public ArchiveMetadataDecoder legacyMetadataDecoder() {
        @Nullable ArchiveMetadataDecoder metadataDecoder = common.metadataDecoder();
        return metadataDecoder != null ? metadataDecoder : DEFAULT_LEGACY_METADATA_DECODER;
    }

    /// Returns a copy with the password provider.
    ///
    /// @param value the password provider, or `null` to disable encrypted-archive password lookup
    /// @return a read configuration equal to this one except for `passwordProvider`
    public RarArchiveOptions withPasswordProvider(@Nullable ArkivoPasswordProvider value) {
        return new RarArchiveOptions(common.withPasswordProvider(value));
    }

    /// Returns a copy with the legacy decoder.
    ///
    /// @param value the decoder for legacy non-Unicode entry names
    /// @return a read configuration equal to this one except for `legacyMetadataDecoder`
    /// @throws NullPointerException if `value` is `null`
    public RarArchiveOptions withLegacyMetadataDecoder(ArchiveMetadataDecoder value) {
        return new RarArchiveOptions(common.withMetadataDecoder(Objects.requireNonNull(value, "value")));
    }
}
