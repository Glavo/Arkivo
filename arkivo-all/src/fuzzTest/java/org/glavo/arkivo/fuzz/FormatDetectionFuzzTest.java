// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/// Fuzzes archive and compression signature detection through buffer and seekable-channel entry points.
@NotNullByDefault
public final class FormatDetectionFuzzTest {
    /// Creates a format-detection fuzz-test instance for JUnit.
    public FormatDetectionFuzzTest() {
    }

    /// Fuzzes detection while checking caller buffer and channel position preservation.
    ///
    /// @param data an arbitrary possible archive or compression prefix
    /// @throws IOException if an in-memory channel probe unexpectedly fails
    @MethodSource("detectionSeeds")
    @FuzzTest(maxDuration = "1m")
    void fuzzFormatDetection(byte @Unmodifiable [] data) throws IOException {
        if (data.length > FuzzSupport.MAX_PARSER_INPUT_SIZE) {
            return;
        }

        boolean direct = data.length > 0 && (data[data.length - 1] & 1) != 0;
        boolean readOnly = data.length > 0 && (data[data.length - 1] & 2) != 0;
        verifyBufferProbe(data, direct, readOnly, true);
        verifyBufferProbe(data, direct, readOnly, false);

        try (ReadOnlyByteArrayChannel channel = new ReadOnlyByteArrayChannel(data)) {
            long position = channel.position();
            CompressionFormats.detect(channel);
            if (channel.position() != position) {
                throw new AssertionError("Compression channel detection changed the source position");
            }
            ArkivoFormats.detect(channel);
            if (channel.position() != position) {
                throw new AssertionError("Archive channel detection changed the source position");
            }
        }
    }

    /// Runs one buffer detector and verifies that all caller-visible buffer state is preserved.
    ///
    /// @param data the possible signature bytes
    /// @param direct whether the backing buffer is direct
    /// @param readOnly whether the detector receives a read-only view
    /// @param compression whether to run compression rather than archive detection
    private static void verifyBufferProbe(
            byte @Unmodifiable [] data,
            boolean direct,
            boolean readOnly,
            boolean compression
    ) {
        ByteBuffer storage = direct
                ? ByteBuffer.allocateDirect(data.length + 4)
                : ByteBuffer.allocate(data.length + 4);
        storage.position(2);
        storage.put(data);
        storage.flip();
        storage.position(2);
        storage.order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer prefix = readOnly ? storage.asReadOnlyBuffer().order(storage.order()) : storage;
        int position = prefix.position();
        int limit = prefix.limit();
        ByteOrder order = prefix.order();

        if (compression) {
            CompressionFormats.detect(prefix);
        } else {
            ArkivoFormats.detect(prefix);
        }
        if (prefix.position() != position || prefix.limit() != limit || prefix.order() != order) {
            throw new AssertionError("Buffer detection changed caller-visible buffer state");
        }
    }

    /// Supplies valid encodings and archives so mutation starts inside every signature family.
    ///
    /// @return deterministic detection seed arguments
    /// @throws IOException if a seed cannot be encoded
    private static Stream<Arguments> detectionSeeds() throws IOException {
        List<Arguments> seeds = new ArrayList<>();
        for (CompressionFormat format : FuzzSupport.COMPRESSION_FORMATS) {
            CompressionCodec<?> codec = FuzzSupport.boundedCodec(
                    format.defaultCodec(),
                    FuzzSupport.SEED_CONTENT.length
            );
            seeds.add(Arguments.of((Object) FuzzSupport.remainingBytes(
                    codec.compress(ByteBuffer.wrap(FuzzSupport.SEED_CONTENT))
            )));
        }
        for (String formatName : List.of("7z", "ar", "cpio", "rar", "tar", "zip")) {
            seeds.add(Arguments.of((Object) FuzzSupport.createArchiveSeed(formatName)));
        }
        return seeds.stream();
    }
}
