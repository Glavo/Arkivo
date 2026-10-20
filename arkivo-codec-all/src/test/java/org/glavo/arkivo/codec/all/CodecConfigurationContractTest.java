// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.bzip2.BZip2Codec;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies immutable resource limits and compression-level capabilities without constructing codec engines.
@NotNullByDefault
final class CodecConfigurationContractTest {
    /// Returns every installed codec as an independently reported case.
    private static Stream<Named<CompressionCodec<?>>> codecs() {
        return CompressionFormats.installed().stream().map(format -> Named.of(format.name(), format.defaultCodec()));
    }

    /// Selects implementations of the common compression-level capability.
    private static Stream<Named<CompressionCodec<?>>> leveledCodecs() {
        return codecs().filter(codec -> codec.getPayload() instanceof CompressionCodec.LevelConfigurable<?>);
    }

    /// Resource limits compose independently and do not modify the original codec.
    @ParameterizedTest(name = "{0}")
    @MethodSource("codecs")
    void configuresReusableDecodingLimits(CompressionCodec<?> codec) {
        long originalOutputSize = codec.maximumOutputSize();
        long originalWindowSize = codec.maximumWindowSize();
        long originalMemorySize = codec.maximumMemorySize();
        assertLimits(codec, CompressionCodec.UNLIMITED_SIZE,
                CompressionCodec.UNLIMITED_SIZE, CompressionCodec.UNLIMITED_SIZE);
        CompressionCodec<?> configured = codec.withMaximumOutputSize(123L)
                .withMaximumWindowSize(456L).withMaximumMemorySize(789L);

        assertLimits(configured, 123L, 456L, 789L);
        assertSame(configured, configured.withMaximumOutputSize(123L));
        assertSame(configured, configured.withMaximumWindowSize(456L));
        assertSame(configured, configured.withMaximumMemorySize(789L));
        assertLimits(codec, originalOutputSize, originalWindowSize, originalMemorySize);
        for (long invalid : new long[]{-2L, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> codec.withMaximumOutputSize(invalid));
            assertThrows(IllegalArgumentException.class, () -> codec.withMaximumWindowSize(invalid));
            assertThrows(IllegalArgumentException.class, () -> codec.withMaximumMemorySize(invalid));
        }
        for (long limit : new long[]{CompressionCodec.UNLIMITED_SIZE, 0L, Long.MAX_VALUE}) {
            assertLimits(configured.withMaximumOutputSize(limit), limit, 456L, 789L);
            assertLimits(configured.withMaximumWindowSize(limit), 123L, limit, 789L);
            assertLimits(configured.withMaximumMemorySize(limit), 123L, 456L, limit);
            assertLimits(configured, 123L, 456L, 789L);
        }
    }

    /// Level endpoints and defaults remain valid when composed with independent decoder limits.
    @ParameterizedTest(name = "{0}")
    @MethodSource("leveledCodecs")
    void composesCompressionLevels(CompressionCodec.LevelConfigurable<?> codec) {
        long original = codec.compressionLevel();
        long minimum = codec.minimumCompressionLevel();
        long maximum = codec.maximumCompressionLevel();
        long defaultLevel = codec.defaultCompressionLevel();
        assertTrue(minimum <= defaultLevel && defaultLevel <= maximum);
        assertEquals(defaultLevel, original);
        CompressionCodec.LevelConfigurable<?> limited = assertInstanceOf(CompressionCodec.LevelConfigurable.class,
                codec.withMaximumOutputSize(123L).withMaximumWindowSize(456L).withMaximumMemorySize(789L));
        for (long level : new long[]{minimum, defaultLevel, maximum}) {
            CompressionCodec.LevelConfigurable<?> configured = assertInstanceOf(
                    CompressionCodec.LevelConfigurable.class, limited.withCompressionLevel(level));
            assertSame(codec.format(), configured.format());
            assertEquals(codec.getClass(), configured.getClass());
            assertEquals(level, configured.compressionLevel());
            assertEquals(minimum, configured.minimumCompressionLevel());
            assertEquals(maximum, configured.maximumCompressionLevel());
            assertEquals(defaultLevel, configured.defaultCompressionLevel());
            assertLimits(configured, 123L, 456L, 789L);
            CompressionCodec.LevelConfigurable<?> changed = assertInstanceOf(CompressionCodec.LevelConfigurable.class,
                    configured.withMaximumOutputSize(321L).withMaximumWindowSize(654L).withMaximumMemorySize(987L));
            assertEquals(level, changed.compressionLevel());
            assertLimits(changed, 321L, 654L, 987L);
            assertLimits(configured, 123L, 456L, 789L);
            assertEquals(original, codec.compressionLevel());
            assertEquals(original, limited.compressionLevel());
        }
        if (minimum > Long.MIN_VALUE) {
            assertThrows(IllegalArgumentException.class, () -> limited.withCompressionLevel(minimum - 1L));
        }
        if (maximum < Long.MAX_VALUE) {
            assertThrows(IllegalArgumentException.class, () -> limited.withCompressionLevel(maximum + 1L));
        }
    }

    /// Configuration through the capability interface retains the compile-time concrete return type.
    @Test
    void preservesConcreteTypeAcrossCapabilityConfiguration() {
        BZip2Codec configured = withMinimumCompressionLevel(BZip2Codec.DEFAULT);
        assertEquals(BZip2Codec.MINIMUM_COMPRESSION_LEVEL, configured.compressionLevel());
    }

    /// Returns the same self type after setting the lowest supported level.
    private static <C extends CompressionCodec<C>> C withMinimumCompressionLevel(
            CompressionCodec.LevelConfigurable<C> codec) {
        return codec.withCompressionLevel(codec.minimumCompressionLevel());
    }

    /// Checks all three independent resource limits.
    private static void assertLimits(CompressionCodec<?> codec, long output, long window, long memory) {
        assertEquals(output, codec.maximumOutputSize());
        assertEquals(window, codec.maximumWindowSize());
        assertEquals(memory, codec.maximumMemorySize());
    }
}
