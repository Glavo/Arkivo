// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Creates independently encoded payloads for ZIP optional-compression tests.
@NotNullByDefault
final class ZipCompressionTestFixtures {
    /// The LZMA SDK major version stored in ZIP LZMA property headers.
    private static final int LZMA_SDK_MAJOR_VERSION = 9;

    /// The LZMA SDK minor version stored in ZIP LZMA property headers.
    private static final int LZMA_SDK_MINOR_VERSION = 20;

    /// The ZIP LZMA property data size for raw LZMA streams.
    private static final int LZMA_PROPERTY_SIZE = 5;

    /// Creates no instances.
    private ZipCompressionTestFixtures() {
    }

    /// Returns compressed bytes for one optional ZIP compression method.
    static byte[] compress(ZipMethod method, byte @Unmodifiable [] content) throws IOException {
        return switch (method) {
            case BZIP2 -> bzip2(content);
            case DEFLATE64 -> deflate64StoredBlock(content);
            case DEPRECATED_ZSTANDARD, ZSTANDARD -> zstandard(content);
            case LZMA -> lzma(content);
            case XZ -> xz(content);
            default -> throw new IllegalArgumentException("Unsupported test compression method: " + method);
        };
    }

    /// Returns BZIP2-compressed bytes for a ZIP BZIP2 entry.
    static byte[] bzip2(byte @Unmodifiable [] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (BZip2CompressorOutputStream encoder = new BZip2CompressorOutputStream(output)) {
            encoder.write(content);
        }
        return output.toByteArray();
    }

    /// Returns Zstandard-compressed bytes for either ZIP Zstandard method identifier.
    static byte[] zstandard(byte @Unmodifiable [] content) throws IOException {
        ByteBuffer compressed = new ZstdCodec().compress(ByteBuffer.wrap(content));
        byte[] result = new byte[compressed.remaining()];
        compressed.get(result);
        return result;
    }

    /// Returns XZ-compressed bytes for a ZIP XZ entry.
    static byte[] xz(byte @Unmodifiable [] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (XZCompressorOutputStream encoder = new XZCompressorOutputStream(output)) {
            encoder.write(content);
        }
        return output.toByteArray();
    }

    /// Returns a ZIP LZMA property header followed by a raw LZMA stream.
    static byte[] lzma(byte @Unmodifiable [] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LZMA2Options options = new LZMA2Options();
        try (LZMAOutputStream encoder = new LZMAOutputStream(output, options, true)) {
            writeLzmaPropertyHeader(output, encoder.getProps(), options.getDictSize());
            encoder.write(content);
        }
        return output.toByteArray();
    }

    /// Returns one final raw Deflate64 stored-block payload.
    static byte[] deflate64StoredBlock(byte @Unmodifiable [] content) {
        if (content.length > 0xffff) {
            throw new IllegalArgumentException("content is too large for a single stored block");
        }
        ByteBuffer buffer = ByteBuffer.allocate(5 + content.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0x01);
        buffer.putShort((short) content.length);
        buffer.putShort((short) ~content.length);
        buffer.put(content);
        return buffer.array();
    }

    /// Writes the ZIP-specific LZMA property header.
    private static void writeLzmaPropertyHeader(
            OutputStream output,
            int properties,
            int dictionarySize
    ) throws IOException {
        output.write(LZMA_SDK_MAJOR_VERSION);
        output.write(LZMA_SDK_MINOR_VERSION);
        output.write(LZMA_PROPERTY_SIZE);
        output.write(0);
        output.write(properties & 0xff);
        output.write(dictionarySize & 0xff);
        output.write((dictionarySize >>> 8) & 0xff);
        output.write((dictionarySize >>> 16) & 0xff);
        output.write((dictionarySize >>> 24) & 0xff);
    }
}
