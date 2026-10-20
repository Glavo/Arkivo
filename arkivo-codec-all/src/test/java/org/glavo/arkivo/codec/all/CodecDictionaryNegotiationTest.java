// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.DictionaryRequest;
import org.glavo.arkivo.codec.DictionaryRequiredException;
import org.glavo.arkivo.codec.deflate.ZlibCodec;
import org.glavo.arkivo.codec.deflate.ZlibDictionary;
import org.glavo.arkivo.codec.deflate.ZlibDictionaryRequest;
import org.glavo.arkivo.codec.lz4.LZ4Codec;
import org.glavo.arkivo.codec.lz4.LZ4Dictionary;
import org.glavo.arkivo.codec.lz4.LZ4DictionaryRequest;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.glavo.arkivo.codec.zstd.ZstdDictionary;
import org.glavo.arkivo.codec.zstd.ZstdDictionaryRequest;
import org.glavo.arkivo.codec.zstd.ZstdDictionaryTrainer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Verifies dictionary negotiation independently of format-specific identifier and dictionary representations.
@NotNullByDefault
final class CodecDictionaryNegotiationTest {
    /// Training and history bytes shared by the three format-specific dictionaries.
    private static final byte @Unmodifiable [] DICTIONARY_BYTES = dictionaryBytes();

    /// Content with matches in the preset history and enough output to require repeated calls.
    private static final byte @Unmodifiable [] CONTENT = Arrays.copyOfRange(DICTIONARY_BYTES, 1024, 3072);

    /// Immutable configurations; each test creates its own decoder state.
    private static final @Unmodifiable List<Scenario<?>> SCENARIOS = scenarios();

    /// Covers bounded and unlimited decoders with complete or fragmented heap and direct input.
    private static Stream<Arguments> configurations() {
        return SCENARIOS.stream().flatMap(scenario -> Stream.of(false, true).flatMap(direct ->
                Stream.of(false, true).flatMap(fragmented -> Stream.of(false, true)
                        .map(limited -> Arguments.of(scenario, direct, fragmented, limited)))));
    }

    /// Covers complete-buffer factories and the additional one-frame factories where supported.
    private static Stream<Arguments> bufferAdapters() {
        return SCENARIOS.stream().flatMap(scenario ->
                Stream.of("allocating", "buffer", "frame-allocating", "frame-buffer")
                        .filter(operation -> !operation.startsWith("frame-")
                                || scenario.plain() instanceof CompressionCodec.Framed<?>)
                        .flatMap(operation -> Stream.of(false, true)
                                .map(direct -> Arguments.of(scenario, operation, direct))));
    }

    /// Covers stream, channel, and complete-transfer adapters without duplicating buffer-layout cases.
    private static Stream<Arguments> transportAdapters() {
        return SCENARIOS.stream().flatMap(scenario -> Stream.of("stream", "channel", "transfer")
                .map(operation -> Arguments.of(scenario, operation)));
    }

    /// Complete-buffer adapters preserve the typed request and report consumed header bytes without output.
    @ParameterizedTest(name = "{0}, {1}, direct={2}")
    @MethodSource("bufferAdapters")
    void bufferAdaptersPreserveRequest(Scenario<?> scenario, String operation, boolean direct) throws IOException {
        byte[] encoded = encode(scenario.configured(), CONTENT);
        CompressionCodec<?> codec = scenario.plain().withMaximumOutputSize(CONTENT.length);
        @UnmodifiableView ByteBuffer source = source(encoded, direct);
        ByteBuffer target = target(CONTENT.length, direct);
        int sourceLimit = source.limit();
        DictionaryRequiredException failure = assertThrows(DictionaryRequiredException.class, () -> {
            switch (operation) {
                case "allocating" -> codec.decompress(source);
                case "buffer" -> codec.decompress(source, target);
                case "frame-allocating" -> ((CompressionCodec.Framed<?>) codec).decompressFrame(source);
                case "frame-buffer" -> ((CompressionCodec.Framed<?>) codec).decompressFrame(source, target);
                default -> throw new AssertionError(operation);
            }
        });
        assertEquals(scenario.request(), failure.request());
        assertTrue(source.position() > 2 && source.position() < sourceLimit);
        assertEquals(sourceLimit, source.limit());
        assertEquals(ByteOrder.LITTLE_ENDIAN, source.order());
        assertEquals(2, target.position());
        verifyTarget(target, CONTENT.length);
    }

