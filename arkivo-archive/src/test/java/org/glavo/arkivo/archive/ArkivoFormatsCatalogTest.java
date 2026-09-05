// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies immutable archive format catalog lookup, detection, and validation.
@NotNullByDefault
public final class ArkivoFormatsCatalogTest {
    /// Verifies names, aliases, stable order, and required lookup.
    @Test
    void indexesFormats() {
        TestFormat alpha = new TestFormat("alpha", List.of("a"), 1, 0x11);
        TestFormat beta = new TestFormat("beta", List.of("b"), 2, 0x2233);
        ArkivoFormats.Catalog catalog = ArkivoFormats.Catalog.of(List.of(
                alpha,
                alpha,
                new TestFormat("alpha", List.of("a"), 1, 0x11),
                beta
        ));

        assertEquals(List.of(alpha, beta), catalog.formats());
        assertSame(alpha, catalog.find("ALPHA"));
        assertSame(alpha, catalog.find("A"));
        assertSame(beta, catalog.require("beta"));
        assertEquals(2, catalog.probeSize());
        assertThrows(IllegalArgumentException.class, () -> catalog.require("missing"));
    }

    /// Verifies detection prefers the matching format with the most specific requested prefix.
    @Test
    void detectsMostSpecificFormatWithoutModifyingPrefix() {
        TestFormat shortFormat = new TestFormat("short", List.of(), 1, 0x22);
        TestFormat longFormat = new TestFormat("long", List.of(), 2, 0x2233);
        ArkivoFormats.Catalog catalog = ArkivoFormats.Catalog.of(List.of(shortFormat, longFormat));
        ByteBuffer prefix = ByteBuffer.wrap(new byte[]{0x22, 0x33, 0x44});
        int position = prefix.position();
        int limit = prefix.limit();

        assertSame(longFormat, catalog.detect(prefix));
        assertEquals(position, prefix.position());
        assertEquals(limit, prefix.limit());
    }

