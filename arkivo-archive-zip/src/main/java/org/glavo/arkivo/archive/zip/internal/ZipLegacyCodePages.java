// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.zip.ZipLegacyMetadataDecoder;
import org.glavo.arkivo.encoding.detector.internal.EncodingDetector;
import org.jetbrains.annotations.NotNullByDefault;

/// Classifies legacy OEM/ANSI conventions without guessing a language or consulting the system locale.
@NotNullByDefault
public final class ZipLegacyCodePages {
    /// Prevents instantiation.
    private ZipLegacyCodePages() {
    }

    /// Applies Info-ZIP's Ext_ASCII_TO_Native creator and attribute rules.
    /// Unknown creator metadata supplies no automatic preference. The extraction version is not a creator version.
    ///
    /// @param versionMadeBy unsigned creator version, or the decoder's unknown sentinel
    /// @param attributes unsigned external attributes, or the decoder's unknown sentinel
    /// @param source header supplying the attributes
    /// @return a code-page family hint, or NONE when the producer does not supply one
    public static EncodingDetector.Preference preference(int versionMadeBy, long attributes,
                                                         ZipLegacyMetadataDecoder.HeaderSource source) {
        if (versionMadeBy == ZipLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE) return EncodingDetector.Preference.NONE;
        int host = versionMadeBy >>> 8;
        if (host != 0 && host != 6 && host != 10 && host != 11) return EncodingDetector.Preference.NONE;
        return isOem(versionMadeBy, attributes, source) ? EncodingDetector.Preference.OEM : EncodingDetector.Preference.ANSI;
    }

    /// Selects Info-ZIP's conversion branch; its non-OEM branch also includes Unix locale encodings.
    private static boolean isOem(int versionMadeBy, long attributes, ZipLegacyMetadataDecoder.HeaderSource source) {
        int version = versionMadeBy & 255;
        return switch (versionMadeBy >>> 8) {
            case 0 -> !((version == 25 || version == 26 || version == 40)
                    && (source == ZipLegacyMetadataDecoder.HeaderSource.LOCAL_FILE_HEADER
                    || attributes != ZipLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE && (attributes & 0xffff_0000L) != 0));
            case 6 -> true;
            // Historical producers also used 11 for NTFS.
            case 10, 11 -> version == 50;
            default -> false;
        };
    }

    /// Selects the explicit compatibility policy's OEM branch, including its unknown-creator default.
    ///
    /// @param context available producer and header metadata
    /// @return whether the configured OEM code page applies
    public static boolean usesOem(ZipLegacyMetadataDecoder.Context context) {
        return context.versionMadeBy() == ZipLegacyMetadataDecoder.UNKNOWN_HEADER_VALUE
                || isOem(context.versionMadeBy(), context.externalAttributes(), context.headerSource());
    }
}