    /// Transport adapters expose the complete dictionary request without delivering undecodable body bytes.
    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("transportAdapters")
    void transportAdaptersPreserveRequest(Scenario<?> scenario, String operation) throws IOException {
        byte[] encoded = encode(scenario.configured(), CONTENT);
        CompressionCodec<?> codec = scenario.plain().withMaximumOutputSize(CONTENT.length);
        ByteArrayInputStream source = new ByteArrayInputStream(encoded);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DictionaryRequiredException failure;
        switch (operation) {
            case "stream" -> {
                try (var stream = codec.newInputStream(source)) {
                    failure = assertThrows(DictionaryRequiredException.class, () -> stream.transferTo(output));
                }
            }
            case "channel" -> {
                ByteBuffer target = target(CONTENT.length, true);
                try (var channel = codec.newReadableByteChannel(Channels.newChannel(source))) {
                    failure = assertThrows(DictionaryRequiredException.class, () -> channel.read(target));
                }
                assertEquals(2, target.position());
                verifyTarget(target, CONTENT.length);
            }
            case "transfer" -> {
                try (var input = Channels.newChannel(source); var target = Channels.newChannel(output)) {
                    failure = assertThrows(DictionaryRequiredException.class, () -> codec.decompress(input, target));
                    assertTrue(input.isOpen());
                    assertTrue(target.isOpen());
                }
            }
            default -> throw new AssertionError(operation);
        }
        assertEquals(scenario.request(), failure.request());
        assertEquals(0, output.size());
    }

    /// Keeps a rejected request intact and resumes at the saved source position with the correct dictionary.
    ///
    /// @param <D> the scenario's dictionary type
    @ParameterizedTest(name = "{0}, direct={1}, fragmented={2}, limited={3}")
    @MethodSource("configurations")
    <D extends CompressionDictionary> void resumesPendingRequest(
            Scenario<D> scenario, boolean direct, boolean fragmented, boolean limited
    ) throws IOException {
        byte[] encoded = encode(scenario.configured(), CONTENT);
        try (var decoder = scenario.factory().create(limit(limited))) {
            assertThrows(IllegalStateException.class, decoder::dictionaryRequest);
            @UnmodifiableView ByteBuffer source = source(encoded, direct);
            awaitRequest(decoder, source, fragmented);
            verifyRequest(decoder, scenario);

            for (int attempt = 0; attempt < 2; attempt++) {
                assertThrows(IllegalArgumentException.class, () -> decoder.provideDictionary(scenario.wrong()));
                verifyRequest(decoder, scenario);
                int position = source.position();
                for (int capacity : new int[]{0, 7}) {
                    ByteBuffer target = target(capacity, direct);
                    assertEquals(CodecOutcome.NEEDS_DICTIONARY, decoder.decode(source, target));
                    assertEquals(CodecOutcome.NEEDS_DICTIONARY, decoder.finish(source, target));
                    assertEquals(position, source.position());
                    assertEquals(2, target.position());
                    verifyTarget(target, capacity);
                }
            }
            decoder.provideDictionary(scenario.dictionary());
            assertThrows(IllegalStateException.class, decoder::dictionaryRequest);
            assertArrayEquals(CONTENT, finish(decoder, source, direct));

            // A late dictionary belongs to the discarded session, not to the codec configuration.
            decoder.reset();
            assertThrows(IllegalStateException.class, decoder::dictionaryRequest);
            source = source(encoded, direct);
            awaitRequest(decoder, source, fragmented);
            verifyRequest(decoder, scenario);
            decoder.provideDictionary(scenario.dictionary());
            assertArrayEquals(CONTENT, finish(decoder, source, direct));
        }
    }

    /// Discards an unsatisfied request without leaking it into a dictionary-free stream or the next request.
    ///
    /// @param <D> the scenario's dictionary type
    @ParameterizedTest(name = "{0}, direct={1}, fragmented={2}, limited={3}")
    @MethodSource("configurations")
    <D extends CompressionDictionary> void resetsPendingRequest(
            Scenario<D> scenario, boolean direct, boolean fragmented, boolean limited
    ) throws IOException {
        byte[] encoded = encode(scenario.configured(), CONTENT);
        byte[] plain = encode(scenario.plain(), CONTENT);
        try (var decoder = scenario.factory().create(limit(limited))) {
            @UnmodifiableView ByteBuffer abandoned = source(encoded, direct);
            awaitRequest(decoder, abandoned, fragmented);
            verifyRequest(decoder, scenario);
            int abandonedPosition = abandoned.position();
            decoder.reset();
            decoder.reset();
            assertThrows(IllegalStateException.class, decoder::dictionaryRequest);
            assertArrayEquals(CONTENT, finish(decoder, source(plain, direct), direct));
            assertEquals(abandonedPosition, abandoned.position());

            decoder.reset();
            @UnmodifiableView ByteBuffer source = source(encoded, direct);
            awaitRequest(decoder, source, fragmented);
            verifyRequest(decoder, scenario);
            decoder.provideDictionary(scenario.dictionary());
            assertArrayEquals(CONTENT, finish(decoder, source, direct));
        }
    }

