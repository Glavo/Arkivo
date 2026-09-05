// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemConfig;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemProvider;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoWritableFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies streaming ZIP entry finalization, target closure, and failure-suppression behavior.
@NotNullByDefault
final class ZipStreamingOutputLifecycleTest {
    /// The directory containing generated path-backed archives.
    @TempDir
    Path temporaryDirectory;

    /// Verifies that streaming writer channels become closed after entry validation fails.
    @Test
    void entryChannelCloseFailureLeavesChannelTerminal() throws IOException {
        Path archivePath = temporaryDirectory.resolve("channel-close-failure.zip");
        byte[] content = "bad size".getBytes(StandardCharsets.UTF_8);

        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
            var invalidEntry = writer.beginFile("bad-size.txt");
            ZipArkivoEntryAttributeView view = invalidEntry.attributeView(ZipArkivoEntryAttributeView.class);
            assertNotNull(view);
            view.setUncompressedSizeAndCrc32(content.length + 1L, crc32(content));
            var channel = invalidEntry.openChannel();

            assertEquals(content.length, channel.write(ByteBuffer.wrap(content)));
            IOException exception = assertThrows(IOException.class, channel::close);
            assertTrue(exception.getMessage().contains("configured size"));
            assertFalse(channel.isOpen());
            assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
            channel.close();
            assertFalse(channel.isOpen());
            assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
        }
    }

    /// Verifies that writer finalization closes its target after entry validation fails.
    @Test
    void writerCloseFailureStillClosesTarget() throws IOException {
        CloseTrackingOutputStream archiveOutput = new CloseTrackingOutputStream(true);
        byte[] content = "bad writer close size".getBytes(StandardCharsets.UTF_8);

        ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archiveOutput);
        var invalidEntry = writer.beginFile("bad-size.txt");
        ZipArkivoEntryAttributeView view = invalidEntry.attributeView(ZipArkivoEntryAttributeView.class);
        assertNotNull(view);
        view.setUncompressedSizeAndCrc32(content.length + 1L, crc32(content));
        OutputStream entryOutput = invalidEntry.openOutputStream();
        entryOutput.write(content);

        IOException exception = assertThrows(IOException.class, writer::close);
        assertTrue(exception.getMessage().contains("configured size"));
        assertTrue(archiveOutput.closed());
        assertEquals(1, exception.getSuppressed().length);
        assertTrue(exception.getSuppressed()[0].getMessage().contains("close failed"));
    }

    /// Verifies that a close-action failure does not mask an output close failure.
    @Test
    void closeActionFailureIsSuppressedWhenOutputCloseFails() {
        CloseTrackingOutputStream archiveOutput = new CloseTrackingOutputStream(true);
        ZipArkivoWritableFileSystemImpl fileSystem = new ZipArkivoWritableFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                archiveOutput,
                ZipArkivoFileSystemConfig.DEFAULTS,
                () -> {
                    throw new IllegalStateException("close action failed");
                }
        );

        IOException exception = assertThrows(IOException.class, fileSystem::close);

        assertEquals("close failed", exception.getMessage());
        assertTrue(archiveOutput.closed());
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("close action failed", exception.getSuppressed()[0].getMessage());
    }

    /// Verifies that close-action failure is preserved after a runtime output close failure.
    @Test
    void closeActionFailureIsSuppressedAfterRuntimeOutputCloseFailure() {
        RuntimeCloseFailingOutputStream archiveOutput = new RuntimeCloseFailingOutputStream(
                new IllegalStateException("close failed")
        );
        boolean[] closeActionRan = new boolean[1];
        ZipArkivoWritableFileSystemImpl fileSystem = new ZipArkivoWritableFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                archiveOutput,
                ZipArkivoFileSystemConfig.DEFAULTS,
                () -> {
                    closeActionRan[0] = true;
                    throw new IllegalStateException("close action failed");
                }
        );

        RuntimeException exception = assertThrows(RuntimeException.class, fileSystem::close);

        assertEquals("close failed", exception.getMessage());
        assertTrue(archiveOutput.closed());
        assertTrue(closeActionRan[0]);
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("close action failed", exception.getSuppressed()[0].getMessage());
        IllegalStateException retryFailure = assertThrows(IllegalStateException.class, fileSystem::close);
        assertEquals("close failed", retryFailure.getMessage());
    }

    /// Verifies one shared output-close and close-action failure remains primary without self-suppression.
    @Test
    void preservesSharedOutputAndCloseActionFailure() {
        IllegalStateException sharedFailure = new IllegalStateException("shared close failure");
        RuntimeCloseFailingOutputStream archiveOutput = new RuntimeCloseFailingOutputStream(sharedFailure);
        boolean[] closeActionRan = new boolean[1];
        ZipArkivoWritableFileSystemImpl fileSystem = new ZipArkivoWritableFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                archiveOutput,
                ZipArkivoFileSystemConfig.DEFAULTS,
                () -> {
                    closeActionRan[0] = true;
                    throw sharedFailure;
                }
        );

        RuntimeException exception = assertThrows(RuntimeException.class, fileSystem::close);

        assertSame(sharedFailure, exception);
        assertEquals(0, exception.getSuppressed().length);
        assertTrue(archiveOutput.closed());
        assertTrue(closeActionRan[0]);
    }

    /// Verifies that close-action failure is preserved after a runtime entry finalization failure.
    @Test
    void closeActionFailureIsSuppressedAfterRuntimeEntryCloseFailure() throws IOException {
        RuntimeWriteFailingOutputStream archiveOutput = new RuntimeWriteFailingOutputStream();
        boolean[] closeActionRan = new boolean[1];
        ZipArkivoWritableFileSystemImpl fileSystem = new ZipArkivoWritableFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                archiveOutput,
                ZipArkivoFileSystemConfig.DEFAULTS,
                () -> {
                    closeActionRan[0] = true;
                    throw new IllegalStateException("close action failed");
                }
        );

        OutputStream entryOutput = fileSystem.newOutputStream(fileSystem.getPath("/runtime.txt"));
        archiveOutput.failWrites();

        RuntimeException exception = assertThrows(RuntimeException.class, fileSystem::close);

        assertEquals("write failed", exception.getMessage());
        assertTrue(archiveOutput.closed());
        assertTrue(closeActionRan[0]);
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("close action failed", exception.getSuppressed()[0].getMessage());
        assertThrows(IOException.class, () -> entryOutput.write(1));
        fileSystem.close();
    }

    /// Returns the unsigned CRC-32 value of the given content.
    ///
    /// @param content the bytes to checksum
    /// @return the unsigned 32-bit checksum in a `long`
    private static long crc32(byte @Unmodifiable [] content) {
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        return crc32.getValue();
    }

    /// Output stream that records closure and optionally reports an I/O failure.
    @NotNullByDefault
    private static final class CloseTrackingOutputStream extends ByteArrayOutputStream {
        /// Whether closure reports an I/O failure.
        private final boolean failClose;

        /// Whether close has been attempted.
        private boolean closed;

        /// Creates a close-tracking output stream.
        ///
        /// @param failClose whether closure must fail
        private CloseTrackingOutputStream(boolean failClose) {
            this.failClose = failClose;
        }

        /// Records closure and reports the configured failure.
        ///
        /// @throws IOException when close failure is enabled
        @Override
        public void close() throws IOException {
            closed = true;
            if (failClose) {
                throw new IOException("close failed");
            }
            super.close();
        }

        /// Returns whether close has been attempted.
        ///
        /// @return `true` after the first close call
        private boolean closed() {
            return closed;
        }
    }

    /// Output stream that can fail writes at runtime and records closure.
    @NotNullByDefault
    private static final class RuntimeWriteFailingOutputStream extends ByteArrayOutputStream {
        /// Whether writes must fail.
        private boolean failWrites;

        /// Whether close has been attempted.
        private boolean closed;

        /// Enables runtime write failures.
        private void failWrites() {
            failWrites = true;
        }

        /// Writes one byte unless failures are enabled.
        ///
        /// @param value the byte value
        @Override
        public synchronized void write(int value) {
            ensureWritesAllowed();
            super.write(value);
        }

        /// Writes bytes unless failures are enabled.
        ///
        /// @param bytes the source bytes
        /// @param offset the source offset
        /// @param length the byte count
        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            ensureWritesAllowed();
            super.write(bytes, offset, length);
        }

        /// Records that this output stream has closed.
        @Override
        public void close() {
            closed = true;
        }

        /// Returns whether close has been attempted.
        ///
        /// @return `true` after the first close call
        private boolean closed() {
            return closed;
        }

        /// Throws when runtime write failures are enabled.
        private void ensureWritesAllowed() {
            if (failWrites) {
                throw new IllegalStateException("write failed");
            }
        }
    }

    /// Output stream that records closure and then fails at runtime.
    @NotNullByDefault
    private static final class RuntimeCloseFailingOutputStream extends ByteArrayOutputStream {
        /// Failure reported by every close attempt.
        private final RuntimeException failure;

        /// Whether close has been attempted.
        private boolean closed;

        /// Creates a close-failing output stream that reports the supplied failure.
        private RuntimeCloseFailingOutputStream(RuntimeException failure) {
            this.failure = failure;
        }

        /// Records closure and throws the configured failure.
        @Override
        public void close() {
            closed = true;
            throw failure;
        }

        /// Returns whether close has been attempted.
        ///
        /// @return `true` after the first close call
        private boolean closed() {
            return closed;
        }
    }
}
