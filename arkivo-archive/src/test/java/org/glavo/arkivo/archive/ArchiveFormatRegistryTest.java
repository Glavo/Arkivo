// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies immutable archive format registry lookup, detection, and validation.
@NotNullByDefault
public final class ArchiveFormatRegistryTest {
    /// Verifies names, aliases, stable order, and required lookup.
    @Test
    void indexesFormats() {
        TestFormat alpha = new TestFormat("alpha", List.of("a"), 1, 0x11);
        TestFormat beta = new TestFormat("beta", List.of("b"), 2, 0x2233);
        ArchiveFormatRegistry registry = ArchiveFormatRegistry.fromFormats(List.of(
                alpha,
                alpha,
                new TestFormat("alpha", List.of("a"), 1, 0x11),
                beta
        ));

        assertEquals(List.of(alpha, beta), registry.formats());
        assertSame(alpha, registry.find("ALPHA"));
        assertSame(alpha, registry.find("A"));
        assertSame(beta, registry.require("beta"));
        assertEquals(2, registry.probeSize());
        assertThrows(IllegalArgumentException.class, () -> registry.require("missing"));
    }

    /// Verifies detection prefers the matching format with the most specific requested prefix.
    @Test
    void detectsMostSpecificFormatWithoutModifyingPrefix() {
        TestFormat shortFormat = new TestFormat("short", List.of(), 1, 0x22);
        TestFormat longFormat = new TestFormat("long", List.of(), 2, 0x2233);
        ArchiveFormatRegistry registry =
                ArchiveFormatRegistry.fromFormats(List.of(shortFormat, longFormat));
        ByteBuffer prefix = ByteBuffer.wrap(new byte[]{0x22, 0x33, 0x44});
        int position = prefix.position();
        int limit = prefix.limit();

        assertSame(longFormat, registry.detect(prefix));
        assertEquals(position, prefix.position());
        assertEquals(limit, prefix.limit());
    }

    /// Verifies ambiguous names and aliases fail during registry construction.
    @Test
    void rejectsAmbiguousNames() {
        TestFormat first = new TestFormat("first", List.of("shared"), 0, 0);
        TestFormat second = new TestFormat("SHARED", List.of(), 0, 0);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> ArchiveFormatRegistry.fromFormats(List.of(first, second))
        );
        assertTrue(exception.getMessage().contains("Ambiguous archive format"));
    }

    /// Verifies malformed format metadata fails during registry construction.
    @Test
    void rejectsInvalidMetadata() {
        TestFormat blank = new TestFormat(" ", List.of(), 0, 0);
        TestFormat negativeProbe = new TestFormat("negative", List.of(), -1, 0);

        assertThrows(
                IllegalStateException.class,
                () -> ArchiveFormatRegistry.fromFormats(List.of(blank))
        );
        assertThrows(
                IllegalStateException.class,
                () -> ArchiveFormatRegistry.fromFormats(List.of(negativeProbe))
        );
    }

    /// Supplies immutable archive format metadata for registry tests.
    @NotNullByDefault
    private static final class TestFormat implements ArkivoFormat {
        /// The stable format name.
        private final String name;

        /// Alternative stable names.
        private final @Unmodifiable List<String> aliases;

        /// The preferred detection prefix size.
        private final int probeSize;

        /// The big-endian signature marker.
        private final int marker;

        /// Creates one test format.
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
        }

        /// Returns the stable format name.
        @Override
        public String name() {
            return name;
        }

        /// Returns alternative stable names.
        @Override
        public @Unmodifiable List<String> aliases() {
            return aliases;
        }

        /// Returns the preferred detection prefix size.
        @Override
        public int probeSize() {
            return probeSize;
        }

        /// Returns whether the prefix begins with this format's marker.
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
    }
}
