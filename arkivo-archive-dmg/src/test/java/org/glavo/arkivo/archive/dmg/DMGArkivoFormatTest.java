// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the public DMG format descriptor and trailer-based detection contract.
@NotNullByDefault
public final class DMGArkivoFormatTest {
    /// Verifies descriptor metadata and the documented absence of prefix-only detection.
    @Test
    public void describesDmgArchives() {
        DMGArkivoFormat format = DMGArkivoFormat.instance();
        ByteBuffer prefix = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        prefix.position(2).mark().position(3);

        assertSame(format, DMGArkivoFormat.instance());
        assertEquals(DMGArkivoFormat.NAME, format.name());
        assertEquals(List.of("dmg"), format.fileExtensions());
        assertEquals(512, format.probeSize());
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.FileSystem);
        assertFalse(format.matches(prefix));
        assertEquals(3, prefix.position());
        assertSame(ByteOrder.LITTLE_ENDIAN, prefix.order());
        prefix.reset();
        assertEquals(2, prefix.position());
    }

    /// Verifies seekable detection reads the terminal `koly` signature and restores the borrowed position.
    ///
    /// @param directory the temporary directory used for generated probe files
    @Test
    public void detectsTerminalKolySignature(@TempDir Path directory) throws IOException {
        DMGArkivoFormat format = DMGArkivoFormat.instance();
        byte[] image = new byte[600];
        ByteArrayAccess.writeIntBigEndian(image, image.length - format.probeSize(), 0x6b6f6c79);
        Path valid = Files.write(directory.resolve("valid.dmg"), image);

        try (SeekableByteChannel source = Files.newByteChannel(valid, StandardOpenOption.READ)) {
            source.position(17L);
            assertTrue(format.matches(source));
            assertEquals(17L, source.position());
        }

        image[image.length - format.probeSize()] ^= 1;
        Path wrong = Files.write(directory.resolve("wrong.dmg"), image);
        try (SeekableByteChannel source = Files.newByteChannel(wrong, StandardOpenOption.READ)) {
            source.position(11L);
            assertFalse(format.matches(source));
            assertEquals(11L, source.position());
        }

        Path shortImage = Files.write(directory.resolve("short.dmg"), new byte[511]);
        try (SeekableByteChannel source = Files.newByteChannel(shortImage, StandardOpenOption.READ)) {
            assertFalse(format.matches(source));
            assertEquals(0L, source.position());
        }
    }

    /// Verifies generic path, direct-channel, and repeatable-source factories read a generated HFS Plus image.
    ///
    /// @param directory the temporary directory used for the generated image
    @Test
    public void opensFileSystemsThroughGenericFactories(@TempDir Path directory) throws IOException {
        DMGArkivoFormat format = DMGArkivoFormat.instance();
        Path image = DMGTestFixtures.writeRawImage(
                directory.resolve("filesystem.dmg"),
                DMGTestFixtures.createHFSPlusDisk()
        );

        try (var fileSystem = format.open(image)) {
            assertEquals("hello", Files.readString(fileSystem.getPath("/hello.txt")));
        }
        try (var fileSystem = format.open(image, ArchiveReadOptions.DEFAULT)) {
            assertEquals("hello", Files.readString(fileSystem.getPath("/hello.txt")));
        }

        SeekableByteChannel defaultChannel = Files.newByteChannel(image, StandardOpenOption.READ);
        try (var fileSystem = format.open(defaultChannel)) {
            assertEquals("hello", Files.readString(fileSystem.getPath("/hello.txt")));
        }
        assertFalse(defaultChannel.isOpen());

        SeekableByteChannel configuredChannel = Files.newByteChannel(image, StandardOpenOption.READ);
        try (var fileSystem = format.open(configuredChannel, ArchiveReadOptions.DEFAULT)) {
            assertEquals("hello", Files.readString(fileSystem.getPath("/hello.txt")));
        }
        assertFalse(configuredChannel.isOpen());

        SeekableByteChannel repeatableBacking = Files.newByteChannel(image, StandardOpenOption.READ);
        ArkivoSeekableChannelSource repeatableSource = ArkivoSeekableChannelSource.of(repeatableBacking);
        try (var fileSystem = format.open(repeatableSource, ArchiveReadOptions.DEFAULT)) {
            assertEquals("hello", Files.readString(fileSystem.getPath("/hello.txt")));
        }
        assertFalse(repeatableBacking.isOpen());
    }

    /// Verifies truncated, stalled, failed, and restoration-failing seekable probes retain their failure semantics.
    @Test
    public void handlesSeekableProbeFailures() throws IOException {
        DMGArkivoFormat format = DMGArkivoFormat.instance();

        FaultingProbeChannel truncated = new FaultingProbeChannel(-1, null, null);
        assertFalse(format.matches(truncated));
        assertEquals(17L, truncated.position());

        FaultingProbeChannel stalled = new FaultingProbeChannel(0, null, null);
        IOException noProgress = assertThrows(IOException.class, () -> format.matches(stalled));
        assertEquals("DMG format probe made no progress", noProgress.getMessage());
        assertEquals(17L, stalled.position());

        IOException readFailure = new IOException("read failed");
        FaultingProbeChannel failed = new FaultingProbeChannel(
                0,
                readFailure,
                new IOException("restore failed")
        );
        IOException propagated = assertThrows(IOException.class, () -> format.matches(failed));
        assertSame(readFailure, propagated);
        assertEquals(1, propagated.getSuppressed().length);
        assertEquals("restore failed", propagated.getSuppressed()[0].getMessage());

        FaultingProbeChannel restorationOnly = new FaultingProbeChannel(
                -1,
                null,
                new IOException("restore failed")
        );
        IOException restoreFailure = assertThrows(IOException.class, () -> format.matches(restorationOnly));
        assertEquals("restore failed", restoreFailure.getMessage());

        IOException sharedFailure = new IOException("shared failure");
        FaultingProbeChannel shared = new FaultingProbeChannel(0, sharedFailure, sharedFailure);
        IOException sharedResult = assertThrows(IOException.class, () -> format.matches(shared));
        assertSame(sharedFailure, sharedResult);
        assertEquals(0, sharedResult.getSuppressed().length);
    }

    /// Verifies null probe inputs fail at the public boundary.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void rejectsNullProbeInputs() {
        DMGArkivoFormat format = DMGArkivoFormat.instance();
        assertThrows(NullPointerException.class, () -> format.matches((ByteBuffer) null));
        assertThrows(NullPointerException.class, () -> format.matches((SeekableByteChannel) null));
    }

    /// Seekable probe source with configurable read and position-restoration failures.
    @NotNullByDefault
    private static final class FaultingProbeChannel implements SeekableByteChannel {
        /// The result returned by reads when no explicit failure is configured.
        private final int readResult;

        /// The explicit read failure, or `null` when reads return [#readResult].
        private final @Nullable IOException readFailure;

        /// Failure thrown by the second position change, or `null` when restoration succeeds.
        private final @Nullable IOException restorationFailure;

        /// The current channel position.
        private long position = 17L;

        /// The number of position changes attempted by the probe.
        private int positionChangeCount;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Creates a fault-injecting probe channel.
        ///
        /// @param readResult the read result used when `readFailure` is `null`
        /// @param readFailure the failure thrown by reads, or `null`
        /// @param restorationFailure the failure thrown while restoring the initial position, or `null`
        private FaultingProbeChannel(
                int readResult,
                @Nullable IOException readFailure,
                @Nullable IOException restorationFailure
        ) {
            this.readResult = readResult;
            this.readFailure = readFailure;
            this.restorationFailure = restorationFailure;
        }

        /// Returns the configured read result or throws the configured failure.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            if (readFailure != null) {
                throw readFailure;
            }
            return readResult;
        }

        /// Rejects writes because the fault-injecting source is read-only.
        @Override
        public int write(ByteBuffer source) {
            throw new NonWritableChannelException();
        }

        /// Returns the simulated current position.
        @Override
        public long position() {
            return position;
        }

        /// Changes the simulated position or fails the restoration attempt.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            if (restorationFailure != null && positionChangeCount > 0) {
                throw restorationFailure;
            }
            positionChangeCount++;
            position = newPosition;
            return this;
        }

        /// Returns a size large enough to contain a UDIF trailer.
        @Override
        public long size() {
            return 600L;
        }

        /// Rejects truncation because the fault-injecting source is read-only.
        @Override
        public SeekableByteChannel truncate(long size) {
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
    }
}
