// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.glavo.arkivo.sevenzip.SevenZipCompression;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.Objects;

/// Maps public 7z compression settings to Commons Compress writer configurations.
@NotNullByDefault
final class SevenZipCompressionSupport {
    /// Prevents instantiation.
    private SevenZipCompressionSupport() {
    }

    /// Sets the default compression for all non-empty writer entries.
    static void applyTo(SevenZOutputFile writer, SevenZipCompression compression) {
        Objects.requireNonNull(writer, "writer");
        writer.setContentMethods(List.of(configuration(compression)));
    }

    /// Sets an entry-specific compression override.
    static void applyTo(SevenZArchiveEntry entry, SevenZipCompression compression) {
        Objects.requireNonNull(entry, "entry");
        entry.setContentMethods(configuration(compression));
    }

    /// Creates the Commons Compress representation of one public compression setting.
    static SevenZMethodConfiguration configuration(SevenZipCompression compression) {
        Objects.requireNonNull(compression, "compression");
        SevenZMethod method = switch (compression.method()) {
            case COPY -> SevenZMethod.COPY;
            case LZMA -> SevenZMethod.LZMA;
            case LZMA2 -> SevenZMethod.LZMA2;
            case BZIP2 -> SevenZMethod.BZIP2;
            case DEFLATE -> SevenZMethod.DEFLATE;
        };
        if (method == SevenZMethod.COPY) {
            return new SevenZMethodConfiguration(method);
        }
        return new SevenZMethodConfiguration(method, compression.parameter());
    }
}