    /// Verifies ambiguous names and aliases fail during index construction.
    @Test
    void rejectsAmbiguousNames() {
        TestFormat first = new TestFormat("first", List.of("shared"), 0, 0);
        TestFormat second = new TestFormat("SHARED", List.of(), 0, 0);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> ArkivoFormats.Catalog.of(List.of(first, second))
        );
        assertTrue(exception.getMessage().contains("Ambiguous archive format"));
    }

    /// Verifies malformed format metadata fails during index construction.
    @Test
    void rejectsInvalidMetadata() {
        TestFormat blank = new TestFormat(" ", List.of(), 0, 0);
        TestFormat negativeProbe = new TestFormat("negative", List.of(), -1, 0);

        assertThrows(
                IllegalStateException.class,
                () -> ArkivoFormats.Catalog.of(List.of(blank))
        );
        assertThrows(
                IllegalStateException.class,
                () -> ArkivoFormats.Catalog.of(List.of(negativeProbe))
        );
    }

    /// Verifies the default seekable probe starts at and restores a nonzero channel position.
    @Test
    void defaultSeekableProbeRestoresItsOrigin() throws IOException {
        TestFormat format = new TestFormat("marker", List.of(), 2, 0x2233);
        ProbeChannel source = new ProbeChannel(new byte[]{0x11, 0x22, 0x33, 0x44});
        source.position(1L);

        assertTrue(format.matches(source));
        assertEquals(1L, source.position());
    }

    /// Verifies the default seekable probe rejects a nonempty read that makes no progress.
    @Test
    void defaultSeekableProbeRejectsZeroProgress() throws IOException {
        TestFormat format = new TestFormat("marker", List.of(), 1, 0x22);
        ProbeChannel source = new ProbeChannel(new byte[]{0x22});
        source.zeroProgress = true;

        IOException exception = assertThrows(IOException.class, () -> format.matches(source));

        assertEquals("Archive format probe made no progress", exception.getMessage());
        assertEquals(0L, source.position());
    }

    /// Verifies position-restoration failure is primary after a successful default seekable probe.
    @Test
    void defaultSeekableProbeReportsRestorationFailure() throws IOException {
        TestFormat format = new TestFormat("marker", List.of(), 1, 0x22);
        ProbeChannel source = new ProbeChannel(new byte[]{0x22});
        IOException restorationFailure = new IOException("restore failed");
        source.failPositionSet(1, restorationFailure);

        IOException exception = assertThrows(IOException.class, () -> format.matches(source));

        assertSame(restorationFailure, exception);
        assertEquals(1L, source.position());
    }

    /// Verifies position-restoration failure is suppressed behind a default probe failure.
    @Test
    void defaultSeekableProbeSuppressesRestorationFailure() throws IOException {
        TestFormat format = new TestFormat("marker", List.of(), 1, 0x22);
        ProbeChannel source = new ProbeChannel(new byte[]{0x22});
        IOException probeFailure = new IOException("probe failed");
        IOException restorationFailure = new IOException("restore failed");
        source.readFailure = probeFailure;
        source.failPositionSet(1, restorationFailure);

        IOException exception = assertThrows(IOException.class, () -> format.matches(source));

        assertSame(probeFailure, exception);
        assertEquals(1, exception.getSuppressed().length);
        assertSame(restorationFailure, exception.getSuppressed()[0]);
    }

    /// Verifies one shared probe and restoration failure retains its original identity without self-suppression.
    @Test
    void defaultSeekableProbePreservesSharedFailure() {
        TestFormat format = new TestFormat("marker", List.of(), 1, 0x22);
        ProbeChannel source = new ProbeChannel(new byte[]{0x22});
        IOException failure = new IOException("shared failure");
        source.readFailure = failure;
        source.failPositionSet(1, failure);

        IOException exception = assertThrows(IOException.class, () -> format.matches(source));

        assertSame(failure, exception);
        assertEquals(0, exception.getSuppressed().length);
    }

    /// Verifies the catalog defensively restores the same origin between nonconforming format probes.
    @Test
    void seekableCatalogRestoresPositionBetweenFormats() throws IOException {
        PositionMutatingFormat shorter = new PositionMutatingFormat("shorter", 1, false, null);
        PositionMutatingFormat longer = new PositionMutatingFormat("longer", 2, true, null);
        ArkivoFormats.Catalog catalog = ArkivoFormats.Catalog.of(List.of(shorter, longer));
        ProbeChannel source = new ProbeChannel(new byte[]{0x11, 0x22, 0x33});
        source.position(1L);

        assertSame(longer, catalog.detect(source));
        assertEquals(1L, shorter.observedPosition);
        assertEquals(1L, longer.observedPosition);
        assertEquals(1L, source.position());
    }

    /// Verifies seekable ambiguity is resolved by explicit catalog order rather than unrelated probe length.
    @Test
    void seekableCatalogPrefersFirstAmbiguousMatch() throws IOException {
        PositionMutatingFormat first = new PositionMutatingFormat("first", 1, true, null);
        PositionMutatingFormat second = new PositionMutatingFormat("second", 2, true, null);
        ArkivoFormats.Catalog catalog = ArkivoFormats.Catalog.of(List.of(first, second));
        ProbeChannel source = new ProbeChannel(new byte[]{0x11, 0x22, 0x33});
        source.position(1L);

        assertSame(first, catalog.detect(source));
        assertEquals(1L, first.observedPosition);
        assertEquals(-1L, second.observedPosition);
        assertEquals(1L, source.position());
    }

    /// Verifies catalog restoration failure is suppressed behind a format probe failure.
    @Test
    void seekableCatalogSuppressesRestorationFailure() throws IOException {
        IOException probeFailure = new IOException("probe failed");
        IOException restorationFailure = new IOException("restore failed");
        PositionMutatingFormat format = new PositionMutatingFormat(
                "failing",
                1,
                false,
                probeFailure
        );
        ArkivoFormats.Catalog catalog = ArkivoFormats.Catalog.of(List.of(format));
        ProbeChannel source = new ProbeChannel(new byte[]{0x11, 0x22});
        source.failPositionSet(2, restorationFailure);

        IOException exception = assertThrows(IOException.class, () -> catalog.detect(source));

        assertSame(probeFailure, exception);
        assertEquals(1, exception.getSuppressed().length);
        assertSame(restorationFailure, exception.getSuppressed()[0]);
    }

    /// Verifies catalog restoration does not suppress a repeated probe failure onto itself.
    @Test
    void seekableCatalogPreservesSharedFailure() {
        IOException failure = new IOException("shared failure");
        PositionMutatingFormat format = new PositionMutatingFormat("failing", 1, false, failure);
        ArkivoFormats.Catalog catalog = ArkivoFormats.Catalog.of(List.of(format));
        ProbeChannel source = new ProbeChannel(new byte[]{0x11, 0x22});
        source.failPositionSet(2, failure);

        IOException exception = assertThrows(IOException.class, () -> catalog.detect(source));

        assertSame(failure, exception);
        assertEquals(0, exception.getSuppressed().length);
    }

    /// Supplies immutable archive format metadata for catalog tests.
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

    /// Provides a deliberately nonconforming seekable probe for catalog restoration tests.
    @NotNullByDefault
    private static final class PositionMutatingFormat implements ArkivoFormat {
        /// The stable test format name.
        private final String name;

        /// The reported preferred probe size.
        private final int probeSize;

        /// Whether this format reports a match when no failure is configured.
        private final boolean matches;

        /// The configured probe failure, or `null` for a normal result.
        private final @Nullable IOException failure;

        /// The position observed at the start of the latest probe.
        private long observedPosition = -1L;

        /// Creates a position-mutating format with the requested result or failure.
        private PositionMutatingFormat(
                String name,
                int probeSize,
                boolean matches,
                @Nullable IOException failure
        ) {
            this.name = name;
            this.probeSize = probeSize;
            this.matches = matches;
            this.failure = failure;
        }

        /// Returns the stable test format name.
        @Override
        public String name() {
            return name;
        }

        /// Returns the preferred probe size.
        @Override
        public int probeSize() {
            return probeSize;
        }

        /// Advances the borrowed channel without restoring it, then reports the configured result.
        @Override
        public boolean matches(SeekableByteChannel source) throws IOException {
            observedPosition = source.position();
            source.position(observedPosition + 1L);
            if (failure != null) {
                throw failure;
            }
            return matches;
        }
    }

    /// Provides a controllable read-only seekable channel for probe-failure tests.
    @NotNullByDefault
    private static final class ProbeChannel implements SeekableByteChannel {
        /// The immutable source bytes.
        private final byte @Unmodifiable [] content;

        /// The next read position.
        private long position;

        /// Whether reads return zero while the target has space.
        private boolean zeroProgress;

        /// The configured read failure, or `null` for normal reads.
        private @Nullable IOException readFailure;

        /// The one-based position-set invocation that fails, or zero when none fails.
        private int failingPositionSet;

        /// The configured position-set failure, or `null` when none fails.
        private @Nullable IOException positionFailure;

        /// The number of position-set invocations.
        private int positionSetCount;

        /// Whether the channel remains open.
        private boolean open = true;

        /// Creates a channel over a private copy of the supplied bytes.
        private ProbeChannel(byte[] content) {
            this.content = Objects.requireNonNull(content, "content").clone();
        }

        /// Configures one position-set invocation to throw the given exception.
        private void failPositionSet(int invocation, IOException failure) {
            if (invocation <= 0) {
                throw new IllegalArgumentException("invocation must be positive");
            }
            this.failingPositionSet = invocation;
            this.positionFailure = Objects.requireNonNull(failure, "failure");
        }

        /// Reads available bytes, reports configured zero progress, or throws the configured failure.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            ensureOpen();
            if (readFailure != null) {
                throw readFailure;
            }
            if (!target.hasRemaining()) {
                return 0;
            }
            if (zeroProgress) {
                return 0;
            }
            if (position >= content.length) {
                return -1;
            }

            int count = Math.min(target.remaining(), content.length - Math.toIntExact(position));
            target.put(content, Math.toIntExact(position), count);
            position += count;
            return count;
        }

        /// Rejects writes because this probe channel is read-only.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns the current position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Sets the position unless this invocation has been configured to fail.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0L) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            positionSetCount++;
            if (positionSetCount == failingPositionSet) {
                throw Objects.requireNonNull(positionFailure, "positionFailure");
            }
            position = newPosition;
            return this;
        }

        /// Returns the source byte count.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return content.length;
        }

        /// Rejects truncation because this probe channel is read-only.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns whether the channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes the channel.
        @Override
        public void close() {
            open = false;
        }

        /// Requires this channel to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}
