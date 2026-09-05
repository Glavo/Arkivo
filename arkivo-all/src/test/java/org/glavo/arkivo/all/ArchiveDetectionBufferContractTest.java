// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFormat;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies archive-prefix detection treats every caller buffer as immutable input state.
@NotNullByDefault
final class ArchiveDetectionBufferContractTest {
    /// Sentinel retained outside the visible prefix range.
    private static final byte BUFFER_GUARD = (byte) 0xa7;

    /// Content stored in archives generated through public writer APIs.
    private static final byte @Unmodifiable [] CONTENT =
            "archive signature buffer contract".getBytes(StandardCharsets.UTF_8);

    /// Verifies exact preferred prefixes across heap, direct, mutable, and read-only views.
    @Test
    void detectsEveryPrefixIdentifiedFormatFromProtectedBufferViews() throws IOException {
        for (ArkivoFormat format : ArkivoFormats.installed()) {
            if ("dmg".equals(format.name())) {
                continue;
            }

            byte[] archive = createArchive(format);
            assertTrue(archive.length >= format.probeSize(), format.name());
            byte[] prefix = Arrays.copyOf(archive, format.probeSize());

            assertDetectedView(format, prefix, false, false);
            assertDetectedView(format, prefix, false, true);
            assertDetectedView(format, prefix, true, false);
            assertDetectedView(format, prefix, true, true);
        }
    }

    /// Verifies every shorter preferred prefix is safe to inspect without changing caller-visible state.
    @Test
    void safelyInspectsTruncatedArchivePrefixesWithoutMutation() throws IOException {
        for (ArkivoFormat format : ArkivoFormats.installed()) {
            if ("dmg".equals(format.name())) {
                continue;
            }

            byte[] archive = createArchive(format);
            byte[] completePrefix = Arrays.copyOf(archive, format.probeSize());
            for (int length = 0; length < completePrefix.length; length++) {
                assertStableDetection(
                        format,
                        Arrays.copyOf(completePrefix, length),
                        (length & 1) != 0,
                        (length & 2) != 0
                );
            }
        }
    }

    /// Verifies the trailer-only DMG format never interprets a caller buffer as an archive prefix.
    @Test
    void rejectsDmgTrailerAsPrefixWithoutMutation() {
        ArkivoFormat format = ArkivoFormats.require("dmg");
        byte[] trailer = new byte[format.probeSize()];
        Arrays.fill(trailer, (byte) 0x5a);
        trailer[0] = 'k';
        trailer[1] = 'o';
        trailer[2] = 'l';
        trailer[3] = 'y';

        for (boolean direct : new boolean[]{false, true}) {
            for (boolean readOnly : new boolean[]{false, true}) {
                ByteBuffer storage = createStorage(trailer, direct);
                ByteBuffer view = createView(storage, trailer.length, readOnly);
                BufferState original = BufferState.capture(view, storage);
                String context = "dmg direct=" + direct + " readOnly=" + readOnly;

                assertFalse(format.matches(view), context);
                original.assertUnchanged(view, storage, context);
                assertNull(ArkivoFormats.detect(view), context);
                original.assertUnchanged(view, storage, context);
                assertGuardBytes(storage, trailer.length, context);
            }
        }
    }

    /// Verifies a terminal DMG signature outranks a coincidental empty-TAR prefix for seekable detection.
    @Test
    void prefersTerminalDmgSignatureOverEmptyTarPrefix() throws IOException {
        ArkivoFormat dmg = ArkivoFormats.require("dmg");
        ArkivoFormat tar = ArkivoFormats.require("tar");
        byte[] image = new byte[tar.probeSize() + dmg.probeSize()];
        ByteBuffer.wrap(image).order(ByteOrder.BIG_ENDIAN).putInt(
                image.length - dmg.probeSize(),
                0x6b6f6c79
        );
        assertTrue(tar.matches(ByteBuffer.wrap(image)));

        try (FragmentingSeekableByteChannel source = new FragmentingSeekableByteChannel(image, 1)) {
            assertTrue(dmg.matches(source));
            assertEquals(0L, source.position());
            assertSame(dmg, ArkivoFormats.detect(source));
            assertEquals(0L, source.position());
        }
    }

