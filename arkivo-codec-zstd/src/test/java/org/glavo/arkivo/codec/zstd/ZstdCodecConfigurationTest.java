// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.EncodingOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the immutable Zstandard codec configuration contract and its complete validation surface.
@NotNullByDefault
public final class ZstdCodecConfigurationTest {
    /// Verifies that builds are immutable snapshots and `toBuilder` preserves every setting.
    @Test
    public void builderCreatesIndependentCompleteSnapshots() {
        ZstdDictionary dictionary = ZstdDictionary.rawContent(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        ZstdCodec.Builder builder = fullyConfiguredBuilder(dictionary);

        ZstdCodec configured = builder.build();
        builder.compressionLevel(ZstdCodec.DEFAULT_COMPRESSION_LEVEL)
                .withoutDictionary()
                .frameChecksum(false)
                .verifyChecksums(true)
                .workerCount(0)
                .windowLog(0L)
                .hashLog(0L)
                .chainLog(0L)
                .searchLog(0L)
                .minimumMatch(0L)
                .targetLength(0L)
                .automaticStrategy()
                .jobSize(0L)
                .overlapLog(0)
                .contentSize(true)
                .dictionaryId(true)
                .longDistanceMatching(false)
                .longDistanceHashLog(0L)
                .longDistanceMinimumMatch(0L)
                .longDistanceBucketSizeLog(0L)
                .longDistanceHashRateLog(0L)
                .frameFormat(ZstdFrameFormat.STANDARD)
                .maximumOutputSize(201L)
                .maximumWindowSize(202L)
                .maximumMemorySize(203L);

        assertFullyConfigured(configured, dictionary);
        ZstdCodec copy = configured.toBuilder().build();
        assertNotSame(configured, copy);
        assertFullyConfigured(copy, dictionary);

        ZstdCodec changed = builder.build();
        assertEquals(ZstdCodec.DEFAULT_COMPRESSION_LEVEL, changed.compressionLevel());
        assertNull(changed.dictionary());
        assertFalse(changed.emitsFrameChecksum());
        assertTrue(changed.verifiesChecksums());
        assertEquals(0, changed.workerCount());
        assertEquals(0, changed.windowLog());
        assertEquals(0, changed.hashLog());
        assertEquals(0, changed.chainLog());
        assertEquals(0, changed.searchLog());
        assertEquals(0, changed.minimumMatch());
        assertEquals(0, changed.targetLength());
        assertNull(changed.strategy());
        assertEquals(0, changed.jobSize());
        assertEquals(0, changed.overlapLog());
        assertTrue(changed.emitsContentSize());
        assertTrue(changed.emitsDictionaryId());
        assertFalse(changed.usesLongDistanceMatching());
        assertEquals(0, changed.longDistanceHashLog());
        assertEquals(0, changed.longDistanceMinimumMatch());
        assertEquals(0, changed.longDistanceBucketSizeLog());
        assertEquals(0, changed.longDistanceHashRateLog());
        assertEquals(ZstdFrameFormat.STANDARD, changed.frameFormat());
        assertEquals(201L, changed.maximumOutputSize());
        assertEquals(202L, changed.maximumWindowSize());
        assertEquals(203L, changed.maximumMemorySize());
    }

    /// Verifies the byte-array builder overload retains an immutable dictionary snapshot.
    @Test
    public void builderCopiesDictionaryBytes() {
        byte[] source = {1, 2, 3, 4, 5, 6, 7, 8};
        ZstdCodec codec = ZstdCodec.builder().dictionary(source).build();

        source[0] = 99;

        assertArrayEquals(
                new byte[]{1, 2, 3, 4, 5, 6, 7, 8},
                java.util.Objects.requireNonNull(codec.dictionary()).bytes()
        );
    }

    /// Verifies no-op withers preserve identity while changed values produce independent configurations.
    @Test
    public void withersArePersistentAndCanonicalizeNoOps() {
        assertSame(ZstdCodec.DEFAULT, ZstdCodec.DEFAULT.withoutDictionary());
        ZstdDictionary dictionary = ZstdDictionary.rawContent(new byte[]{8, 7, 6, 5, 4, 3, 2, 1});
        ZstdCodec codec = fullyConfiguredBuilder(dictionary).build();

        assertSame(codec, codec.withCompressionLevel(-7L));
        assertSame(codec, codec.withDictionary(dictionary));
        assertSame(codec, codec.withFrameChecksum(true));
        assertSame(codec, codec.withVerifyChecksums(false));
        assertSame(codec, codec.withFrameFormat(ZstdFrameFormat.MAGICLESS));
        assertSame(codec, codec.withMaximumOutputSize(101L));
        assertSame(codec, codec.withMaximumWindowSize(102L));
        assertSame(codec, codec.withMaximumMemorySize(103L));

        assertNotSame(codec, codec.withCompressionLevel(-6L));
        assertNotSame(codec, codec.withFrameChecksum(false));
        assertNotSame(codec, codec.withVerifyChecksums(true));
        assertNotSame(codec, codec.withFrameFormat(ZstdFrameFormat.STANDARD));
        assertNotSame(codec, codec.withMaximumOutputSize(111L));
        assertNotSame(codec, codec.withMaximumWindowSize(112L));
        assertNotSame(codec, codec.withMaximumMemorySize(113L));
        assertNull(codec.withoutDictionary().dictionary());
        assertSame(dictionary, codec.withoutDictionary().withDictionary(dictionary).dictionary());

        byte[] dictionaryBytes = {11, 12, 13, 14, 15, 16, 17, 18};
        ZstdCodec fromBytes = ZstdCodec.DEFAULT.withDictionary(dictionaryBytes);
        dictionaryBytes[0] = 99;
        assertArrayEquals(
                new byte[]{11, 12, 13, 14, 15, 16, 17, 18},
                java.util.Objects.requireNonNull(fromBytes.dictionary()).bytes()
        );
        assertFullyConfigured(codec, dictionary);
    }

    /// Verifies every inclusive numeric endpoint and the zero-valued automatic settings.
    @Test
    public void builderAcceptsEveryDocumentedBoundary() {
        ZstdCodec minimum = ZstdCodec.builder()
                .compressionLevel(ZstdCodec.MINIMUM_COMPRESSION_LEVEL)
                .workerCount(0)
                .windowLog(ZstdCodec.MINIMUM_WINDOW_LOG)
                .hashLog(ZstdCodec.MINIMUM_HASH_LOG)
                .chainLog(ZstdCodec.MINIMUM_CHAIN_LOG)
                .searchLog(ZstdCodec.MINIMUM_SEARCH_LOG)
                .minimumMatch(ZstdCodec.MINIMUM_MATCH_LENGTH)
                .targetLength(0L)
                .strategy(ZstdStrategy.FAST)
                .automaticStrategy()
                .jobSize(0L)
                .overlapLog(0)
                .longDistanceHashLog(ZstdCodec.MINIMUM_LONG_DISTANCE_HASH_LOG)
                .longDistanceMinimumMatch(ZstdCodec.MINIMUM_LONG_DISTANCE_MATCH_LENGTH)
                .longDistanceBucketSizeLog(ZstdCodec.MINIMUM_LONG_DISTANCE_BUCKET_SIZE_LOG)
                .longDistanceHashRateLog(ZstdCodec.MINIMUM_LONG_DISTANCE_HASH_RATE_LOG)
                .maximumOutputSize(0L)
                .maximumWindowSize(0L)
                .maximumMemorySize(0L)
                .build();
        assertEquals(ZstdCodec.MINIMUM_COMPRESSION_LEVEL, minimum.compressionLevel());
        assertEquals(ZstdCodec.MINIMUM_WINDOW_LOG, minimum.windowLog());
        assertEquals(ZstdCodec.MINIMUM_HASH_LOG, minimum.hashLog());
        assertEquals(ZstdCodec.MINIMUM_CHAIN_LOG, minimum.chainLog());
        assertEquals(ZstdCodec.MINIMUM_SEARCH_LOG, minimum.searchLog());
        assertEquals(ZstdCodec.MINIMUM_MATCH_LENGTH, minimum.minimumMatch());
        assertNull(minimum.strategy());
        assertEquals(ZstdCodec.MINIMUM_LONG_DISTANCE_HASH_LOG, minimum.longDistanceHashLog());
        assertEquals(ZstdCodec.MINIMUM_LONG_DISTANCE_MATCH_LENGTH, minimum.longDistanceMinimumMatch());
        assertEquals(ZstdCodec.MINIMUM_LONG_DISTANCE_BUCKET_SIZE_LOG, minimum.longDistanceBucketSizeLog());
        assertEquals(ZstdCodec.MINIMUM_LONG_DISTANCE_HASH_RATE_LOG, minimum.longDistanceHashRateLog());
        assertEquals(0L, minimum.maximumOutputSize());
        assertEquals(0L, minimum.maximumWindowSize());
        assertEquals(0L, minimum.maximumMemorySize());

        ZstdCodec maximum = ZstdCodec.builder()
                .compressionLevel(ZstdCodec.MAXIMUM_COMPRESSION_LEVEL)
                .workerCount(ZstdCodec.MAXIMUM_WORKER_COUNT)
                .windowLog(ZstdCodec.MAXIMUM_WINDOW_LOG)
                .hashLog(ZstdCodec.MAXIMUM_HASH_LOG)
                .chainLog(ZstdCodec.MAXIMUM_CHAIN_LOG)
                .searchLog(ZstdCodec.MAXIMUM_SEARCH_LOG)
                .minimumMatch(ZstdCodec.MAXIMUM_MATCH_LENGTH)
                .targetLength(Integer.MAX_VALUE)
                .jobSize(Integer.MAX_VALUE)
                .overlapLog(9)
                .longDistanceHashLog(ZstdCodec.MAXIMUM_LONG_DISTANCE_HASH_LOG)
                .longDistanceMinimumMatch(ZstdCodec.MAXIMUM_LONG_DISTANCE_MATCH_LENGTH)
                .longDistanceBucketSizeLog(ZstdCodec.MAXIMUM_LONG_DISTANCE_BUCKET_SIZE_LOG)
                .longDistanceHashRateLog(ZstdCodec.MAXIMUM_LONG_DISTANCE_HASH_RATE_LOG)
                .build();
        assertEquals(ZstdCodec.MAXIMUM_COMPRESSION_LEVEL, maximum.compressionLevel());
        assertEquals(ZstdCodec.MAXIMUM_WORKER_COUNT, maximum.workerCount());
        assertEquals(ZstdCodec.MAXIMUM_WINDOW_LOG, maximum.windowLog());
        assertEquals(ZstdCodec.MAXIMUM_HASH_LOG, maximum.hashLog());
        assertEquals(ZstdCodec.MAXIMUM_CHAIN_LOG, maximum.chainLog());
        assertEquals(ZstdCodec.MAXIMUM_SEARCH_LOG, maximum.searchLog());
        assertEquals(ZstdCodec.MAXIMUM_MATCH_LENGTH, maximum.minimumMatch());
        assertEquals(Integer.MAX_VALUE, maximum.targetLength());
        assertEquals(Integer.MAX_VALUE, maximum.jobSize());
        assertEquals(9, maximum.overlapLog());
        assertEquals(ZstdCodec.MAXIMUM_LONG_DISTANCE_HASH_LOG, maximum.longDistanceHashLog());
        assertEquals(ZstdCodec.MAXIMUM_LONG_DISTANCE_MATCH_LENGTH, maximum.longDistanceMinimumMatch());
        assertEquals(ZstdCodec.MAXIMUM_LONG_DISTANCE_BUCKET_SIZE_LOG, maximum.longDistanceBucketSizeLog());
        assertEquals(ZstdCodec.MAXIMUM_LONG_DISTANCE_HASH_RATE_LOG, maximum.longDistanceHashRateLog());

        ZstdCodec automatic = ZstdCodec.builder()
                .windowLog(0L)
                .hashLog(0L)
                .chainLog(0L)
                .searchLog(0L)
                .minimumMatch(0L)
                .longDistanceHashLog(0L)
                .longDistanceMinimumMatch(0L)
                .longDistanceBucketSizeLog(0L)
                .longDistanceHashRateLog(0L)
                .build();
        assertEquals(0, automatic.windowLog());
        assertEquals(0, automatic.hashLog());
        assertEquals(0, automatic.chainLog());
        assertEquals(0, automatic.searchLog());
        assertEquals(0, automatic.minimumMatch());
        assertEquals(0, automatic.longDistanceHashLog());
        assertEquals(0, automatic.longDistanceMinimumMatch());
        assertEquals(0, automatic.longDistanceBucketSizeLog());
        assertEquals(0, automatic.longDistanceHashRateLog());
    }

    /// Verifies that every builder parameter rejects values immediately outside its documented domain.
    @Test
    public void builderRejectsEveryInvalidDomain() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ZstdCodec.builder().compressionLevel((long) ZstdCodec.MINIMUM_COMPRESSION_LEVEL - 1L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ZstdCodec.builder().compressionLevel((long) ZstdCodec.MAXIMUM_COMPRESSION_LEVEL + 1L)
        );
        assertThrows(IllegalArgumentException.class, () -> ZstdCodec.DEFAULT.withCompressionLevel(Long.MIN_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ZstdCodec.DEFAULT.withCompressionLevel(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ZstdCodec.builder().workerCount(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> ZstdCodec.builder().workerCount(ZstdCodec.MAXIMUM_WORKER_COUNT + 1)
        );
        assertAutomaticBoundsRejected(
                () -> ZstdCodec.builder().windowLog((long) ZstdCodec.MINIMUM_WINDOW_LOG - 1L),
                () -> ZstdCodec.builder().windowLog((long) ZstdCodec.MAXIMUM_WINDOW_LOG + 1L)
        );
        assertAutomaticBoundsRejected(
                () -> ZstdCodec.builder().hashLog((long) ZstdCodec.MINIMUM_HASH_LOG - 1L),
                () -> ZstdCodec.builder().hashLog((long) ZstdCodec.MAXIMUM_HASH_LOG + 1L)
        );
        assertAutomaticBoundsRejected(
                () -> ZstdCodec.builder().chainLog((long) ZstdCodec.MINIMUM_CHAIN_LOG - 1L),
                () -> ZstdCodec.builder().chainLog((long) ZstdCodec.MAXIMUM_CHAIN_LOG + 1L)
        );
        assertAutomaticBoundsRejected(
                () -> ZstdCodec.builder().searchLog(-1L),
                () -> ZstdCodec.builder().searchLog((long) ZstdCodec.MAXIMUM_SEARCH_LOG + 1L)
        );
        assertAutomaticBoundsRejected(
                () -> ZstdCodec.builder().minimumMatch((long) ZstdCodec.MINIMUM_MATCH_LENGTH - 1L),
                () -> ZstdCodec.builder().minimumMatch((long) ZstdCodec.MAXIMUM_MATCH_LENGTH + 1L)
        );
        assertAutomaticBoundsRejected(
                () -> ZstdCodec.builder().longDistanceHashLog(
                        (long) ZstdCodec.MINIMUM_LONG_DISTANCE_HASH_LOG - 1L
                ),
                () -> ZstdCodec.builder().longDistanceHashLog(
                        (long) ZstdCodec.MAXIMUM_LONG_DISTANCE_HASH_LOG + 1L
                )
        );
        assertAutomaticBoundsRejected(
                () -> ZstdCodec.builder().longDistanceMinimumMatch(
                        (long) ZstdCodec.MINIMUM_LONG_DISTANCE_MATCH_LENGTH - 1L
                ),
                () -> ZstdCodec.builder().longDistanceMinimumMatch(
                        (long) ZstdCodec.MAXIMUM_LONG_DISTANCE_MATCH_LENGTH + 1L
                )
        );
        assertAutomaticBoundsRejected(
                () -> ZstdCodec.builder().longDistanceBucketSizeLog(-1L),
                () -> ZstdCodec.builder().longDistanceBucketSizeLog(
                        (long) ZstdCodec.MAXIMUM_LONG_DISTANCE_BUCKET_SIZE_LOG + 1L
                )
        );
        assertAutomaticBoundsRejected(
                () -> ZstdCodec.builder().longDistanceHashRateLog(-1L),
                () -> ZstdCodec.builder().longDistanceHashRateLog(
                        (long) ZstdCodec.MAXIMUM_LONG_DISTANCE_HASH_RATE_LOG + 1L
                )
        );
        assertThrows(IllegalArgumentException.class, () -> ZstdCodec.builder().targetLength(-1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> ZstdCodec.builder().targetLength((long) Integer.MAX_VALUE + 1L)
        );
        assertThrows(IllegalArgumentException.class, () -> ZstdCodec.builder().jobSize(-1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> ZstdCodec.builder().jobSize((long) Integer.MAX_VALUE + 1L)
        );
        assertThrows(IllegalArgumentException.class, () -> ZstdCodec.builder().overlapLog(-1));
        assertThrows(IllegalArgumentException.class, () -> ZstdCodec.builder().overlapLog(10));
        assertThrows(IllegalArgumentException.class, () -> ZstdCodec.builder().maximumOutputSize(-2L));
        assertThrows(IllegalArgumentException.class, () -> ZstdCodec.builder().maximumWindowSize(-2L));
        assertThrows(IllegalArgumentException.class, () -> ZstdCodec.builder().maximumMemorySize(-2L));
        assertThrows(NullPointerException.class, () -> ZstdCodec.builder().dictionary((ZstdDictionary) null));
        assertThrows(NullPointerException.class, () -> ZstdCodec.builder().dictionary((byte[]) null));
        assertThrows(NullPointerException.class, () -> ZstdCodec.builder().strategy(null));
        assertThrows(NullPointerException.class, () -> ZstdCodec.builder().frameFormat(null));
        assertThrows(NullPointerException.class, () -> ZstdCodec.DEFAULT.withDictionary((ZstdDictionary) null));
        assertThrows(NullPointerException.class, () -> ZstdCodec.DEFAULT.withDictionary((byte[]) null));
        assertThrows(NullPointerException.class, () -> ZstdCodec.DEFAULT.withFrameFormat(null));
        assertThrows(
                NullPointerException.class,
                () -> ZstdCodec.DEFAULT.newEncoder((EncodingOptions) null)
        );
    }

    /// Verifies configuration-independent metadata and saturating compressed-size bounds.
    @Test
    public void metadataAndSizeBoundsAreStable() {
        ZstdCodec codec = ZstdCodec.DEFAULT;
        assertSame(ZstdFormat.instance(), codec.format());
        assertEquals(ZstdCodec.MINIMUM_COMPRESSION_LEVEL, codec.minimumCompressionLevel());
        assertEquals(ZstdCodec.MAXIMUM_COMPRESSION_LEVEL, codec.maximumCompressionLevel());
        assertEquals(ZstdCodec.DEFAULT_COMPRESSION_LEVEL, codec.defaultCompressionLevel());
        assertTrue(codec.supportsSeekableEncoding());
        assertFalse(codec.withFrameFormat(ZstdFrameFormat.MAGICLESS).supportsSeekableEncoding());
        assertEquals(64L, codec.maxCompressedSize(0L));
        assertEquals(Long.MAX_VALUE, codec.maxCompressedSize(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> codec.maxCompressedSize(-1L));
        assertEquals(CompressionCodec.UNLIMITED_SIZE, codec.maximumOutputSize());
        assertEquals(CompressionCodec.UNLIMITED_SIZE, codec.maximumWindowSize());
        assertEquals(CompressionCodec.UNLIMITED_SIZE, codec.maximumMemorySize());
    }

    /// Returns a builder with a nondefault value selected for every configurable field.
    private static ZstdCodec.Builder fullyConfiguredBuilder(ZstdDictionary dictionary) {
        return ZstdCodec.builder()
                .compressionLevel(-7L)
                .dictionary(dictionary)
                .frameChecksum(true)
                .verifyChecksums(false)
                .workerCount(2)
                .windowLog(20L)
                .hashLog(17L)
                .chainLog(16L)
                .searchLog(5L)
                .minimumMatch(4L)
                .targetLength(64L)
                .strategy(ZstdStrategy.BT_ULTRA)
                .jobSize(524_288L)
                .overlapLog(7)
                .contentSize(false)
                .dictionaryId(false)
                .longDistanceMatching(true)
                .longDistanceHashLog(18L)
                .longDistanceMinimumMatch(32L)
                .longDistanceBucketSizeLog(4L)
                .longDistanceHashRateLog(2L)
                .frameFormat(ZstdFrameFormat.MAGICLESS)
                .maximumOutputSize(101L)
                .maximumWindowSize(102L)
                .maximumMemorySize(103L);
    }

    /// Asserts every nondefault codec setting used by snapshot tests.
    private static void assertFullyConfigured(ZstdCodec codec, ZstdDictionary dictionary) {
        assertEquals(-7L, codec.compressionLevel());
        assertSame(dictionary, codec.dictionary());
        assertTrue(codec.emitsFrameChecksum());
        assertFalse(codec.verifiesChecksums());
        assertEquals(2, codec.workerCount());
        assertEquals(20, codec.windowLog());
        assertEquals(17, codec.hashLog());
        assertEquals(16, codec.chainLog());
        assertEquals(5, codec.searchLog());
        assertEquals(4, codec.minimumMatch());
        assertEquals(64, codec.targetLength());
        assertEquals(ZstdStrategy.BT_ULTRA, codec.strategy());
        assertEquals(524_288, codec.jobSize());
        assertEquals(7, codec.overlapLog());
        assertFalse(codec.emitsContentSize());
        assertFalse(codec.emitsDictionaryId());
        assertTrue(codec.usesLongDistanceMatching());
        assertEquals(18, codec.longDistanceHashLog());
        assertEquals(32, codec.longDistanceMinimumMatch());
        assertEquals(4, codec.longDistanceBucketSizeLog());
        assertEquals(2, codec.longDistanceHashRateLog());
        assertEquals(ZstdFrameFormat.MAGICLESS, codec.frameFormat());
        assertEquals(101L, codec.maximumOutputSize());
        assertEquals(102L, codec.maximumWindowSize());
        assertEquals(103L, codec.maximumMemorySize());
    }

    /// Asserts that both lower and upper nonzero values outside an automatic-or-bounded domain fail.
    private static void assertAutomaticBoundsRejected(Runnable belowMinimum, Runnable aboveMaximum) {
        assertThrows(IllegalArgumentException.class, belowMinimum::run);
        assertThrows(IllegalArgumentException.class, aboveMaximum::run);
    }
}