    /// Restores configured dictionaries after completion and after abandoning partially produced output.
    @ParameterizedTest(name = "{0}, direct={1}, fragmented={2}, limited={3}")
    @MethodSource("configurations")
    void restoresConfiguredDictionary(Scenario<?> scenario, boolean direct, boolean fragmented, boolean limited)
            throws IOException {
        byte[] encoded = encode(scenario.configured(), CONTENT);
        try (var decoder = scenario.configured().withMaximumOutputSize(limit(limited)).newDecoder()) {
            @UnmodifiableView ByteBuffer partial = source(encoded, direct);
            int end = partial.limit();
            if (fragmented) {
                partial.limit(3);
            }
            ByteBuffer target = target(1, direct);
            for (int call = 0; call < encoded.length + 1; call++) {
                CodecOutcome outcome = partial.limit() == end
                        ? decoder.finish(partial, target) : decoder.decode(partial, target);
                if (outcome == CodecOutcome.NEEDS_OUTPUT) {
                    assertEquals(3, target.position());
                    assertEquals(CONTENT[0], target.get(2));
                    break;
                }
                assertEquals(CodecOutcome.NEEDS_INPUT, outcome);
                assertTrue(partial.limit() < end);
                partial.limit(partial.limit() + 1);
            }
            assertEquals(3, target.position());
            verifyTarget(target, 1);
            for (int attempt = 0; attempt < 2; attempt++) {
                decoder.reset();
                assertArrayEquals(CONTENT, finish(decoder, source(encoded, direct), direct));
            }
        }
    }

    /// Advances only through header bytes until the decoder exposes its dictionary requirement.
    private static void awaitRequest(CompressionDecoder decoder, ByteBuffer source, boolean fragmented)
            throws IOException {
        int end = source.limit();
        if (fragmented) {
            source.limit(source.position() + 1);
        }
        ByteBuffer target = target(7, source.isDirect());
        for (int call = 0; call <= end; call++) {
            CodecOutcome outcome = fragmented ? decoder.decode(source, target) : decoder.finish(source, target);
            assertEquals(2, target.position());
            verifyTarget(target, 7);
            if (outcome == CodecOutcome.NEEDS_DICTIONARY) {
                source.limit(end);
                return;
            }
            assertEquals(CodecOutcome.NEEDS_INPUT, outcome);
            assertTrue(fragmented && source.limit() < end);
            source.limit(source.limit() + 1);
        }
        fail("Decoder did not request its dictionary");
    }

    /// Checks the complete format-specific request and both matching and nonmatching dictionary values.
    ///
    /// @param <D> the dictionary type accepted by the decoder
    private static <D extends CompressionDictionary> void verifyRequest(
            CompressionDecoder.DictionaryAware<D, ?> decoder, Scenario<D> scenario
    ) {
        DictionaryRequest<D> request = decoder.dictionaryRequest();
        assertEquals(scenario.request(), request);
        assertTrue(request.matches(scenario.dictionary()));
        assertFalse(request.matches(scenario.wrong()));
    }

