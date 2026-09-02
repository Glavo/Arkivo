// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.RawCompressionDictionary;
import org.glavo.arkivo.codec.deflate.DeflateCodec;
import org.glavo.arkivo.codec.deflate.DeflateStrategy;
import org.glavo.arkivo.codec.deflate.GzipCodec;
import org.glavo.arkivo.codec.deflate.ZlibCodec;
import org.glavo.arkivo.codec.deflate.ZlibDictionary;
import org.glavo.arkivo.codec.lz4.LZ4BlockSize;
import org.glavo.arkivo.codec.lz4.LZ4Codec;
import org.glavo.arkivo.codec.lz4.LZ4Dictionary;
import org.glavo.arkivo.codec.lzip.LzipCodec;
import org.glavo.arkivo.codec.lzma.LZMA2Codec;
import org.glavo.arkivo.codec.lzma.LZMACodec;
import org.glavo.arkivo.codec.lzma.LZMAProperties;
import org.glavo.arkivo.codec.lzma.RawLZMACodec;
import org.glavo.arkivo.codec.ppmd.PPMdCodec;
import org.glavo.arkivo.codec.xz.XZCheckType;
import org.glavo.arkivo.codec.xz.XZCodec;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.glavo.arkivo.codec.zstd.ZstdDictionary;
import org.glavo.arkivo.codec.zstd.ZstdFrameFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/// Fuzzes valid non-default codec configurations through complete buffer round trips.
@NotNullByDefault
public final class CompressionConfigurationFuzzTest {
    /// The number of configuration and buffer-control bytes preceding the source payload.
    private static final int HEADER_SIZE = 4;

    /// The fixed minimum dictionary content generated for dictionary-capable formats.
    private static final int GENERATED_DICTIONARY_SIZE = 256;

    /// Creates a compression-configuration fuzz-test instance for JUnit.
    public CompressionConfigurationFuzzTest() {
    }

    /// Configures the selected format, round-trips arbitrary content, and checks complete buffer consumption.
    ///
    /// The target covers generic compression-level extremes together with format-specific dictionaries, checksums,
    /// framing, block sizing, LZMA properties, and PPMd model settings. Every generated configuration is valid by
    /// construction, so checked failures are treated as defects rather than malformed-input outcomes.
    ///
    /// @param data format, configuration, and buffer controls followed by arbitrary source bytes
    /// @throws IOException if a valid generated configuration cannot complete its round trip
    @MethodSource("configurationSeeds")
    @FuzzTest(maxDuration = "1m")
    void fuzzCompressionConfigurations(byte @Unmodifiable [] data) throws IOException {
        if (data.length < HEADER_SIZE
                || data.length > HEADER_SIZE + FuzzSupport.MAX_ROUND_TRIP_INPUT_SIZE) {
            return;
        }

        int primary = Byte.toUnsignedInt(data[1]);
        int secondary = Byte.toUnsignedInt(data[2]);
        int bufferControl = Byte.toUnsignedInt(data[3]);
        byte[] expected = Arrays.copyOfRange(data, HEADER_SIZE, data.length);
        byte[] dictionary = generatedDictionary(expected);
        CompressionFormat format = FuzzSupport.compressionFormat(Byte.toUnsignedInt(data[0]));
        CompressionCodec<?> codec = configureCodec(
                format.defaultCodec(),
                expected.length,
                primary,
                secondary,
                dictionary
        );

        ByteBuffer source = guardedBuffer(expected, (bufferControl & 1) != 0, (bufferControl & 2) != 0);
        int sourceLimit = source.limit();
        ByteOrder sourceOrder = source.order();
        ByteBuffer compressed = codec.compress(source);
        if (source.position() != sourceLimit || source.limit() != sourceLimit || source.order() != sourceOrder) {
            throw new AssertionError("Configured compression did not consume the source without changing its view");
        }

        byte[] encoded = FuzzSupport.remainingBytes(compressed);
        ByteBuffer encodedSource = guardedBuffer(encoded, (bufferControl & 4) != 0, (bufferControl & 8) != 0);
        int encodedLimit = encodedSource.limit();
        ByteOrder encodedOrder = encodedSource.order();
        ByteBuffer decoded = codec.decompress(encodedSource);
        if (encodedSource.position() != encodedLimit
                || encodedSource.limit() != encodedLimit
                || encodedSource.order() != encodedOrder) {
            throw new AssertionError("Configured decompression did not consume the encoding without changing its view");
        }
        if (!Arrays.equals(expected, FuzzSupport.remainingBytes(decoded))) {
            throw new AssertionError("Configured codec round trip changed source bytes: " + format.name());
        }
    }

