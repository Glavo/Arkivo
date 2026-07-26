// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.fuzz;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.lz4.LZ4BlockCodec;
import org.glavo.arkivo.codec.lzma.RawLZMACodec;
import org.glavo.arkivo.codec.ppmd.PPMdCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/// Provides bounded configurations and deterministic seeds shared by local Jazzer targets.
@NotNullByDefault
final class FuzzSupport {
    /// The largest compressed or archive payload accepted by a parser target.
    static final int MAX_PARSER_INPUT_SIZE = 256 * 1024;

    /// The largest uncompressed payload accepted by a round-trip target.
    static final int MAX_ROUND_TRIP_INPUT_SIZE = 4 * 1024;

    /// The maximum decoded bytes produced by one fuzz invocation.
    static final int MAX_DECODED_OUTPUT_SIZE = 256 * 1024;

    /// The maximum history window made available to one decoder.
    private static final long MAXIMUM_CODEC_WINDOW_SIZE = 16L * 1024L * 1024L;

    /// The maximum codec-accounted memory made available to one decoder.
    private static final long MAXIMUM_CODEC_MEMORY_SIZE = 32L * 1024L * 1024L;

    /// The fixed body used to build valid compression and archive seeds.
    static final byte @Unmodifiable [] SEED_CONTENT =
            "Arkivo Jazzer seed\n".getBytes(StandardCharsets.UTF_8);

    /// Installed compression formats in deterministic catalog order.
    static final @Unmodifiable List<CompressionFormat> COMPRESSION_FORMATS =
            CompressionFormats.installed();

    /// Formats exercised through their forward-only reader.
    static final @Unmodifiable List<String> STREAMING_ARCHIVE_FORMATS =
            List.of("ar", "cpio", "rar", "tar", "zip");

    /// Formats exercised through their random-access file system.
    static final @Unmodifiable List<String> FILE_SYSTEM_ARCHIVE_FORMATS =
            List.of("7z", "ar", "rar", "tar", "zip");

    /// Resource limits applied to every archive parser invocation.
    static final ArchiveReadOptions ARCHIVE_READ_OPTIONS = ArchiveReadOptions.DEFAULT.withLimits(
            ArchiveReadLimits.builder()
                    .maximumEntryCount(64L)
                    .maximumEntrySize(MAX_DECODED_OUTPUT_SIZE)
                    .maximumTotalEntrySize(MAX_DECODED_OUTPUT_SIZE)
                    .maximumMetadataSize(MAX_DECODED_OUTPUT_SIZE)
                    .maximumCompressionWindowSize(MAXIMUM_CODEC_WINDOW_SIZE)
                    .maximumDecoderMemorySize(MAXIMUM_CODEC_MEMORY_SIZE)
                    .maximumDecodedArchiveSize(MAX_DECODED_OUTPUT_SIZE)
                    .maximumOuterCompressionLayers(2L)
                    .build()
    );

    /// The valid empty RAR5 signature used to enter the read-only RAR implementation.
    private static final byte @Unmodifiable [] EMPTY_RAR5 =
            {'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00};

    /// Prevents instantiation.
    private FuzzSupport() {
    }

    /// Returns the installed compression format selected by an arbitrary integer.
    ///
    /// @param selector the arbitrary selector
    /// @return the selected installed format
    static CompressionFormat compressionFormat(int selector) {
        return COMPRESSION_FORMATS.get(Math.floorMod(selector, COMPRESSION_FORMATS.size()));
    }

    /// Applies finite decoder limits and decoded-size metadata required by raw formats.
    ///
    /// @param codec the base codec
    /// @param decodedSize the expected nonnegative decoded size
    /// @return a bounded immutable codec suitable for one fuzz invocation
    static CompressionCodec<?> boundedCodec(CompressionCodec<?> codec, long decodedSize) {
        CompressionCodec<?> configured = codec
                .withMaximumOutputSize(MAX_DECODED_OUTPUT_SIZE)
                .withMaximumWindowSize(MAXIMUM_CODEC_WINDOW_SIZE)
                .withMaximumMemorySize(MAXIMUM_CODEC_MEMORY_SIZE);
        if (configured instanceof LZ4BlockCodec blockCodec) {
            configured = blockCodec.withMaximumBlockSize(MAX_DECODED_OUTPUT_SIZE);
        }
        if (configured instanceof RawLZMACodec rawLzmaCodec) {
            configured = rawLzmaCodec.withDecodedSize(decodedSize);
        }
        if (configured instanceof PPMdCodec ppmdCodec) {
            configured = ppmdCodec.withDecodedSize(decodedSize);
        }
        return configured;
    }

    /// Copies the remaining bytes of a buffer without changing its position.
    ///
    /// @param buffer the source buffer
    /// @return a new byte array containing the remaining bytes
    static byte @Unmodifiable [] remainingBytes(ByteBuffer buffer) {
        ByteBuffer view = buffer.duplicate();
        byte[] result = new byte[view.remaining()];
        view.get(result);
        return result;
    }

    /// Prefixes a payload with fuzz-control bytes.
    ///
    /// @param controls the leading control bytes
    /// @param payload the payload to append
    /// @return a new combined byte array
    static byte @Unmodifiable [] prefix(byte @Unmodifiable [] controls, byte @Unmodifiable [] payload) {
        byte[] result = new byte[controls.length + payload.length];
        System.arraycopy(controls, 0, result, 0, controls.length);
        System.arraycopy(payload, 0, result, controls.length, payload.length);
        return result;
    }

    /// Creates a small valid archive for the named supported format.
    ///
    /// RAR is read-only and therefore uses a valid empty RAR5 signature. Other formats are generated through Arkivo's
    /// public streaming writer so their seeds track the current encoder implementation.
    ///
    /// @param formatName the installed archive format name
    /// @return a complete valid archive
    /// @throws IOException if the archive cannot be encoded
    static byte @Unmodifiable [] createArchiveSeed(String formatName) throws IOException {
        if ("rar".equals(formatName)) {
            return EMPTY_RAR5.clone();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter(formatName, output)) {
            ArkivoStreamingWriter.Entry entry = writer.beginFile("seed.txt");
            try (OutputStream body = entry.openOutputStream()) {
                body.write(SEED_CONTENT);
            }
        }
        return output.toByteArray();
    }

    /// Compresses an archive seed with the named outer compression format.
    ///
    /// @param formatName the installed compression format name
    /// @param archive the decoded archive bytes
    /// @return the complete compressed encoding
    /// @throws IOException if the encoding cannot be produced
    static byte @Unmodifiable [] compressArchive(
            String formatName,
            byte @Unmodifiable [] archive
    ) throws IOException {
        CompressionCodec<?> codec = CompressionFormats.require(formatName).defaultCodec();
        return remainingBytes(codec.compress(ByteBuffer.wrap(archive)));
    }
}
