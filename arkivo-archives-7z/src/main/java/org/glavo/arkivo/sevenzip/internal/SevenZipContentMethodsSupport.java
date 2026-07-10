// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.glavo.arkivo.sevenzip.SevenZipCompression;
import org.glavo.arkivo.sevenzip.SevenZipFilter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Maps public 7z compression and preprocessing settings to Commons Compress writer configurations.
@NotNullByDefault
final class SevenZipContentMethodsSupport {
    /// Prevents instantiation.
    private SevenZipContentMethodsSupport() {
    }

    /// Sets the default filter and compression pipeline for all non-empty writer entries.
    static void applyTo(
            SevenZOutputFile writer,
            SevenZipCompression compression,
            @Nullable SevenZipFilter filter
    ) {
        Objects.requireNonNull(writer, "writer");
        if (filter == null) {
            writer.setContentMethods(List.of(configuration(compression)));
        } else {
            // SevenZOutputFile reverses this iterable before constructing the encoder chain.
            writer.setContentMethods(List.of(configuration(filter), configuration(compression)));
        }
    }

    /// Sets an entry-specific filter and compression pipeline.
    static void applyTo(
            SevenZArchiveEntry entry,
            SevenZipCompression compression,
            @Nullable SevenZipFilter filter
    ) {
        Objects.requireNonNull(entry, "entry");
        if (filter == null) {
            entry.setContentMethods(configuration(compression));
        } else {
            entry.setContentMethods(configuration(compression), configuration(filter));
        }
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

    /// Creates the Commons Compress representation of one public preprocessing filter.
    static SevenZMethodConfiguration configuration(SevenZipFilter filter) {
        Objects.requireNonNull(filter, "filter");
        SevenZMethod method = switch (filter.method()) {
            case DELTA -> SevenZMethod.DELTA_FILTER;
            case BCJ_X86 -> SevenZMethod.BCJ_X86_FILTER;
            case BCJ_PPC -> SevenZMethod.BCJ_PPC_FILTER;
            case BCJ_IA64 -> SevenZMethod.BCJ_IA64_FILTER;
            case BCJ_ARM -> SevenZMethod.BCJ_ARM_FILTER;
            case BCJ_ARM_THUMB -> SevenZMethod.BCJ_ARM_THUMB_FILTER;
            case BCJ_SPARC -> SevenZMethod.BCJ_SPARC_FILTER;
        };
        if (method == SevenZMethod.DELTA_FILTER) {
            return new SevenZMethodConfiguration(method, filter.parameter());
        }
        return new SevenZMethodConfiguration(method);
    }
}
