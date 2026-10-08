// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Replays the original input layouts and assertions of Zstandard's dictionary-loading and round-trip fuzz targets.
///
/// The original native loader and assertions run in a separate test-only executable. Native by-reference loading
/// maps to Java's immutable copied dictionary; native single-use prefixes map to explicitly configured dictionaries.
/// Java rejects dictionary objects shorter than eight bytes and full dictionaries with identifier zero. Short raw
/// dictionaries ignored by native compression are also tested with an unconfigured Java codec.
/// Set `ARKIVO_ZSTD_DICTIONARY_LOADER_EXECUTABLE` to `buildZstdDictionaryLoaderReference`'s executable;
/// `ARKIVO_REQUIRE_ZSTD_DICTIONARY_LOADER=true` makes an absent executable fail instead of skip.
/// Raw-history tests use `ARKIVO_ZSTD_RAW_DICTIONARY_EXECUTABLE` from `buildZstdRawDictionaryReference`
/// and the corresponding `ARKIVO_REQUIRE_ZSTD_RAW_DICTIONARY` flag.
/// Trained-dictionary round trips use `ARKIVO_ZSTD_DICTIONARY_ROUND_TRIP_EXECUTABLE` from
/// `buildZstdDictionaryRoundTripReference` and `ARKIVO_REQUIRE_ZSTD_DICTIONARY_ROUND_TRIP`.
/// Dictionary decoding uses `ARKIVO_ZSTD_DICTIONARY_DECOMPRESS_EXECUTABLE` from
/// `buildZstdDictionaryDecompressReference` and `ARKIVO_REQUIRE_ZSTD_DICTIONARY_DECOMPRESS`.
@NotNullByDefault
@Timeout(240)
final class ZstdOfficialDictionaryFuzzTest {
    /// Holds generated requests and preserves failing native inputs for reproduction.
    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    Path directory;

    /// Verifies original dictionary interpretation, rejection, buffer ownership, reuse and both interoperability directions.
    @Test
    void officialDictionaryLoading() throws IOException, InterruptedException {
        String executable = executable("DICTIONARY_LOADER");
        List<byte[]> seeds = seeds();
        assertEquals(5321, seeds.size());
        List<String> requests = seeds.stream().map(seed -> "L " + bytes(seed)).toList();
        List<String> results = run(executable, requests, "loaders");
        assertEquals(seeds.size(), results.size());
        List<String> decodeRequests = new ArrayList<>();
        List<byte[]> expectedDecoded = new ArrayList<>();
        int accepted = 0;
        int rejected = 0;
        int shortDictionaries = 0;
        int zeroIdentifiers = 0;
        for (int index = 0; index < seeds.size(); index++) {
            byte[] seed = seeds.get(index);
            Input input = input(seed);
            byte[] plain = Arrays.copyOf(seed, input.size());
            String[] fields = results.get(index).split(" ");
            assertEquals(7, fields.length);
            assertEquals(input.size(), Integer.parseInt(fields[0]));
            assertEquals(input.type(), Integer.parseInt(fields[1]));
            assertEquals(input.load(), Integer.parseInt(fields[2]));
            assertEquals(input.prefix(), Integer.parseInt(fields[3]));
            int error = Integer.parseInt(fields[4]);
            byte[] frame = hex(fields[6]);
            assertEquals(frame.length, Integer.parseInt(fields[5]));
            assertTrue(frame.length <= Zstd.compressBound(plain.length));
            try {
                boolean magic = plain.length >= 8
                        && Integer.toUnsignedLong(ByteArrayAccess.readIntLittleEndian(plain, 0))
                        == ZstdDictionary.FORMATTED_DICTIONARY_MAGIC;
                boolean zeroId = magic && input.type() != 1 && ByteArrayAccess.readIntLittleEndian(plain, 4) == 0;
                ZstdCodec codec;
                if (plain.length < 8 || zeroId || input.type() == 2 && !magic) {
                    for (int kind = 0; kind < 3; kind++) {
                        ByteBuffer source = source(plain, kind);
                        assertThrows(IllegalArgumentException.class, () -> dictionary(input.type(), source));
                        checkSource(source, plain, 3);
                    }
                    if (zeroId) {
                        assertEquals(0, error, "The native API permits identifier zero in this valid full dictionary");
                        zeroIdentifiers++;
                        continue;
                    }
                    if (plain.length >= 8) {
                        assertTrue(error != 0, "Native full dictionary must reject missing magic");
                        rejected++;
                        continue;
                    }
                    shortDictionaries++;
                    if (error != 0) {
                        assertEquals(2, input.type());
                        continue;
                    }
                    codec = ZstdCodec.DEFAULT;
                } else {
                    codec = ZstdCodec.DEFAULT.withDictionary(dictionary(input.type(), source(plain, index % 3)));
                    if (error != 0) {
                        assertTrue(input.type() != 1, "Native raw dictionaries must load");
                        ZstdCodec malformed = codec;
                        assertThrows(IOException.class, () -> malformed.compress(ByteBuffer.wrap(plain)));
                        rejected++;
                        continue;
                    }
                }
                accepted++;
                for (int kind = 0; kind < 3; kind++) decode(codec, frame, plain, kind);
                byte[] encoded;
                try (var encoder = codec.newEncoder()) {
                    encoded = encode(encoder, plain, index % 3);
                    encoder.reset();
                    assertArrayEquals(encoded, encode(encoder, plain, index % 3), "Configured dictionary after reset");
                }
                decode(codec, encoded, plain, index % 3);
                decodeRequests.add("D " + bytes(seed) + " " + bytes(encoded));
                expectedDecoded.add(plain);
                ByteBuffer oneShotSource = source(plain, index % 3);
                ByteBuffer oneShot = codec.compress(oneShotSource);
                checkSource(oneShotSource, plain, plain.length + 3);
                byte[] oneShotFrame = new byte[oneShot.remaining()];
                oneShot.get(oneShotFrame);
                decodeRequests.add("D " + bytes(seed) + " " + bytes(oneShotFrame));
                expectedDecoded.add(plain);
                if (plain.length >= 8) {
                    // The immutable dictionary must not retain its caller's writable storage.
                    ByteBuffer mutable = source(plain, index % 2);
                    ZstdDictionary copied = dictionary(input.type(), mutable);
                    checkSource(mutable, plain, 3);
                    mutable.put(3, (byte) (mutable.get(3) ^ 0xff));
                    assertArrayEquals(plain, copied.bytes());
                    decode(ZstdCodec.DEFAULT.withDictionary(copied), frame, plain, index % 3);
                }
            } catch (IOException | RuntimeException | AssertionError failure) {
                throw new AssertionError("Seed " + index + ", " + input + ", nativeError=" + error, failure);
            }
        }
        assertTrue(accepted > 100 && rejected > 100 && shortDictionaries > 0 && zeroIdentifiers > 0,
                "accepted=" + accepted + ", rejected=" + rejected + ", short=" + shortDictionaries + ", zero=" + zeroIdentifiers);
        List<String> decoded = run(executable, decodeRequests, "java-frames");
        assertEquals(expectedDecoded.size(), decoded.size());
        for (int index = 0; index < decoded.size(); index++) {
            String[] fields = decoded.get(index).split(" ");
            assertEquals(2, fields.length);
            assertEquals(expectedDecoded.get(index).length, Integer.parseInt(fields[0]));
            assertArrayEquals(expectedDecoded.get(index), hex(fields[1]), "Native decoding of Java frame " + index);
        }
    }

