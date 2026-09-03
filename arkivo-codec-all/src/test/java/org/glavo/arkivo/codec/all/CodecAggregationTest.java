// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.bzip2.BZip2Format;
import org.glavo.arkivo.codec.compress.UnixCompressFormat;
import org.glavo.arkivo.codec.deflate.Deflate64Format;
import org.glavo.arkivo.codec.deflate.DeflateFormat;
import org.glavo.arkivo.codec.deflate.GzipFormat;
import org.glavo.arkivo.codec.deflate.ZlibFormat;
import org.glavo.arkivo.codec.lz4.LZ4BlockFormat;
import org.glavo.arkivo.codec.lz4.LZ4Format;
import org.glavo.arkivo.codec.lzip.LzipFormat;
import org.glavo.arkivo.codec.lzma.LZMA2Format;
import org.glavo.arkivo.codec.lzma.LZMAFormat;
import org.glavo.arkivo.codec.lzma.RawLZMAFormat;
import org.glavo.arkivo.codec.ppmd.PPMdFormat;
import org.glavo.arkivo.codec.xz.XZFormat;
import org.glavo.arkivo.codec.zstd.ZstdFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies runtime discovery and public descriptor metadata through the aggregate codecs module.
@NotNullByDefault
final class CodecAggregationTest {
    /// Expected official formats in their deterministic catalog order.
    private static final @Unmodifiable List<ExpectedFormat> EXPECTED_FORMATS = List.of(
            new ExpectedFormat(BZip2Format.instance(), List.of("bz2"), List.of("bz2", "bzip2"), 4),
            new ExpectedFormat(UnixCompressFormat.instance(), List.of("z", "unix-compress"), List.of("Z", "taz"), 3),
            new ExpectedFormat(DeflateFormat.instance(), List.of(), List.of("deflate"), 0),
            new ExpectedFormat(Deflate64Format.instance(), List.of("deflate-64"), List.of(), 0),
            new ExpectedFormat(GzipFormat.instance(), List.of(), List.of("gz", "gzip"), 2),
            new ExpectedFormat(ZlibFormat.instance(), List.of(), List.of("zlib"), 2),
            new ExpectedFormat(LZ4Format.instance(), List.of("lz4-frame"), List.of("lz4"), 4),
            new ExpectedFormat(LZ4BlockFormat.instance(), List.of("lz4-raw"), List.of(), 0),
            new ExpectedFormat(LzipFormat.instance(), List.of(), List.of("lz", "tlz"), 4),
            new ExpectedFormat(LZMAFormat.instance(), List.of(), List.of("lzma"), 0),
            new ExpectedFormat(RawLZMAFormat.instance(), List.of("raw-lzma"), List.of(), 0),
            new ExpectedFormat(LZMA2Format.instance(), List.of(), List.of("lzma2"), 0),
            new ExpectedFormat(PPMdFormat.instance(), List.of("ppmd7"), List.of(), 0),
            new ExpectedFormat(XZFormat.instance(), List.of(), List.of("xz"), 6),
            new ExpectedFormat(ZstdFormat.instance(), List.of(), List.of("zst", "zstd"), 4)
    );

    /// Verifies every aggregated compression format is visible in official order with exact public metadata.
    @Test
    void discoversAggregatedFormats() {
        List<CompressionFormat> expected = EXPECTED_FORMATS.stream()
                .map(ExpectedFormat::format)
                .toList();

        assertEquals(expected, CompressionFormats.installed());

        for (ExpectedFormat entry : EXPECTED_FORMATS) {
            CompressionFormat format = entry.format();
            assertEquals(entry.aliases(), format.aliases(), format.name());
            assertEquals(entry.fileExtensions(), format.fileExtensions(), format.name());
            assertEquals(entry.probeSize(), format.probeSize(), format.name());
            assertSame(format, format.defaultCodec().format(), format.name());
            assertSame(format.defaultCodec(), format.defaultCodec(), format.name());
            assertSame(format, CompressionFormats.require(format.name()), format.name());
            assertSame(
                    format,
                    CompressionFormats.require(format.name().toUpperCase(Locale.ROOT)),
                    format.name()
            );

            for (String alias : entry.aliases()) {
                assertSame(format, CompressionFormats.require(alias), alias);
                assertSame(format, CompressionFormats.require(alias.toUpperCase(Locale.ROOT)), alias);
            }
        }
    }

    /// Verifies descriptor collections are immutable as promised by the public API.
    @Test
    void exposesImmutableDescriptorCollections() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> CompressionFormats.installed().add(BZip2Format.instance())
        );

        for (CompressionFormat format : CompressionFormats.installed()) {
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> format.aliases().add("test-alias"),
                    format.name()
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> format.fileExtensions().add("test-extension"),
                    format.name()
            );
        }
    }

    /// Stores the exact public metadata expected for one official format descriptor.
    ///
    /// @param format canonical descriptor identity
    /// @param aliases stable lookup aliases
    /// @param fileExtensions common file extensions without leading dots
    /// @param probeSize preferred signature probe size in bytes
    private record ExpectedFormat(
            CompressionFormat format,
            @Unmodifiable List<String> aliases,
            @Unmodifiable List<String> fileExtensions,
            int probeSize
    ) {
        /// Creates an immutable expected-format value.
        private ExpectedFormat {
            aliases = List.copyOf(aliases);
            fileExtensions = List.copyOf(fileExtensions);
        }
    }
}
