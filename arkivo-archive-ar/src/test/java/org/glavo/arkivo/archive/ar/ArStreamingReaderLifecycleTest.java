// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.DosFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies AR streaming-reader entry ownership, body lifetime, and source cleanup behavior.
@NotNullByDefault
final class ArStreamingReaderLifecycleTest {
    /// Verifies that a member body can only be opened once.
    @Test
    void entryBodyCanOnlyBeOpenedOnce() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] archive = singleFileArchive("hello.txt", content);

        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertTrue(reader.next());

            try (var channel = reader.openChannel()) {
                IllegalStateException exception = assertThrows(IllegalStateException.class, reader::openChannel);
                assertTrue(exception.getMessage().contains("already open"));

                ByteBuffer buffer = ByteBuffer.allocate(content.length);
                assertEquals(content.length, channel.read(buffer));
                buffer.flip();
                assertEquals("hello", StandardCharsets.UTF_8.decode(buffer).toString());
            }

            assertFalse(reader.next());
        }
    }

    /// Verifies body-close retries retain successful drain progress and preserve the following member header.
    @ParameterizedTest
    @ValueSource(ints = {3, 16385})
    void retriesBodyDrainWithoutSkippingFollowingMember(int bodySize) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var writer = ArArkivoStreamingWriter.open(output)) {
            writeFile(writer, "first", new byte[bodySize]);
            writeFile(writer, "second", new byte[]{4});
        }
        byte[] archive = output.toByteArray();
        for (int consumed : new int[]{1, 2, 8192, 8193, bodySize - 1, bodySize}) {
            if (consumed > bodySize) continue;
            int failureOffset = 68 + consumed;
            for (Throwable failure : List.of(new IOException("drain failed"),
                    new IllegalStateException("drain failed"), new AssertionError("drain failed"))) {
                FailOnceInputStream source = new FailOnceInputStream(archive, failureOffset, failure);
                try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(source)) {
                    assertTrue(reader.next());
                    assertEquals(68, source.position);
                    InputStream body = reader.openInputStream();
                    assertSame(failure, assertThrows(failure.getClass(), body::close));
                    assertEquals(failureOffset, source.position);
                    body.close();
                    assertEquals(68 + bodySize + 1, source.position);
                    body.close();
                    assertEquals(68 + bodySize + 1, source.position);
                    assertTrue(reader.next());
                    assertEquals("second", reader.readAttributes(ArArkivoEntryAttributes.class).path());
                    try (InputStream second = reader.openInputStream()) {
                        assertEquals(4, second.read());
                        assertEquals(-1, second.read());
                    }
                    assertFalse(reader.next());
                }
            }
        }
    }

    /// Reuses one bounded buffer across symbol tables, unread bodies, entry closes, and alignment padding.
    @ParameterizedTest
    @CsvSource({"0,false", "1,false", "8192,false", "8193,false", "16385,false",
            "0,true", "1,true", "8192,true", "8193,true", "16385,true"})
    void reusesDiscardBufferAcrossMembers(int firstSize, boolean symbolTable) throws IOException {
        int[] sizes = {firstSize, 0, 1, 8193, 2};
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var writer = ArArkivoStreamingWriter.open(output)) {
            for (int index = 0; index < sizes.length; index++) {
                writeFile(writer, "entry" + index, new byte[sizes[index]]);
            }
        }
        byte[] archive = output.toByteArray();
        if (symbolTable) {
            Arrays.fill(archive, 8, 24, (byte) ' ');
            archive[8] = '/';
        }
        DiscardTrackingInputStream source = new DiscardTrackingInputStream(archive, sizes);
        try (var reader = ArArkivoStreamingReader.open(source)) {
            for (int index = symbolTable ? 1 : 0; index < sizes.length; index++) {
                assertTrue(reader.next());
                assertEquals("entry" + index, reader.readAttributes(ArArkivoEntryAttributes.class).path());
                if ((index & 1) != 0) reader.openInputStream().close();
            }
            assertFalse(reader.next());
            assertTrue(source.discardReads >= 6);
            assertEquals(archive.length, source.position());
        }
    }

    /// Verifies entry streams drain unread bodies, preserve padding, and reject use after close.
    @Test
    void entryInputStreamsEnforceTheirLifecycle() throws IOException {
        byte[] archive = twoFileArchive();

        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertTrue(reader.next());
            var first = reader.openInputStream();
            assertEquals(1, first.read());
            first.close();
            first.close();
            assertThrows(IOException.class, first::read);

            assertTrue(reader.next());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> reader.readAttributes(DosFileAttributes.class)
            );
            try (var second = reader.openInputStream()) {
                assertEquals(0, second.read(new byte[0]));
                assertEquals(4, second.read());
                assertEquals(-1, second.read());
                assertEquals(-1, second.read());
            }
            assertFalse(reader.next());
        }
    }

    /// Verifies that advancing closes an open body and drains its unread bytes and odd-length padding.
    @Test
    void advancingClosesAndDrainsPartiallyReadMemberBody() throws IOException {
        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(
                new ByteArrayInputStream(twoFileArchive())
        )) {
            assertTrue(reader.next());
            var first = reader.openInputStream();
            assertEquals(1, first.read());

            assertTrue(reader.next());
            assertThrows(ClosedChannelException.class, first::read);
            first.close();

            try (var second = reader.openInputStream()) {
                assertEquals(4, second.read());
                assertEquals(-1, second.read());
            }
            assertFalse(reader.next());
        }
    }

    /// Returns a complete archive containing one regular file.
    ///
    /// @param path the archive-local member path
    /// @param content the member body
    /// @return a complete generated AR archive
    /// @throws IOException if the fixture cannot be encoded
    private static byte[] singleFileArchive(String path, byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(output)) {
            writeFile(writer, path, content);
        }
        return output.toByteArray();
    }

    /// Returns a complete archive containing two regular files of different parity.
    ///
    /// @return a complete generated AR archive
    /// @throws IOException if the fixture cannot be encoded
    private static byte[] twoFileArchive() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(output)) {
            writeFile(writer, "first", new byte[]{1, 2, 3});
            writeFile(writer, "second", new byte[]{4});
        }
        return output.toByteArray();
    }

    /// Writes one complete regular-file member.
    ///
    /// @param writer the open streaming writer
    /// @param path the archive-local member path
    /// @param content the complete member body
    /// @throws IOException if the member cannot be written
    private static void writeFile(ArArkivoStreamingWriter writer, String path, byte[] content) throws IOException {
        try (OutputStream body = writer.beginFile(path).openOutputStream()) {
            body.write(content);
        }
    }

    /// Checks that discarding member data and padding uses the same bounded array throughout enumeration.
    @NotNullByDefault
    private static final class DiscardTrackingInputStream extends ByteArrayInputStream {
        /// Inclusive physical start of each member body.
        private final int @Unmodifiable [] starts;

        /// Exclusive physical end of each member, including padding.
        private final int @Unmodifiable [] ends;

        /// First destination used to discard member data or padding.
        private byte @Nullable [] discardBuffer;

        /// Number of reads into the discard buffer.
        private int discardReads;

        /// Creates a source containing short-name members with the supplied body sizes.
        private DiscardTrackingInputStream(byte[] archive, int[] sizes) {
            super(archive);
            starts = new int[sizes.length];
            ends = new int[sizes.length];
            int offset = 8;
            for (int index = 0; index < sizes.length; index++) {
                starts[index] = offset + 60;
                offset = starts[index] + sizes[index] + (sizes[index] & 1);
                ends[index] = offset;
            }
            assertEquals(archive.length, offset);
        }

        /// Returns the current physical source offset.
        private int position() {
            return pos;
        }

        /// Checks body and padding reads without including header reads in the buffer comparison.
        @Override
        public int read(byte[] target, int offset, int length) {
            for (int index = 0; index < starts.length; index++) {
                if (pos >= starts[index] && pos < ends[index]) {
                    assertTrue(length > 0 && length <= 8192);
                    assertTrue(pos + length <= ends[index]);
                    if (discardBuffer == null) discardBuffer = target;
                    else assertSame(discardBuffer, target);
                    discardReads++;
                    break;
                }
            }
            return super.read(target, offset, length);
        }
    }

    /// Supplies a byte array while failing once at an exact read boundary.
    @NotNullByDefault
    private static final class FailOnceInputStream extends InputStream {
        /// Immutable source bytes retained for this test.
        private final byte @Unmodifiable [] bytes;

        /// Offset at which the configured failure is emitted.
        private final int failureOffset;

        /// Failure emitted at the boundary.
        private final Throwable failure;

        /// Current physical source offset.
        private int position;

        /// Whether the failure has already been emitted.
        private boolean failed;

        /// Creates a source with one recoverable boundary failure.
        private FailOnceInputStream(byte[] bytes, int failureOffset, Throwable failure) {
            this.bytes = bytes.clone();
            this.failureOffset = failureOffset;
            this.failure = failure;
        }

        /// Checks the current failure boundary before consuming input.
        private void checkFailure() throws IOException {
            if (!failed && position == failureOffset) {
                failed = true;
                if (failure instanceof IOException exception) {
                    throw exception;
                }
                if (failure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) failure;
            }
        }

        /// Reads one unsigned source byte.
        @Override
        public int read() throws IOException {
            checkFailure();
            return position == bytes.length ? -1 : Byte.toUnsignedInt(bytes[position++]);
        }

        /// Reads no farther than the configured failure boundary.
        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) {
                return 0;
            }
            checkFailure();
            if (position == bytes.length) {
                return -1;
            }
            int count = Math.min(length, bytes.length - position);
            if (!failed) {
                count = Math.min(count, failureOffset - position);
            }
            System.arraycopy(bytes, position, target, offset, count);
            position += count;
            return count;
        }

    }

}