    /// Applies all generic and format-specific settings selected by two arbitrary control bytes.
    private static CompressionCodec<?> configureCodec(
            CompressionCodec<?> base,
            int sourceSize,
            int primary,
            int secondary,
            byte @Unmodifiable [] dictionary
    ) {
        CompressionCodec<?> configured = configureCompressionLevel(base, primary);
        DeflateStrategy deflateStrategy = DeflateStrategy.values()[secondary % DeflateStrategy.values().length];

        if (configured instanceof DeflateCodec deflate) {
            configured = deflate.withStrategy(deflateStrategy);
            if ((secondary & 0x04) != 0) {
                configured = ((DeflateCodec) configured).withDictionary(RawCompressionDictionary.of(dictionary));
            }
        } else if (configured instanceof ZlibCodec zlib) {
            configured = zlib.withStrategy(deflateStrategy);
            if ((secondary & 0x04) != 0) {
                configured = ((ZlibCodec) configured).withDictionary(ZlibDictionary.of(dictionary));
            }
        } else if (configured instanceof GzipCodec gzip) {
            configured = gzip.withStrategy(deflateStrategy);
        } else if (configured instanceof LZ4Codec lz4) {
            LZ4Codec selected = lz4
                    .withBlockSize(LZ4BlockSize.values()[primary % LZ4BlockSize.values().length])
                    .withIndependentBlocks((secondary & 0x08) != 0)
                    .withBlockChecksum((secondary & 0x10) != 0)
                    .withContentChecksum((secondary & 0x20) != 0)
                    .withVerifyChecksums(true);
            if ((secondary & 0x04) != 0) {
                LZ4Dictionary selectedDictionary = (secondary & 0x40) == 0
                        ? LZ4Dictionary.rawContent(dictionary)
                        : LZ4Dictionary.identifiedByContent(dictionary);
                selected = selected.withDictionary(selectedDictionary);
            }
            configured = selected;
        } else if (configured instanceof ZstdCodec zstd) {
            ZstdCodec selected = zstd
                    .withFrameFormat(ZstdFrameFormat.values()[primary % ZstdFrameFormat.values().length])
                    .withFrameChecksum((secondary & 0x10) != 0)
                    .withVerifyChecksums(true);
            if ((secondary & 0x04) != 0) {
                selected = selected.withDictionary(ZstdDictionary.rawContent(dictionary));
            }
            configured = selected.toBuilder()
                    .contentSize((secondary & 0x20) != 0)
                    .dictionaryId((secondary & 0x40) != 0)
                    .build();
        } else if (configured instanceof XZCodec xz) {
            configured = xz
                    .withProperties(lzmaProperties(primary, secondary))
                    .withCheckType(XZCheckType.values()[secondary % XZCheckType.values().length])
                    .withBlockSize(1L + Math.floorMod(sourceSize + primary, 128))
                    .withVerifyChecksums(true);
        } else if (configured instanceof RawLZMACodec rawLzma) {
            configured = rawLzma
                    .withProperties(lzmaProperties(primary, secondary))
                    .withEndMarker((secondary & 0x08) != 0);
        } else if (configured instanceof LZMACodec lzma) {
            configured = lzma.withProperties(lzmaProperties(primary, secondary));
        } else if (configured instanceof LZMA2Codec lzma2) {
            configured = lzma2.withProperties(lzmaProperties(primary, secondary));
        } else if (configured instanceof LzipCodec lzip) {
            configured = lzip.withDictionarySize(4 * 1024 << (primary & 0x03));
        } else if (configured instanceof PPMdCodec ppmd) {
            configured = ppmd
                    .withMaximumOrder(2 + primary % 15)
                    .withMemorySize(1L << (20 + (secondary & 0x03)));
        }

        return FuzzSupport.boundedCodec(configured, sourceSize);
    }

    /// Selects a minimum, default, maximum, or middle compression level when the codec exposes that capability.
    private static CompressionCodec<?> configureCompressionLevel(CompressionCodec<?> codec, int selector) {
        if (!(codec instanceof CompressionCodec.LevelConfigurable<?> configurable)) {
            return codec;
        }
        long minimum = configurable.minimumCompressionLevel();
        long maximum = configurable.maximumCompressionLevel();
        long level = switch (selector & 0x03) {
            case 0 -> minimum;
            case 1 -> configurable.defaultCompressionLevel();
            case 2 -> maximum;
            default -> minimum + (maximum - minimum) / 2L;
        };
        return configurable.withCompressionLevel(level);
    }

    /// Creates valid small LZMA properties from arbitrary controls.
    private static LZMAProperties lzmaProperties(int primary, int secondary) {
        int literalPositionBits = primary % 5;
        int literalContextBits = secondary % (5 - literalPositionBits);
        int positionBits = (primary >>> 3) % 5;
        int dictionarySize = 4 * 1024 << ((secondary >>> 3) & 0x03);
        return new LZMAProperties(
                literalContextBits,
                literalPositionBits,
                positionBits,
                dictionarySize
        );
    }

    /// Builds dictionary bytes that follow source mutations while remaining valid for every tested dictionary format.
    private static byte @Unmodifiable [] generatedDictionary(byte @Unmodifiable [] source) {
        byte[] dictionary = new byte[GENERATED_DICTIONARY_SIZE];
        for (int index = 0; index < dictionary.length; index++) {
            dictionary[index] = source.length == 0
                    ? (byte) (index * 31 + 17)
                    : source[index % source.length];
        }
        return dictionary;
    }

    /// Creates a heap or direct source with inaccessible guard bytes and optional read-only access.
    private static ByteBuffer guardedBuffer(
            byte @Unmodifiable [] content,
            boolean direct,
            boolean readOnly
    ) {
        ByteBuffer storage = direct
                ? ByteBuffer.allocateDirect(content.length + 4)
                : ByteBuffer.allocate(content.length + 4);
        storage.position(2);
        storage.put(content);
        storage.flip();
        storage.position(2);
        storage.order(ByteOrder.LITTLE_ENDIAN);
        return readOnly ? storage.asReadOnlyBuffer().order(storage.order()) : storage;
    }

    /// Supplies three configuration families for every installed compression format.
    ///
    /// @return deterministic configuration fuzz seeds
    private static Stream<Arguments> configurationSeeds() {
        return IntStream.range(0, FuzzSupport.COMPRESSION_FORMATS.size())
                .boxed()
                .flatMap(formatIndex -> IntStream.range(0, 3).mapToObj(variant -> Arguments.of((Object) FuzzSupport.prefix(
                        new byte[]{
                                formatIndex.byteValue(),
                                (byte) (variant == 2 ? 0xfe : variant),
                                (byte) (variant == 0 ? 0 : 0x7c | variant),
                                (byte) (variant * 5)
                        },
                        FuzzSupport.SEED_CONTENT
                ))));
    }
}
