// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;

/// Identifies a compression method supported by 7z output.
@NotNullByDefault
public enum SevenZipCompressionMethod {
    /// Stores entry data without compression.
    COPY("copy"),

    /// Compresses each non-empty entry with legacy LZMA.
    LZMA("lzma"),

    /// Compresses each non-empty entry with LZMA2.
    LZMA2("lzma2"),

    /// Compresses each non-empty entry with BZIP2.
    BZIP2("bzip2"),

    /// Compresses each non-empty entry with raw DEFLATE.
    DEFLATE("deflate");

    /// The stable environment and display name.
    private final String optionName;

    /// Creates a compression method with its stable option name.
    SevenZipCompressionMethod(String optionName) {
        this.optionName = optionName;
    }

    /// Parses a stable case-insensitive compression method name.
    public static SevenZipCompressionMethod parse(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (SevenZipCompressionMethod method : values()) {
            if (method.optionName.equals(normalized)) {
                return method;
            }
        }
        throw new IllegalArgumentException("Unknown 7z compression method: " + value);
    }

    /// Returns the stable environment and display name.
    public String optionName() {
        return optionName;
    }

    /// Returns the stable display name.
    @Override
    public String toString() {
        return optionName;
    }
}