    /// Retains raw_dictionary_round_trip.c's independent dictionary partition, reduced capacity and loading modes.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void officialRawDictionaries(boolean reuse) throws IOException, InterruptedException {
        String executable = executable("RAW_DICTIONARY");
        List<byte[]> seeds = rawSeeds();
        assertEquals(887, seeds.size());
        List<String> responses = run(executable, seeds.stream().map(seed -> "R " + bytes(seed)).toList(), "raw", reuse);
        assertEquals(seeds.size(), responses.size());
        List<String> decodeRequests = new ArrayList<>();
        List<byte[]> expectedDecoded = new ArrayList<>();
        Map<String, Set<Integer>> observed = new HashMap<>();
        int magicDictionaries = 0;
        int reducedCapacity = 0;
        Set<Integer> largeInputWorkers = new HashSet<>();
        for (int index = 0; index < seeds.size(); index++) {
            byte[] seed = seeds.get(index);
            ZstdFuzzDataProducer producer = new ZstdFuzzDataProducer(seed);
            int total = producer.reservePrefix();
            int size = (int) producer.range(0, total);
            int capacity = Math.toIntExact(Zstd.compressBound(size) - producer.range(0, 1));
            int prefix = (int) producer.range(0, 1);
            byte[] plain = Arrays.copyOf(seed, size);
            byte[] history = Arrays.copyOfRange(seed, size, total);
            String[] fields = responses.get(index).split(" ");
            assertEquals(24, fields.length);
            Map<String, Integer> parameters = new HashMap<>();
            for (int field = 0; field < fields.length - 1; field++) {
                String[] pair = fields[field].split("=");
                assertEquals(2, pair.length);
                assertTrue(parameters.put(pair[0], Integer.parseInt(pair[1])) == null);
            }
            assertEquals(size, parameters.get("size"));
            assertEquals(history.length, parameters.get("dictionary"));
            assertEquals(capacity, parameters.get("capacity"));
            assertEquals(prefix, parameters.get("prefix"));
            assertEquals(0, parameters.get("checksum"));
            for (String key : List.of("strategy", "workers", "prefix", "compressionLoad", "decompressionLoad")) {
                observed.computeIfAbsent(key, ignored -> new HashSet<>()).add(parameters.get(key));
            }
            if (capacity < Zstd.compressBound(size)) reducedCapacity++;
            if (size > 1024 * 1024) largeInputWorkers.add(parameters.get("workers"));
            if (history.length >= 8 && ByteArrayAccess.readIntLittleEndian(history, 0)
                    == (int) ZstdDictionary.FORMATTED_DICTIONARY_MAGIC) magicDictionaries++;
            byte[] frame = hex(fields[fields.length - 1]);
            assertTrue(frame.length <= capacity);
            try {
                ZstdCodec codec = randomCodec(parameters);
                if (history.length >= 8) codec = codec.withDictionary(ZstdDictionary.rawContent(history));
                else assertThrows(IllegalArgumentException.class, () -> ZstdDictionary.rawContent(history));
                ZstdStandardFrameInfo info = (ZstdStandardFrameInfo) codec.frameInfo(ByteBuffer.wrap(frame));
                assertEquals(0, info.dictionaryId(), "Raw history must not supply a formatted dictionary identifier");
                assertEquals(false, info.checksum());
                for (int kind = 0; kind < 3; kind++) decode(codec, frame, plain, kind);
                byte[] encoded;
                try (var encoder = codec.newEncoder()) {
                    encoded = encode(encoder, plain, index % 3);
                    encoder.reset();
                    assertArrayEquals(encoded, encode(encoder, plain, index % 3));
                }
                decode(codec, encoded, plain, index % 3);
                decodeRequests.add("D " + bytes(seed) + " " + bytes(encoded));
                expectedDecoded.add(plain);
                ByteBuffer oneShotSource = source(plain, index % 3);
                ByteBuffer oneShot = codec.compress(oneShotSource);
                checkSource(oneShotSource, plain, plain.length + 3);
                byte[] oneShotFrame = new byte[oneShot.remaining()];
                oneShot.get(oneShotFrame);
                decodeRequests.add("D " + bytes(seed) + " " + bytes(oneShotFrame));
                expectedDecoded.add(plain);
            } catch (IOException | RuntimeException | AssertionError failure) {
                throw new AssertionError("Raw seed " + index + ", reuse=" + reuse + ", parameters=" + parameters, failure);
            }
        }
        assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9), observed.get("strategy"));
        assertEquals(Set.of(0, 1, 2), observed.get("workers"));
        assertEquals(Set.of(0, 1, 2), largeInputWorkers);
        assertEquals(Set.of(0, 1), observed.get("prefix"));
        assertEquals(Set.of(-1, 0, 1), observed.get("compressionLoad"));
        assertEquals(Set.of(-1, 0, 1), observed.get("decompressionLoad"));
        assertTrue(magicDictionaries > 10 && reducedCapacity > 100);
        List<String> decoded = run(executable, decodeRequests, "raw-java", reuse);
        assertEquals(expectedDecoded.size(), decoded.size());
        for (int index = 0; index < decoded.size(); index++) {
            String[] fields = decoded.get(index).split(" ");
            assertEquals(2, fields.length);
            assertEquals(expectedDecoded.get(index).length, Integer.parseInt(fields[0]));
            assertArrayEquals(expectedDecoded.get(index), hex(fields[1]), "Native raw decoding of Java frame " + index);
        }
    }

    /// Preserves dictionary_round_trip.c's training, parameter rollback and repeated-compression assertions.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void officialTrainedDictionaryRoundTrips(boolean reuse) throws IOException, InterruptedException {
        String executable = executable("DICTIONARY_ROUND_TRIP");
        List<byte[]> seeds = trainedSeeds();
        assertEquals(435, seeds.size());
        List<String> responses = run(executable, seeds.stream().map(seed -> "R " + bytes(seed)).toList(), "trained", reuse);
        assertEquals(seeds.size(), responses.size());
        List<String> decodeRequests = new ArrayList<>();
        List<byte[]> expectedDecoded = new ArrayList<>();
        Map<String, Set<Integer>> observed = new HashMap<>();
        Set<Integer> trainedTypes = new HashSet<>();
        Set<Integer> largeInputWorkers = new HashSet<>();
        Set<Integer> simpleLevels = new HashSet<>();
        int trained = 0;
        int empty = 0;
        for (int index = 0; index < seeds.size(); index++) {
            byte[] seed = seeds.get(index);
            ZstdFuzzDataProducer producer = new ZstdFuzzDataProducer(seed);
            int size = producer.reservePrefix();
            long capacity = Zstd.compressBound(size) - producer.range(0, 1);
            byte[] plain = Arrays.copyOf(seed, size);
            String[] fields = responses.get(index).split(" ");
            assertEquals(27, fields.length);
            Map<String, Integer> parameters = new HashMap<>();
            for (int field = 0; field < fields.length - 2; field++) {
                String[] pair = fields[field].split("=");
                assertEquals(2, pair.length);
                assertTrue(parameters.put(pair[0], Integer.parseInt(pair[1])) == null);
            }
            assertEquals(size, parameters.get("size"));
            assertEquals(capacity, parameters.get("capacity").longValue());
            assertEquals(2, parameters.get("calls"));
            byte[] dictionaryBytes = hex(fields[fields.length - 2]);
            byte[] frame = hex(fields[fields.length - 1]);
            assertEquals(dictionaryBytes.length, parameters.get("dictionary"));
            assertTrue(frame.length <= capacity);
            for (String key : List.of("type", "prefix", "load", "simple")) {
                observed.computeIfAbsent(key, ignored -> new HashSet<>()).add(parameters.get(key));
            }
            int type = parameters.get("type");
            boolean simple = parameters.get("simple") == 1;
            if (simple) simpleLevels.add(parameters.get("level"));
            else {
                observed.computeIfAbsent("strategy", ignored -> new HashSet<>()).add(parameters.get("strategy"));
                if (size > 1024 * 1024) largeInputWorkers.add(parameters.get("workers"));
            }
            try {
                ZstdCodec codec = simple ? ZstdCodec.DEFAULT.withCompressionLevel(parameters.get("level"))
                        : randomCodec(parameters);
                long dictionaryId = 0;
                if (dictionaryBytes.length == 0) empty++;
                else {
                    trained++;
                    trainedTypes.add(type);
                    ZstdDictionary dictionary = dictionary(type, ByteBuffer.wrap(dictionaryBytes));
                    codec = codec.withDictionary(dictionary);
                    dictionaryId = dictionary.dictionaryId();
                }
                ZstdStandardFrameInfo info = (ZstdStandardFrameInfo) codec.frameInfo(ByteBuffer.wrap(frame));
                assertEquals(false, info.checksum());
                assertEquals(simple || parameters.get("dictionaryId") != 0 ? dictionaryId : 0, info.dictionaryId());
                for (int kind = 0; kind < 3; kind++) decode(codec, frame, plain, kind);
                byte[] encoded;
                try (var encoder = codec.newEncoder()) {
                    encoded = encode(encoder, plain, index % 3);
                    encoder.reset();
                    assertArrayEquals(encoded, encode(encoder, plain, index % 3), "Trained dictionary after reset");
                }
                decode(codec, encoded, plain, index % 3);
                String request = "D " + bytes(plain) + " " + type + " " + parameters.get("prefix") + " "
                        + parameters.get("load") + " " + bytes(dictionaryBytes) + " ";
                decodeRequests.add(request + bytes(encoded));
                expectedDecoded.add(plain);
                ByteBuffer source = source(plain, index % 3);
                ByteBuffer oneShot = codec.compress(source);
                checkSource(source, plain, plain.length + 3);
                byte[] oneShotFrame = new byte[oneShot.remaining()];
                oneShot.get(oneShotFrame);
                decodeRequests.add(request + bytes(oneShotFrame));
                expectedDecoded.add(plain);
            } catch (IOException | RuntimeException | AssertionError failure) {
                throw new AssertionError("Trained seed " + index + ", reuse=" + reuse + ", parameters=" + parameters, failure);
            }
        }
        assertTrue(trained > 50 && empty > 50, "trained=" + trained + ", empty=" + empty);
        assertEquals(Set.of(0, 1, 2), trainedTypes);
        assertEquals(Set.of(0, 1, 2), observed.get("type"));
        assertEquals(Set.of(0, 1), observed.get("prefix"));
        assertEquals(Set.of(-1, 0, 1), observed.get("load"));
        assertEquals(Set.of(0, 1), observed.get("simple"));
        assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9), observed.get("strategy"));
        assertEquals(Set.of(0, 1, 2), largeInputWorkers);
        for (int level = -3; level <= 19; level++) assertTrue(simpleLevels.contains(level), "level=" + level);
        List<String> decoded = run(executable, decodeRequests, "trained-java", reuse);
        assertEquals(expectedDecoded.size(), decoded.size());
        for (int index = 0; index < decoded.size(); index++) {
            String[] fields = decoded.get(index).split(" ");
            assertEquals(2, fields.length);
            assertEquals(expectedDecoded.get(index).length, Integer.parseInt(fields[0]));
            assertArrayEquals(expectedDecoded.get(index), hex(fields[1]), "Native trained decoding of Java frame " + index);
        }
    }

    /// Replays dictionary_decompress.c with exact native successful output and guarded bounded failure paths.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void officialDictionaryDecompression(boolean reuse) throws IOException, InterruptedException {
        String executable = executable("DICTIONARY_DECOMPRESS");
        List<byte[]> seeds = decompressionSeeds();
        assertEquals(2693, seeds.size());
        List<String> responses = run(executable, seeds.stream().map(ZstdOfficialDictionaryFuzzTest::bytes).toList(),
                "decompress", reuse);
        assertEquals(seeds.size(), responses.size());
        Set<String> modes = new HashSet<>();
        int accepted = 0;
        int rejected = 0;
        int trained = 0;
        int empty = 0;
        byte[] recoveryPlain = {1, 2, 3};
        byte[] recoveryFrame = Zstd.compress(recoveryPlain);
        for (int index = 0; index < seeds.size(); index++) {
            byte[] seed = seeds.get(index);
            ZstdFuzzDataProducer producer = new ZstdFuzzDataProducer(seed);
            int size = producer.reservePrefix();
            for (int sample = 0; sample < 100; sample++) producer.range(0, Math.max(size, 1) - 1);
            int mode = producer.range(0, 1) == 0 ? 0 : producer.range(0, 1) == 0 ? 1 : 2;
            // C may evaluate the two advanced-loader arguments in either order; both consume one byte.
            if (mode == 1) producer.range(0, 2);
            if (mode != 0) producer.range(0, 2);
            int capacity = (int) producer.range(0, 10L * size);
            byte[] frame = Arrays.copyOf(seed, size);
            String[] fields = responses.get(index).split(" ");
            assertEquals(9, fields.length);
            assertEquals(size, Integer.parseInt(fields[0]));
            assertEquals(capacity, Integer.parseInt(fields[1]));
            assertEquals(mode, Integer.parseInt(fields[2]));
            int type = Integer.parseInt(fields[3]);
            int load = Integer.parseInt(fields[4]);
            int error = Integer.parseInt(fields[5]);
            byte[] dictionaryBytes = hex(fields[7]);
            byte[] expected = hex(fields[8]);
            assertEquals(expected.length, Integer.parseInt(fields[6]));
            modes.add(mode + ":" + type + ":" + load);
            if (error == 0) accepted++; else rejected++;
            if (dictionaryBytes.length == 0) empty++; else trained++;
            ZstdCodec codec = ZstdCodec.DEFAULT.withMaximumWindowSize(8L << 20).withMaximumMemorySize(32L << 20);
            if (dictionaryBytes.length != 0) codec = codec.withDictionary(dictionary(type, ByteBuffer.wrap(dictionaryBytes)));
            for (int kind = 0; kind < 3; kind++) {
                ByteBuffer source = source(frame, kind);
                ByteBuffer target = target(storage(capacity, kind), capacity);
                try {
                    boolean success;
                    try {
                        codec.decompress(source, target);
                        success = true;
                    } catch (IOException | BufferOverflowException malformedOrTooSmall) {
                        success = false;
                    }
                    guards(target);
                    checkSource(source, frame, source.position());
                    assertEquals(error == 0, success, "Native error=" + error);
                    if (success) {
                        assertEquals(source.limit(), source.position());
                        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
                        append(target, decoded);
                        assertArrayEquals(expected, decoded.toByteArray());
                    }
                    if (kind == index % 3) {
                        checkDecoderRecovery(codec, frame, capacity, kind, recoveryFrame, recoveryPlain);
                    }
                } catch (RuntimeException | AssertionError failure) {
                    throw new AssertionError("Decode seed " + index + ", reuse=" + reuse + ", kind=" + kind
                            + ", size=" + size + ", capacity=" + capacity + ", mode=" + mode + ", type=" + type
                            + ", dictionary=" + dictionaryBytes.length + ", nativeError=" + error, failure);
                }
            }
        }
        assertEquals(Set.of("0:0:0", "1:0:0", "1:0:1", "1:1:0", "1:1:1", "1:2:0", "1:2:1",
                "2:0:-1", "2:1:-1", "2:2:-1"), modes);
        assertTrue(accepted > 100 && rejected > 100 && trained > 100 && empty > 0,
                "accepted=" + accepted + ", rejected=" + rejected + ", trained=" + trained + ", empty=" + empty);
    }

    /// Verifies reset permits an independent frame after decoding fails or stops with partial progress.
    private static void checkDecoderRecovery(ZstdCodec codec, byte[] frame, int capacity, int kind,
                                             byte[] recoveryFrame, byte[] recoveryPlain) throws IOException {
        try (var decoder = codec.newDecoder()) {
            ByteBuffer input = source(frame, kind);
            ByteBuffer output = target(storage(capacity, kind), capacity);
            try {
                CodecOutcome outcome = decoder.finish(input, output);
                assertTrue(outcome == CodecOutcome.FINISHED || outcome == CodecOutcome.NEEDS_OUTPUT
                        || outcome == CodecOutcome.NEEDS_DICTIONARY);
            } catch (IOException malformed) {
                // Unlike the complete-buffer adapter, an incremental decoder also rejects an absent initial frame.
            }
            checkSource(input, frame, input.position());
            guards(output);
            decoder.reset();
            input = source(recoveryFrame, kind);
            output = target(storage(recoveryPlain.length, kind), recoveryPlain.length);
            assertEquals(CodecOutcome.FINISHED, decoder.finish(input, output));
            checkSource(input, recoveryFrame, recoveryFrame.length + 3);
            ByteArrayOutputStream restored = new ByteArrayOutputStream();
            append(output, restored);
            assertArrayEquals(recoveryPlain, restored.toByteArray());
        }
    }

    /// Applies the shared native parameter subset after the original fuzz target disables frame checksums.
    private static ZstdCodec randomCodec(Map<String, Integer> values) {
        int strategy = values.get("strategy");
        int bucket = values.get("ldmBucket");
        if (bucket == 0) bucket = Math.max(3, Math.min(8, strategy));
        bucket = Math.min(bucket, values.get("ldmHash"));
        return ZstdCodec.builder().compressionLevel(values.get("level"))
                .windowLog(values.get("window")).hashLog(values.get("hash")).chainLog(values.get("chain"))
                .searchLog(values.get("search")).minimumMatch(values.get("match")).targetLength(values.get("target"))
                .strategy(ZstdStrategy.values()[strategy - 1]).contentSize(values.get("contentSize") != 0)
                .frameChecksum(false).dictionaryId(values.get("dictionaryId") != 0).workerCount(values.get("workers"))
                .longDistanceMatching(values.get("ldm") == 1).longDistanceHashLog(values.get("ldmHash"))
                .longDistanceMinimumMatch(values.get("ldmMatch")).longDistanceBucketSizeLog(bucket)
                .longDistanceHashRateLog(values.get("ldmRate")).build();
    }

    /// Creates finite original-layout seeds with empty, short, magic-prefixed and boundary-sized raw history.
    private static @Unmodifiable List<byte[]> rawSeeds() {
        List<byte[]> seeds = new ArrayList<>();
        Random random = new Random(0x2a_d1c7L);
        for (int dictionarySize : new int[]{0, 1, 7, 8, 9, 31, 32, 1024, 32768}) {
            byte[] dictionary = new byte[dictionarySize];
            random.nextBytes(dictionary);
            if (dictionarySize >= 8) ByteArrayAccess.writeIntLittleEndian(dictionary, 0,
                    (int) ZstdDictionary.FORMATTED_DICTIONARY_MAGIC);
            for (int size : new int[]{0, 1, 31, 4096, 131073}) {
                byte[] plain = new byte[size];
                random.nextBytes(plain);
                if (dictionarySize > 0) {
                    int copied = Math.min(dictionarySize, 32);
                    for (int index = 0; index < size; index++) plain[index] = dictionary[dictionarySize - copied + index % copied];
                }
                for (int prefix = 0; prefix < 2; prefix++) {
                    for (int smaller = 0; smaller < 2; smaller++) {
                        for (int loading = 0; loading < 4; loading++) {
                            byte[] profile = new byte[38];
                            // In the fixed 36-byte FUZZ parameter layout, strategy and worker selection are at 7 and 17.
                            profile[7] = (byte) (seeds.size() % 9);
                            profile[17] = (byte) (seeds.size() % 3);
                            profile[33] = profile[34] = 1; // Skip optional source-size and compressed-block hints.
                            profile[36] = (byte) (loading & 1);
                            profile[37] = (byte) (loading >>> 1);
                            seeds.add(rawSeed(plain, dictionary, smaller, prefix, profile));
                        }
                    }
                }
            }
        }
        // Exceed native single-job thresholds with loaded dictionaries and single-use prefixes.
        for (int prefix = 0; prefix < 2; prefix++) {
            for (int workers = 0; workers <= 2; workers++) {
                byte[] dictionary = new byte[32768];
                random.nextBytes(dictionary);
                byte[] plain = new byte[1024 * 1024 + 1];
                for (int index = 0; index < plain.length; index++) plain[index] = dictionary[index % dictionary.length];
                byte[] profile = new byte[38];
                profile[0] = 5; // Use the generator's maximum 32-KiB window.
                profile[17] = (byte) workers;
                profile[33] = profile[34] = 1;
                seeds.add(rawSeed(plain, dictionary, 1, prefix, profile));
            }
        }
        for (int index = 0; index < 96; index++) {
            byte[] plain = new byte[index * 97];
            byte[] dictionary = new byte[index * 53];
            byte[] tail = new byte[128];
            random.nextBytes(plain);
            random.nextBytes(dictionary);
            random.nextBytes(tail);
            seeds.add(rawSeed(plain, dictionary, index % 2, index % 2, tail));
        }
        for (int length = 0; length <= 64; length++) {
            byte[] seed = new byte[length];
            random.nextBytes(seed);
            seeds.add(seed);
        }
        return List.copyOf(seeds);
    }

    /// Serializes source, dictionary and reversed parameter bytes using the upstream prefix-reservation convention.
    private static byte[] rawSeed(byte[] plain, byte[] dictionary, int smaller, int prefix, byte[] profile) {
        int total = plain.length + dictionary.length;
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        for (int index = width(total) - 1; index >= 0; index--) tail.write(plain.length >>> (index * 8));
        tail.write(smaller);
        tail.write(prefix);
        tail.writeBytes(profile);
        byte[] payload = Arrays.copyOf(plain, total);
        System.arraycopy(dictionary, 0, payload, plain.length, dictionary.length);
        return seed(payload, tail.toByteArray());
    }

    /// Creates training inputs spanning trainer failure, complete dictionaries and original parameter branches.
    private static @Unmodifiable List<byte[]> trainedSeeds() {
        List<byte[]> seeds = new ArrayList<>();
        Random random = new Random(0xd1c7_2071L);
        for (int size : new int[]{0, 1, 7, 8, 31, 256, 1024, 4096, 16384, 131073}) {
            for (int shape = 0; shape < 2; shape++) {
                byte[] plain = new byte[size];
                random.nextBytes(plain);
                if (shape == 1) for (int index = 256; index < size; index++) plain[index] = plain[index % 256];
                for (int prefix = 0; prefix < 2; prefix++) {
                    for (int type = 0; type < 3; type++) {
                        for (int load = 0; load < 2; load++) {
                            byte[] profile = new byte[38];
                            profile[7] = (byte) (seeds.size() % 9);
                            profile[8] = (byte) (seeds.size() % 2);
                            profile[10] = (byte) (seeds.size() / 2 % 2);
                            profile[17] = (byte) (seeds.size() % 3);
                            profile[33] = profile[34] = 1;
                            profile[36] = (byte) load;
                            seeds.add(trainedSeed(plain, load, prefix, type, profile, false));
                        }
                    }
                }
            }
        }
        for (int level = -3; level <= 19; level++) {
            for (int size : new int[]{0, 16384}) {
                byte[] plain = new byte[size];
                random.nextBytes(plain);
                for (int prefix = 0; prefix < 2; prefix++) {
                    seeds.add(trainedSeed(plain, prefix, prefix, 0, new byte[]{(byte) (level + 3), 1}, true));
                }
            }
        }
        for (int prefix = 0; prefix < 2; prefix++) {
            for (int workers = 0; workers <= 2; workers++) {
                byte[] plain = new byte[1024 * 1024 + 1];
                random.nextBytes(plain);
                for (int index = 16384; index < plain.length; index++) plain[index] = plain[index % 16384];
                byte[] profile = new byte[38];
                profile[0] = 5;
                profile[17] = (byte) workers;
                profile[33] = profile[34] = 1;
                seeds.add(trainedSeed(plain, 1, prefix, workers, profile, false));
            }
        }
        for (int index = 0; index < 32; index++) {
            byte[] plain = new byte[index * 997];
            byte[] profile = new byte[128];
            random.nextBytes(plain);
            random.nextBytes(profile);
            seeds.add(trainedSeed(plain, index % 2, index % 2, index % 3, profile, false));
        }
        for (int size = 0; size <= 64; size++) {
            byte[] exhausted = new byte[size];
            random.nextBytes(exhausted);
            seeds.add(exhausted);
        }
        return List.copyOf(seeds);
    }

    /// Retains the upstream capacity bit, one hundred trainer offsets and compression-branch parameters.
    private static byte[] trainedSeed(byte[] plain, int smaller, int prefix, int type, byte[] profile, boolean simple) {
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        tail.write(smaller);
        for (int sample = 0; sample < 100; sample++) {
            int offset = plain.length == 0 ? 0 : (int) ((long) sample * 127 % plain.length);
            for (int index = width(Math.max(plain.length, 1) - 1) - 1; index >= 0; index--) {
                tail.write(offset >>> (index * 8));
            }
        }
        tail.write(prefix);
        tail.write(simple ? 0 : 1);
        if (!simple) tail.write(type);
        tail.writeBytes(profile);
        return seed(plain, tail.toByteArray());
    }

    /// Combines valid, malformed, concatenated, skippable, truncated and arbitrary dictionary-decoder inputs.
    private static @Unmodifiable List<byte[]> decompressionSeeds() throws IOException {
        List<byte[]> frames = new ArrayList<>();
        Random random = new Random(0xd1c7_dec0L);
        try (var compressor = new ZstdCompressCtx()) {
            for (int size : new int[]{0, 1, 256, 4096, 131073}) {
                byte[] plain = new byte[size];
                random.nextBytes(plain);
                for (boolean contentSize : new boolean[]{false, true}) {
                    for (boolean checksum : new boolean[]{false, true}) {
                        frames.add(compressor.setContentSize(contentSize).setChecksum(checksum).compress(plain));
                    }
                }
            }
        }
        Path root = Path.of(Objects.requireNonNull(System.getProperty("arkivo.zstd.referenceDirectory")));
        for (String name : List.of("block-128k.zst", "empty-block.zst", "rle-first-block.zst", "zeroSeq_2B.zst")) {
            frames.add(Files.readAllBytes(root.resolve("tests/golden-decompression").resolve(name)));
        }
        for (String name : List.of("off0.bin.zst", "truncated_huff_state.zst", "zeroSeq_extraneous.zst")) {
            frames.add(Files.readAllBytes(root.resolve("tests/golden-decompression-errors").resolve(name)));
        }
        for (int id = 0; id < 16; id++) {
            byte[] skip = new byte[id + 8];
            random.nextBytes(skip);
            ByteArrayAccess.writeIntLittleEndian(skip, 0, 0x184d2a50 + id);
            ByteArrayAccess.writeIntLittleEndian(skip, 4, id);
            frames.add(skip);
        }
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        joined.writeBytes(frames.get(7));
        joined.writeBytes(frames.get(frames.size() - 1));
        joined.writeBytes(frames.get(8));
        frames.add(joined.toByteArray());
        frames.add(new byte[0]);
        List<byte[]> seeds = new ArrayList<>();
        for (byte[] frame : frames) {
            for (int capacity : new int[]{0, Math.min(1, frame.length * 10), frame.length, frame.length * 10}) {
                seeds.add(decompressionSeed(frame, capacity, 0, 0, 0));
                for (int first = 0; first < 3; first++) {
                    seeds.add(decompressionSeed(frame, capacity, 2, first, 0));
                    // This product covers all type/load combinations under either C argument evaluation order.
                    for (int second = 0; second < 3; second++) {
                        seeds.add(decompressionSeed(frame, capacity, 1, first, second));
                    }
                }
            }
            for (int cut : new int[]{0, Math.min(1, frame.length), frame.length / 2, Math.max(0, frame.length - 1)}) {
                seeds.add(decompressionSeed(Arrays.copyOf(frame, cut), cut * 10, 1, 1, 2));
            }
            if (frame.length != 0) {
                byte[] mutated = frame.clone();
                mutated[mutated.length - 1] ^= 0x40;
                seeds.add(decompressionSeed(mutated, mutated.length * 10, 2, 0, 0));
            }
        }
        for (int size = 0; size <= 64; size++) {
            byte[] exhausted = new byte[size];
            random.nextBytes(exhausted);
            seeds.add(exhausted);
        }
        for (int index = 0; index < 64; index++) {
            byte[] arbitrary = new byte[index * 37];
            random.nextBytes(arbitrary);
            seeds.add(decompressionSeed(arbitrary, arbitrary.length * 10, index % 3, index % 3, index % 2));
        }
        return List.copyOf(seeds);
    }

    /// Selects the original trainer offsets, decoder branch and output capacity without changing the frame bytes.
    private static byte[] decompressionSeed(byte[] frame, int capacity, int mode, int first, int second) {
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        for (int sample = 0; sample < 100; sample++) {
            int offset = frame.length == 0 ? 0 : (int) ((long) sample * 127 % frame.length);
            for (int index = width(Math.max(frame.length, 1) - 1) - 1; index >= 0; index--) {
                tail.write(offset >>> (index * 8));
            }
        }
        tail.write(mode == 0 ? 0 : 1);
        if (mode != 0) {
            tail.write(mode == 1 ? 0 : 1);
            tail.write(first);
            if (mode == 1) tail.write(second);
        }
        for (int index = width(frame.length * 10) - 1; index >= 0; index--) tail.write(capacity >>> (index * 8));
        return seed(frame, tail.toByteArray());
    }

    /// Appends parameters in reverse consumption order followed by the original prefix-reservation field.
    private static byte[] seed(byte[] payload, byte[] parameters) {
        int total = payload.length;
        int suffix = 1;
        while (suffix != width(total + parameters.length + suffix)) suffix = width(total + parameters.length + suffix);
        byte[] seed = Arrays.copyOf(payload, total + parameters.length + suffix);
        for (int index = 0; index < parameters.length; index++) seed[total + parameters.length - 1 - index] = parameters[index];
        for (int index = 0; index < suffix; index++) seed[total + parameters.length + index] = (byte) (parameters.length >>> (index * 8));
        assertEquals(total, new ZstdFuzzDataProducer(seed).reservePrefix());
        return seed;
    }

    /// Returns the number of bytes consumed by an inclusive zero-based parameter range.
    private static int width(int value) {
        int result = 0;
        for (; value != 0; value >>>= 8) result++;
        return result;
    }

    /// Records parameters consumed from the original tail before using all remaining bytes as source and dictionary.
    ///
    /// @param size remaining payload length
    /// @param type native auto, raw, or full interpretation (0, 1, or 2)
    /// @param load native copy or reference selection (0 or 1)
    /// @param prefix native loaded-dictionary or single-use prefix selection (0 or 1)
    @NotNullByDefault
    private record Input(int size, int type, int load, int prefix) {
    }

    /// Reads the original parameter order, including exhaustion defaults for inputs shorter than three bytes.
    private static Input input(byte[] seed) {
        ZstdFuzzDataProducer producer = new ZstdFuzzDataProducer(seed);
        int prefix = (int) producer.range(0, 1);
        int load = (int) producer.range(0, 1);
        int type = (int) producer.range(0, 2);
        return new Input(producer.remainingBytes(), type, load, prefix);
    }

    /// Creates the requested Java dictionary without changing source positions or limits.
    private static ZstdDictionary dictionary(int type, ByteBuffer bytes) {
        return switch (type) {
            case 0 -> ZstdDictionary.of(bytes);
            case 1 -> ZstdDictionary.rawContent(bytes);
            case 2 -> ZstdDictionary.fullDictionary(bytes);
            default -> throw new AssertionError(type);
        };
    }

    /// Encodes with bounded output while retaining source guards and the encoder's resettable dictionary state.
    private static byte[] encode(CompressionEncoder encoder, byte[] plain, int kind) throws IOException {
        ByteBuffer source = source(plain, kind);
        ByteBuffer storage = storage(257, kind);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        CodecOutcome outcome;
        do {
            ByteBuffer target = target(storage, 257);
            int before = source.position();
            outcome = encoder.encode(source, target);
            append(target, result);
            assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT);
            assertTrue(source.position() > before || target.position() > 3 || outcome == CodecOutcome.NEEDS_INPUT);
        } while (outcome != CodecOutcome.NEEDS_INPUT);
        checkSource(source, plain, plain.length + 3);
        do {
            ByteBuffer target = target(storage, 257);
            outcome = encoder.finish(target);
            append(target, result);
            assertTrue(outcome == CodecOutcome.FINISHED || outcome == CodecOutcome.NEEDS_OUTPUT);
            assertTrue(outcome == CodecOutcome.FINISHED || target.position() > 3);
        } while (outcome != CodecOutcome.FINISHED);
        return result.toByteArray();
    }

    /// Decodes fragmented input twice through the same configured decoder, preserving bytes after the frame.
    private static void decode(ZstdCodec codec, byte[] frame, byte[] plain, int kind) throws IOException {
        byte[] withTail = Arrays.copyOf(frame, frame.length + 7);
        Arrays.fill(withTail, frame.length, withTail.length, (byte) 0x37);
        ByteBuffer source = source(withTail, kind);
        ByteBuffer storage = storage(kind == 0 ? 31 : 4093, kind);
        try (var decoder = codec.newDecoder()) {
            for (int repeat = 0; repeat < 2; repeat++) {
                source.position(3);
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                for (int call = 0; ; call++) {
                    assertTrue(call <= frame.length + plain.length + 32);
                    source.limit(Math.min(3 + withTail.length, source.position() + (kind == 0 ? 7 : 8191)));
                    int before = source.position();
                    int limit = source.limit();
                    ByteBuffer target = target(storage, call == 0 ? 0 : storage.capacity() - 6);
                    CodecOutcome outcome = decoder.decode(source, target);
                    append(target, result);
                    assertEquals(limit, source.limit());
                    assertTrue(result.size() <= plain.length);
                    assertTrue(source.position() <= 3 + frame.length);
                    if (outcome == CodecOutcome.FINISHED) break;
                    assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT);
                    if (call != 0) assertTrue(source.position() > before || target.position() > 3);
                }
                assertArrayEquals(plain, result.toByteArray());
                source.limit(3 + withTail.length);
                checkSource(source, withTail, 3 + frame.length);
                decoder.reset();
            }
        }
    }

    /// Allocates reusable output storage with guard space on both sides.
    private static ByteBuffer storage(int capacity, int kind) {
        return kind == 1 ? ByteBuffer.allocateDirect(capacity + 6) : ByteBuffer.allocate(capacity + 6);
    }

    /// Creates a bounded target view with guard bytes outside the writable region.
    private static ByteBuffer target(ByteBuffer storage, int capacity) {
        ByteBuffer target = storage.duplicate().clear().limit(capacity + 6).slice();
        for (int index = 0; index < 3; index++) {
            target.put(index, (byte) 0x5a);
            target.put(capacity + 3 + index, (byte) 0x5a);
        }
        return target.position(3).limit(capacity + 3);
    }

    /// Creates guarded heap, direct, or read-only input with nonzero position.
    private static ByteBuffer source(byte[] bytes, int kind) {
        ByteBuffer source = target(storage(bytes.length, kind), bytes.length);
        source.put(bytes).position(3);
        return kind == 2 ? source.asReadOnlyBuffer() : source;
    }

    /// Checks the output bounds and appends only bytes produced by the last call.
    private static void append(ByteBuffer target, ByteArrayOutputStream output) {
        guards(target);
        byte[] bytes = new byte[target.position() - 3];
        target.duplicate().position(3).get(bytes);
        output.writeBytes(bytes);
    }

    /// Verifies sentinel bytes, unchanged limits and valid positions.
    private static void guards(ByteBuffer buffer) {
        assertEquals(buffer.capacity() - 3, buffer.limit());
        assertTrue(buffer.position() >= 3 && buffer.position() <= buffer.limit());
        ByteBuffer view = buffer.duplicate().clear();
        for (int index = 0; index < 3; index++) {
            assertEquals((byte) 0x5a, view.get(index));
            assertEquals((byte) 0x5a, view.get(view.capacity() - 1 - index));
        }
    }

    /// Checks the exact consumed extent and all unchanged input bytes, including the frame tail.
    private static void checkSource(ByteBuffer source, byte[] expected, int position) {
        guards(source);
        assertEquals(position, source.position());
        @UnmodifiableView ByteBuffer actual = source.asReadOnlyBuffer().position(3);
        assertEquals(-1, ByteBuffer.wrap(expected).mismatch(actual));
    }

    /// Generates every loading-mode combination and finite truncation, mutation and exhausted-tail inputs.
    private static @Unmodifiable List<byte[]> seeds() throws IOException {
        List<byte[]> seeds = new ArrayList<>();
        Random random = new Random(0xd1c7_10adL);
        for (int size : new int[]{0, 1, 2, 3, 4, 7, 8, 9, 15, 16, 31, 32, 127, 128, 255, 256, 1023, 1024, 4096, 32768}) {
            for (int shape = 0; shape < 3; shape++) {
                byte[] plain = new byte[size];
                if (shape != 0) random.nextBytes(plain);
                if (shape == 2) for (int index = 16; index < size; index++) plain[index] = plain[index % 16];
                addModes(seeds, plain);
            }
        }
        Path root = Path.of(Objects.requireNonNull(System.getProperty("arkivo.zstd.referenceDirectory")));
        byte[] dictionary = Files.readAllBytes(root.resolve("tests/golden-dictionaries/http-dict-missing-symbols"));
        addModes(seeds, dictionary);
        for (int size = 8; size < Math.min(dictionary.length, 256); size++) {
            addModes(seeds, Arrays.copyOf(dictionary, size));
        }
        for (int index = 0; index < Math.min(dictionary.length, 128); index++) {
            byte[] mutated = dictionary.clone();
            mutated[index] ^= (byte) (1 << (index % 8));
            addModes(seeds, mutated);
        }
        byte[] zeroId = dictionary.clone();
        ByteArrayAccess.writeIntLittleEndian(zeroId, 4, 0);
        addModes(seeds, zeroId);
        for (int size = 0; size <= 64; size++) {
            byte[] seed = new byte[size];
            random.nextBytes(seed);
            seeds.add(seed);
        }
        return List.copyOf(seeds);
    }

    /// Appends the original three one-byte parameters in reverse consumption order.
    private static void addModes(List<byte[]> seeds, byte[] plain) {
        for (int type = 0; type < 3; type++) {
            for (int load = 0; load < 2; load++) {
                for (int prefix = 0; prefix < 2; prefix++) {
                    byte[] seed = Arrays.copyOf(plain, plain.length + 3);
                    seed[plain.length] = (byte) type;
                    seed[plain.length + 1] = (byte) load;
                    seed[plain.length + 2] = (byte) prefix;
                    seeds.add(seed);
                }
            }
        }
    }

    /// Encodes a length-prefixed request field, retaining an explicit token for an empty input.
    private static String bytes(byte[] value) {
        return value.length + " " + (value.length == 0 ? "-" : HexFormat.of().formatHex(value));
    }

    /// Decodes one response byte field.
    private static byte[] hex(String value) {
        return value.equals("-") ? new byte[0] : HexFormat.of().parseHex(value);
    }

    /// Resolves the required-in-CI reference executable.
    private static String executable(String target) {
        String variable = "ARKIVO_ZSTD_" + target + "_EXECUTABLE";
        @Nullable String value = System.getenv(variable);
        boolean available = value != null && Files.isRegularFile(Path.of(value));
        if (Boolean.parseBoolean(System.getenv("ARKIVO_REQUIRE_ZSTD_" + target))) {
            assertTrue(available, "Set " + variable + " to the official dictionary reference");
        }
        assumeTrue(available, "Official dictionary reference is not configured: " + variable);
        return Path.of(Objects.requireNonNull(value)).toAbsolutePath().toString();
    }

    /// Runs a bounded batch outside the source tree and retains diagnostic files on failure.
    private List<String> run(String executable, List<String> requests, String name) throws IOException, InterruptedException {
        return run(executable, requests, name, false);
    }

    /// Selects fresh or retained native contexts without changing any seed's original parameter sequence.
    private List<String> run(String executable, List<String> requests, String name, boolean reuse) throws IOException, InterruptedException {
        Path input = directory.resolve(name + ".input");
        Path output = directory.resolve(name + ".output");
        Path errors = directory.resolve(name + ".errors");
        Files.write(input, requests, StandardCharsets.US_ASCII);
        ProcessBuilder builder = reuse ? new ProcessBuilder(executable, "--reuse") : new ProcessBuilder(executable);
        Process process = builder.redirectInput(input.toFile())
                .redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) fail("Official dictionary loader timed out");
            assertEquals(0, process.exitValue(), Files.readString(errors));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Official dictionary loader did not terminate");
            }
        }
        return Files.readAllLines(output, StandardCharsets.US_ASCII);
    }
}
