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
import java.nio.InvalidMarkException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/// Fuzzes archive and compression signature detection through buffer and seekable-channel entry points.
@NotNullByDefault
public final class FormatDetectionFuzzTest {
    /// Sentinel retained outside every visible buffer and channel input range.
    private static final byte INPUT_GUARD = (byte) 0x9d;

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

        int maximumReadSize = data.length == 0
                ? 1
                : 1 + Byte.toUnsignedInt(data[0]) % 32;
        verifyChannelProbes(data, maximumReadSize);
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
                ? ByteBuffer.allocateDirect(data.length + 9)
                : ByteBuffer.allocate(data.length + 9);
        for (int index = 0; index < storage.capacity(); index++) {
            storage.put(index, INPUT_GUARD);
        }
        storage.position(4);
        storage.put(data);
        storage.limit(4 + data.length);
        storage.position(4);
        storage.order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer prefix = readOnly ? storage.asReadOnlyBuffer().order(storage.order()) : storage;
        prefix.mark();
        int position = prefix.position();
        int limit = prefix.limit();
        ByteOrder order = prefix.order();
        boolean originallyReadOnly = prefix.isReadOnly();
        byte[] originalStorage = snapshot(storage);

        if (compression) {
            CompressionFormats.detect(prefix);
        } else {
            ArkivoFormats.detect(prefix);
        }
        if (prefix.position() != position
                || prefix.limit() != limit
                || prefix.order() != order
                || prefix.isReadOnly() != originallyReadOnly) {
            throw new AssertionError("Buffer detection changed caller-visible buffer state");
        }
        try {
            prefix.reset();
        } catch (InvalidMarkException exception) {
            throw new AssertionError("Buffer detection discarded the caller mark", exception);
        }
        if (prefix.position() != position) {
            throw new AssertionError("Buffer detection changed the caller mark");
        }
        if (!Arrays.equals(originalStorage, snapshot(storage))) {
            throw new AssertionError("Buffer detection changed the caller storage");
        }
    }

    /// Runs both seekable detectors from a fragmented nonzero logical source origin.
    ///
    /// @param data the possible signature bytes
    /// @param maximumReadSize the positive maximum bytes returned by one channel read
    private static void verifyChannelProbes(
            byte @Unmodifiable [] data,
            int maximumReadSize
    ) throws IOException {
        int sourceOffset = 3;
        byte[] embedded = new byte[sourceOffset + data.length];
        Arrays.fill(embedded, 0, sourceOffset, INPUT_GUARD);
        System.arraycopy(data, 0, embedded, sourceOffset, data.length);

        try (ReadOnlyByteArrayChannel channel =
                     new ReadOnlyByteArrayChannel(embedded, maximumReadSize)) {
            channel.position(sourceOffset);
            CompressionFormats.detect(channel);
            if (channel.position() != sourceOffset) {
                throw new AssertionError("Compression channel detection changed the source position");
            }
            ArkivoFormats.detect(channel);
            if (channel.position() != sourceOffset) {
                throw new AssertionError("Archive channel detection changed the source position");
            }
        }
    }

    /// Returns a complete byte snapshot of the supplied storage without changing its state.
    private static byte @Unmodifiable [] snapshot(ByteBuffer storage) {
        ByteBuffer view = storage.duplicate();
        view.clear();
        byte[] result = new byte[view.remaining()];
        view.get(result);
        return result;
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
        for (String formatName : List.of("7z", "ar", "cpio", "dmg", "rar", "tar", "zip")) {
            seeds.add(Arguments.of((Object) FuzzSupport.createArchiveSeed(formatName)));
        }
        return seeds.stream();
    }
}
