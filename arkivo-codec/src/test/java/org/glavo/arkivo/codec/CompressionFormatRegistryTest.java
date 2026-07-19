// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies immutable compression format registry discovery and validation.
@NotNullByDefault
public final class CompressionFormatRegistryTest {
    /// Verifies names, aliases, format order, default codecs, and required lookup.
    @Test
    void indexesFormats() {
        TestFormat alpha = new TestFormat("alpha", List.of("a"), 1, 0x11);
        TestFormat beta = new TestFormat("beta", List.of("b"), 2, 0x2233);
        CompressionFormatRegistry registry = CompressionFormatRegistry.fromFormats(List.of(alpha, alpha, beta));

        assertEquals(List.of(alpha, beta), registry.formats());
        assertSame(alpha, registry.find("ALPHA"));
        assertSame(alpha, registry.find("A"));
        assertSame(beta, registry.require("beta"));
        assertSame(alpha, alpha.defaultCodec().format());
        assertEquals(2, registry.probeSize());
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.require("missing")
        );

    }

    /// Verifies signature detection uses protected views without modifying caller state.
    @Test
    void detectsWithoutModifyingPrefix() {
        TestFormat first = new TestFormat("first", List.of(), 1, 0x11);
        TestFormat second = new TestFormat("second", List.of(), 2, 0x2233);
        CompressionFormatRegistry registry = CompressionFormatRegistry.fromFormats(List.of(first, second));
        ByteBuffer prefix = ByteBuffer.wrap(new byte[]{0x22, 0x33, 0x44});
        int position = prefix.position();
        int limit = prefix.limit();

        assertSame(second, registry.detect(prefix));
        assertEquals(position, prefix.position());
        assertEquals(limit, prefix.limit());
    }

    /// Verifies ambiguous names and aliases are rejected instead of depending on format order.
    @Test
    void rejectsAmbiguousNames() {
        TestFormat first = new TestFormat("first", List.of("shared"), 0, 0);
        TestFormat second = new TestFormat("SHARED", List.of(), 0, 0);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> CompressionFormatRegistry.fromFormats(List.of(first, second))
        );
        assertTrue(exception.getMessage().contains("Ambiguous compression format"));
    }

    /// Verifies invalid format metadata is rejected while the registry is built.
    @Test
    void rejectsInvalidMetadata() {
        TestFormat blank = new TestFormat(" ", List.of(), 0, 0);
        TestFormat negativeProbe = new TestFormat("negative", List.of(), -1, 0);

        assertThrows(
                IllegalStateException.class,
                () -> CompressionFormatRegistry.fromFormats(List.of(blank))
        );
        assertThrows(
                IllegalStateException.class,
                () -> CompressionFormatRegistry.fromFormats(List.of(negativeProbe))
        );
    }

    /// A small immutable format used to exercise registry metadata.
    @NotNullByDefault
    private static class TestFormat implements CompressionFormat {
        /// The stable test format name.
        private final String name;

        /// Alternative test format names.
        private final @Unmodifiable List<String> aliases;

        /// The preferred signature size.
        private final int probeSize;

        /// The big-endian signature marker.
        private final int marker;

        /// The format's canonical default codec.
        private final CompressionCodec<?> defaultCodec;

        /// Creates a test format with explicit metadata.
        private TestFormat(
                String name,
                @Unmodifiable List<String> aliases,
                int probeSize,
                int marker
        ) {
            this.name = name;
            this.aliases = List.copyOf(aliases);
            this.probeSize = probeSize;
            this.marker = marker;
            this.defaultCodec = new TestCodec(this);
        }

        /// Returns the stable test format name.
        @Override
        public String name() {
            return name;
        }

        /// Returns alternative test format names.
        @Override
        public @Unmodifiable List<String> aliases() {
            return aliases;
        }

        /// Returns the preferred signature size.
        @Override
        public int probeSize() {
            return probeSize;
        }

        /// Returns whether the remaining prefix begins with the configured marker.
        @Override
        public boolean matches(ByteBuffer prefix) {
            if (prefix.remaining() < probeSize) {
                return false;
            }
            int value = 0;
            int position = prefix.position();
            for (int index = 0; index < probeSize; index++) {
                value = (value << Byte.SIZE) | Byte.toUnsignedInt(prefix.get(position + index));
            }
            return value == marker;
        }

        /// Returns the canonical default codec.
        @Override
        public CompressionCodec<?> defaultCodec() {
            return defaultCodec;
        }
    }

    /// A small codec carrying its format identity.
    @NotNullByDefault
    private static final class TestCodec implements CompressionCodec<TestCodec> {
        /// The owning format identity.
        private final CompressionFormat format;

        /// The maximum decoded output size.
        private final long maximumOutputSize;

        /// The maximum decoder history-window size.
        private final long maximumWindowSize;

        /// The maximum decoder working-memory size.
        private final long maximumMemorySize;

        /// Creates a codec for the given format.
        private TestCodec(CompressionFormat format) {
            this(format, UNLIMITED_SIZE, UNLIMITED_SIZE, UNLIMITED_SIZE);
        }

        /// Creates a fully configured codec for the given format.
        private TestCodec(
                CompressionFormat format,
                long maximumOutputSize,
                long maximumWindowSize,
                long maximumMemorySize
        ) {
            this.format = format;
            this.maximumOutputSize = maximumOutputSize;
            this.maximumWindowSize = maximumWindowSize;
            this.maximumMemorySize = maximumMemorySize;
        }

        /// Returns the maximum decoded output size.
        @Override
        public long maximumOutputSize() {
            return maximumOutputSize;
        }

        /// Returns the maximum decoder history-window size.
        @Override
        public long maximumWindowSize() {
            return maximumWindowSize;
        }

        /// Returns the maximum decoder working-memory size.
        @Override
        public long maximumMemorySize() {
            return maximumMemorySize;
        }

        /// Returns a codec with the requested decoded output limit.
        @Override
        public TestCodec withMaximumOutputSize(long value) {
            CompressionDecoderSupport.validateLimit(value, "maximumOutputSize");
            return value == maximumOutputSize
                    ? this
                    : new TestCodec(format, value, maximumWindowSize, maximumMemorySize);
        }

        /// Returns a codec with the requested decoder history-window limit.
        @Override
        public TestCodec withMaximumWindowSize(long value) {
            CompressionDecoderSupport.validateLimit(value, "maximumWindowSize");
            return value == maximumWindowSize
                    ? this
                    : new TestCodec(format, maximumOutputSize, value, maximumMemorySize);
        }

        /// Returns a codec with the requested decoder working-memory limit.
        @Override
        public TestCodec withMaximumMemorySize(long value) {
            CompressionDecoderSupport.validateLimit(value, "maximumMemorySize");
            return value == maximumMemorySize
                    ? this
                    : new TestCodec(format, maximumOutputSize, maximumWindowSize, value);
        }

        /// Returns the owning format identity.
        @Override
        public CompressionFormat format() {
            return format;
        }

        /// Rejects encoder creation because registry tests only exercise metadata.
        @Override
        public CompressionEncoder newEncoder(EncodingOptions options) {
            throw new UnsupportedOperationException("Test codec has no encoder");
        }

        /// Rejects decoder creation because registry tests only exercise metadata.
        @Override
        public CompressionDecoder newDecoder() {
            throw new UnsupportedOperationException("Test codec has no decoder");
        }
    }
}