    /// Verifies every format through fragmented seekable reads from a nonzero logical archive origin.
    @Test
    void detectsEveryFormatFromFragmentedSeekableSourcesAndRestoresPosition() throws IOException {
        int formatIndex = 0;
        for (ArkivoFormat format : ArkivoFormats.installed()) {
            byte[] archive = createArchive(format);
            int archiveOffset = 5 + formatIndex;
            byte[] embedded = new byte[archiveOffset + archive.length];
            Arrays.fill(embedded, 0, archiveOffset, BUFFER_GUARD);
            System.arraycopy(archive, 0, embedded, archiveOffset, archive.length);

            try (FragmentingSeekableByteChannel source =
                         new FragmentingSeekableByteChannel(embedded, 1 + formatIndex % 5)) {
                source.position(archiveOffset);
                assertTrue(format.matches(source), format.name());
                assertEquals(archiveOffset, source.position(), format.name());

                assertSame(format, ArkivoFormats.detect(source), format.name());
                assertEquals(archiveOffset, source.position(), format.name());
            }
            formatIndex++;
        }
    }

    /// Creates one complete archive whose prefix identifies the given format.
    private static byte @Unmodifiable [] createArchive(ArkivoFormat format) throws IOException {
        if ("rar".equals(format.name())) {
            return new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x00, 0x00};
        }
        if ("dmg".equals(format.name())) {
            byte[] image = new byte[format.probeSize()];
            image[0] = 'k';
            image[1] = 'o';
            image[2] = 'l';
            image[3] = 'y';
            return image;
        }
        if (!(format instanceof ArkivoFormat.StreamingWritable streamingWriter)) {
            throw new AssertionError("No archive seed writer for " + format.name());
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArkivoStreamingWriter writer = streamingWriter.openStreamingWriter(output)) {
            try (OutputStream body = writer.beginFile("entry.txt").openOutputStream()) {
                body.write(CONTENT);
            }
        }
        return output.toByteArray();
    }

    /// Verifies a signature view identifies the expected format and preserves storage and view state.
    private static void assertDetectedView(
            ArkivoFormat expected,
            byte @Unmodifiable [] prefix,
            boolean direct,
            boolean readOnly
    ) {
        ByteBuffer storage = createStorage(prefix, direct);
        ByteBuffer view = createView(storage, prefix.length, readOnly);
        BufferState original = BufferState.capture(view, storage);
        String context = expected.name() + " direct=" + direct + " readOnly=" + readOnly;

        assertTrue(expected.matches(view), context);
        original.assertUnchanged(view, storage, context);
        assertSame(expected, ArkivoFormats.detect(view), context);
        original.assertUnchanged(view, storage, context);
        assertGuardBytes(storage, prefix.length, context);
    }

    /// Runs generic detection for an arbitrary short prefix and verifies it is observationally read-only.
    private static void assertStableDetection(
            ArkivoFormat format,
            byte @Unmodifiable [] prefix,
            boolean direct,
            boolean readOnly
    ) {
        ByteBuffer storage = createStorage(prefix, direct);
        ByteBuffer view = createView(storage, prefix.length, readOnly);
        BufferState original = BufferState.capture(view, storage);
        String context = format.name() + " length=" + prefix.length;

        format.matches(view);
        original.assertUnchanged(view, storage, context);
        ArkivoFormats.detect(view);
        original.assertUnchanged(view, storage, context);
        assertGuardBytes(storage, prefix.length, context);
    }

    /// Creates guarded storage containing the prefix at a nonzero offset.
    private static ByteBuffer createStorage(byte @Unmodifiable [] prefix, boolean direct) {
        ByteBuffer storage = direct
                ? ByteBuffer.allocateDirect(prefix.length + 9)
                : ByteBuffer.allocate(prefix.length + 9);
        for (int index = 0; index < storage.capacity(); index++) {
            storage.put(index, BUFFER_GUARD);
        }
        storage.position(4);
        storage.put(prefix);
        storage.clear();
        return storage;
    }

    /// Creates the mutable or read-only visible prefix view with a non-default byte order and a current mark.
    private static ByteBuffer createView(ByteBuffer storage, int prefixLength, boolean readOnly) {
        ByteBuffer view = storage.duplicate();
        view.position(4);
        view.limit(4 + prefixLength);
        if (readOnly) {
            view = view.asReadOnlyBuffer();
        }
        view.order(ByteOrder.LITTLE_ENDIAN);
        view.mark();
        return view;
    }

    /// Verifies the guard bytes surrounding the visible prefix remain untouched.
    private static void assertGuardBytes(ByteBuffer storage, int prefixLength, String context) {
        for (int index = 0; index < 4; index++) {
            assertEquals(BUFFER_GUARD, storage.get(index), context + " prefix guard " + index);
        }
        for (int index = 4 + prefixLength; index < storage.capacity(); index++) {
            assertEquals(BUFFER_GUARD, storage.get(index), context + " suffix guard " + index);
        }
    }

    /// Captures caller-visible buffer state and the complete backing storage contents.
    ///
    /// @param position original view position and mark
    /// @param limit original view limit
    /// @param order original view byte order
    /// @param readOnly whether the original view was read-only
    /// @param storage complete backing-storage snapshot
    private record BufferState(
            int position,
            int limit,
            ByteOrder order,
            boolean readOnly,
            byte @Unmodifiable [] storage
    ) {
        /// Captures one view and its complete backing storage.
        private static BufferState capture(ByteBuffer view, ByteBuffer storage) {
            ByteBuffer snapshotView = storage.duplicate();
            snapshotView.clear();
            byte[] snapshot = new byte[snapshotView.remaining()];
            snapshotView.get(snapshot);
            return new BufferState(
                    view.position(),
                    view.limit(),
                    view.order(),
                    view.isReadOnly(),
                    snapshot
            );
        }

        /// Verifies the view state, mark, and complete backing storage equal this snapshot.
        private void assertUnchanged(ByteBuffer view, ByteBuffer currentStorage, String context) {
            assertEquals(position, view.position(), context);
            assertEquals(limit, view.limit(), context);
            assertEquals(order, view.order(), context);
            assertEquals(readOnly, view.isReadOnly(), context);
            view.reset();
            assertEquals(position, view.position(), context + " mark");

            ByteBuffer snapshotView = currentStorage.duplicate();
            snapshotView.clear();
            byte[] current = new byte[snapshotView.remaining()];
            snapshotView.get(current);
            assertArrayEquals(storage, current, context);
        }
    }

    /// Provides a read-only in-memory seekable source whose reads are deliberately fragmented.
    @NotNullByDefault
    private static final class FragmentingSeekableByteChannel implements SeekableByteChannel {
        /// Immutable source bytes.
        private final byte @Unmodifiable [] bytes;

        /// Maximum bytes returned by one nonempty read.
        private final int maximumReadSize;

        /// Current channel position.
        private int position;

        /// Whether the channel remains open.
        private boolean open = true;

        /// Creates a source over a private copy of the supplied bytes.
        private FragmentingSeekableByteChannel(byte @Unmodifiable [] bytes, int maximumReadSize) {
            this.bytes = bytes.clone();
            this.maximumReadSize = maximumReadSize;
        }

        /// Reads at most the configured fragment size from the current position.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            if (!destination.hasRemaining()) {
                return 0;
            }
            if (position >= bytes.length) {
                return -1;
            }
            int count = Math.min(
                    Math.min(destination.remaining(), maximumReadSize),
                    bytes.length - position
            );
            destination.put(bytes, position, count);
            position += count;
            return count;
        }

        /// Rejects writes because the source is read-only.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns the current byte position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Sets the current byte position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0L || newPosition > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("newPosition out of range: " + newPosition);
            }
            position = (int) newPosition;
            return this;
        }

        /// Returns the fixed source size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return bytes.length;
        }

        /// Rejects truncation because the source is read-only.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            ensureOpen();
            throw new NonWritableChannelException();
        }

        /// Returns whether the source remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes the source.
        @Override
        public void close() {
            open = false;
        }

        /// Throws when an operation requires an open source.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}
