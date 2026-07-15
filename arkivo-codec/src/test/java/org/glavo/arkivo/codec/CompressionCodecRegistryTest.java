// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies immutable compression codec registry discovery and validation.
@NotNullByDefault
public final class CompressionCodecRegistryTest {
    /// Verifies names, aliases, provider order, and typed lookup.
    @Test
    void indexesDefaultCodecs() {
        TestCodec alpha = new TestCodec("alpha", List.of("a"), 1, 0x11);
        TestCodec beta = new TestCodec("beta", List.of("b"), 2, 0x2233);
        CompressionCodecRegistry registry = CompressionCodecRegistry.fromProviders(List.of(
                () -> alpha,
                () -> beta
        ));

        assertEquals(List.of(alpha, beta), registry.codecs());
        assertSame(alpha, registry.find("ALPHA"));
        assertSame(alpha, registry.find("A"));
        assertSame(beta, registry.require("beta"));
        assertSame(beta, registry.require("b", TestCodec.class));
        assertEquals(2, registry.probeSize());
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.require("missing")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.require("alpha", OtherTestCodec.class)
        );
    }

    /// Verifies signature detection uses protected views without modifying caller state.
    @Test
    void detectsWithoutModifyingPrefix() {
        TestCodec first = new TestCodec("first", List.of(), 1, 0x11);
        TestCodec second = new TestCodec("second", List.of(), 2, 0x2233);
        CompressionCodecRegistry registry = CompressionCodecRegistry.fromProviders(List.of(
                () -> first,
                () -> second
        ));
        ByteBuffer prefix = ByteBuffer.wrap(new byte[]{0x22, 0x33, 0x44});
        int position = prefix.position();
        int limit = prefix.limit();

        assertSame(second, registry.detect(prefix));
        assertEquals(position, prefix.position());
        assertEquals(limit, prefix.limit());
    }

    /// Verifies ambiguous names and aliases are rejected instead of depending on provider order.
    @Test
    void rejectsAmbiguousNames() {
        TestCodec first = new TestCodec("first", List.of("shared"), 0, 0);
        TestCodec second = new TestCodec("SHARED", List.of(), 0, 0);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> CompressionCodecRegistry.fromProviders(List.of(
                        () -> first,
                        () -> second
                ))
        );
        assertTrue(exception.getMessage().contains("Ambiguous compression codec"));
    }

    /// Verifies invalid provider metadata is rejected while the registry is built.
    @Test
    void rejectsInvalidMetadata() {
        TestCodec blank = new TestCodec(" ", List.of(), 0, 0);
        TestCodec negativeProbe = new TestCodec("negative", List.of(), -1, 0);

        assertThrows(
                IllegalStateException.class,
                () -> CompressionCodecRegistry.fromProviders(List.of(() -> blank))
        );
        assertThrows(
                IllegalStateException.class,
                () -> CompressionCodecRegistry.fromProviders(List.of(() -> negativeProbe))
        );
    }

    /// A small immutable codec used to exercise registry metadata.
    @NotNullByDefault
    private static class TestCodec implements CompressionCodec {
        /// Empty capabilities used because registry tests do not create engines.
        private static final CompressionCapabilities CAPABILITIES =
                CompressionCapabilities.of(Set.of());

        /// The stable test codec name.
        private final String name;

        /// Alternative test codec names.
        private final @Unmodifiable List<String> aliases;

        /// The preferred signature size.
        private final int probeSize;

        /// The big-endian signature marker.
        private final int marker;

        /// Creates a test codec with explicit metadata.
        private TestCodec(
                String name,
                @Unmodifiable List<String> aliases,
                int probeSize,
                int marker
        ) {
            this.name = name;
            this.aliases = List.copyOf(aliases);
            this.probeSize = probeSize;
            this.marker = marker;
        }

        /// Returns the stable test codec name.
        @Override
        public String name() {
            return name;
        }

        /// Returns alternative test codec names.
        @Override
        public @Unmodifiable List<String> aliases() {
            return aliases;
        }

        /// Returns empty engine capabilities.
        @Override
        public CompressionCapabilities capabilities() {
            return CAPABILITIES;
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
            for (int index = 0; index < probeSize; index++) {
                value = (value << Byte.SIZE) | Byte.toUnsignedInt(prefix.get());
            }
            return value == marker;
        }
    }

    /// A distinct codec type used to verify typed lookup failures.
    @NotNullByDefault
    private static final class OtherTestCodec extends TestCodec {
        /// Creates a distinct test codec type.
        private OtherTestCodec() {
            super("other", List.of(), 0, 0);
        }
    }
}