    /// Finishes with small guarded targets and verifies the container trailer remains unconsumed.
    private static byte @Unmodifiable [] finish(CompressionDecoder decoder, ByteBuffer source, boolean direct)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int sourceLimit = source.limit();
        for (int call = 0; call <= CONTENT.length; call++) {
            ByteBuffer target = target(7, direct);
            CodecOutcome outcome = decoder.finish(source, target);
            assertEquals(sourceLimit, source.limit());
            assertEquals(ByteOrder.LITTLE_ENDIAN, source.order());
            verifyTarget(target, 7);
            int end = target.position();
            for (int index = 2; index < end; index++) {
                output.write(target.get(index));
            }
            if (outcome == CodecOutcome.FINISHED) {
                assertEquals(sourceLimit - 1, source.position());
                assertEquals((byte) 0x39, source.get(source.position()));
                return output.toByteArray();
            }
            assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
            assertEquals(target.limit(), end);
        }
        return fail("Decoder did not complete after dictionary provision");
    }

    /// Creates a read-only source with a nonzero starting position and a trailing container byte.
    private static @UnmodifiableView ByteBuffer source(byte @Unmodifiable [] encoded, boolean direct) {
        ByteBuffer storage = direct ? ByteBuffer.allocateDirect(encoded.length + 3)
                : ByteBuffer.allocate(encoded.length + 3);
        storage.position(2).put(encoded).put((byte) 0x39).flip().position(2);
        return storage.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    /// Creates a writable window surrounded by sentinel bytes.
    private static ByteBuffer target(int capacity, boolean direct) {
        ByteBuffer result = direct ? ByteBuffer.allocateDirect(capacity + 4) : ByteBuffer.allocate(capacity + 4);
        for (int index = 0; index < result.capacity(); index++) {
            result.put(index, (byte) 0x5a);
        }
        return result.position(2).limit(capacity + 2).order(ByteOrder.LITTLE_ENDIAN);
    }

    /// Verifies output limits, byte order, and all bytes not reported as produced output.
    private static void verifyTarget(ByteBuffer target, int capacity) {
        assertEquals(capacity + 2, target.limit());
        assertEquals(ByteOrder.LITTLE_ENDIAN, target.order());
        assertTrue(target.position() >= 2 && target.position() <= target.limit());
        ByteBuffer view = target.duplicate().clear();
        assertEquals((byte) 0x5a, view.get(0));
        assertEquals((byte) 0x5a, view.get(1));
        for (int index = target.position(); index < view.limit(); index++) {
            assertEquals((byte) 0x5a, view.get(index));
        }
    }

    /// Selects an exact output allowance or leaves the decoder unwrapped by output limiting.
    private static long limit(boolean limited) {
        return limited ? CONTENT.length : CompressionCodec.UNLIMITED_SIZE;
    }

    /// Encodes one stream using its format-specific dictionary configuration.
    private static byte @Unmodifiable [] encode(CompressionCodec<?> codec, byte @Unmodifiable [] content)
            throws IOException {
        ByteBuffer encoded = codec.compress(ByteBuffer.wrap(content));
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        return result;
    }

    /// Builds deterministic typed dictionary fixtures without native dependencies or binary resources.
    private static @Unmodifiable List<Scenario<?>> scenarios() {
        ZlibDictionary zlib = ZlibDictionary.of(DICTIONARY_BYTES);
        LZ4Dictionary lz4 = LZ4Dictionary.identified(23L, DICTIONARY_BYTES);
        ZstdDictionaryTrainer trainer = new ZstdDictionaryTrainer(DICTIONARY_BYTES.length, 1024);
        trainer.addSample(ByteBuffer.wrap(DICTIONARY_BYTES));
        ZstdDictionary zstd = trainer.train();
        return List.of(
                new Scenario<>(ZlibCodec.DEFAULT, ZlibCodec.DEFAULT.withDictionary(zlib),
                        maximum -> ZlibCodec.DEFAULT.withMaximumOutputSize(maximum).newDecoder(),
                        zlib, ZlibDictionary.of(new byte[DICTIONARY_BYTES.length]),
                        new ZlibDictionaryRequest(zlib.adler32())),
                new Scenario<>(LZ4Codec.DEFAULT, LZ4Codec.DEFAULT.withDictionary(lz4),
                        maximum -> LZ4Codec.DEFAULT.withMaximumOutputSize(maximum).newDecoder(),
                        lz4, LZ4Dictionary.identified(24L, DICTIONARY_BYTES), new LZ4DictionaryRequest(23L)),
                new Scenario<>(ZstdCodec.DEFAULT, ZstdCodec.DEFAULT.withDictionary(zstd),
                        maximum -> ZstdCodec.DEFAULT.withMaximumOutputSize(maximum).newDecoder(),
                        zstd, ZstdDictionary.rawContent(DICTIONARY_BYTES),
                        new ZstdDictionaryRequest(zstd.dictionaryId()))
        );
    }

    /// Creates repeatable history containing both recurring phrases and varying bytes.
    private static byte @Unmodifiable [] dictionaryBytes() {
        byte[] result = new byte[8192];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) (index % 97 < 80 ? 'a' + index % 23 : index * 37);
        }
        return result;
    }

    /// Creates a dictionary-aware engine while preserving its accepted dictionary type.
    ///
    /// @param <D> the accepted dictionary type
    @FunctionalInterface
    @NotNullByDefault
    private interface DecoderFactory<D extends CompressionDictionary> {
        /// Creates an unconfigured engine with the selected output allowance.
        CompressionDecoder.DictionaryAware<D, ?> create(long maximumOutputSize) throws IOException;
    }

    /// Holds format-specific data while the test methods exercise common negotiation behavior.
    ///
    /// @param <D> the dictionary type
    /// @param plain the dictionary-free codec
    /// @param configured the codec with the matching dictionary installed
    /// @param factory the dictionary-free decoder factory
    /// @param dictionary the matching dictionary
    /// @param wrong a dictionary that does not match the encoded identifier
    /// @param request the complete expected request
    @NotNullByDefault
    private record Scenario<D extends CompressionDictionary>(
            CompressionCodec<?> plain, CompressionCodec<?> configured, DecoderFactory<D> factory,
            D dictionary, D wrong, DictionaryRequest<D> request
    ) {
        /// Returns the format name used in parameterized test reports.
        @Override
        public String toString() {
            return plain.format().name();
        }
    }
}
