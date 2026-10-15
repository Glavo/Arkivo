// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.codec.deflate.ZlibCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.readFully;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.sector;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.writeImage;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies DMG image source positioning, ownership, failure cleanup, and repeatable access.
@NotNullByDefault
final class DMGImageSourceLifecycleTest {
    /// Temporary directory used for generated flattened UDIF images.
    @TempDir
    private Path temporaryDirectory;

    /// Resolves independent fork, table, and run offsets for raw and compressed data through repeated opens.
    @ParameterizedTest
    @CsvSource({"0,0,0", "37,0,0", "512,512,0", "37,113,59", "0,0,59", "37,0,59", "0,113,0", "0,113,59"})
    void resolvesDataForkRelativeOffsets(int forkOffset, int tableOffset, int runOffset) throws IOException {
        byte[] expected = sector(71);
        for (boolean compressed : new boolean[]{false, true}) {
            byte[] encoded = compressed ? DMGTestFixtures.compress(ZlibCodec.DEFAULT, expected) : expected;
            int type = compressed ? DMGTestFixtures.ZLIB_RUN : DMGTestFixtures.RAW_RUN;
            Path path = DMGTestFixtures.writeImage(temporaryDirectory.resolve(compressed + ".dmg"),
                    List.of(new DMGTestFixtures.Run(type, encoded)), forkOffset, tableOffset, runOffset);
            try (DMGImage image = DMGImage.open(path)) {
                assertEquals(expected.length, image.size());
                for (int attempt = 0; attempt < 2; attempt++) {
                    try (SeekableByteChannel channel = image.openChannel()) {
                        ByteBuffer output = ByteBuffer.allocate(expected.length);
                        readFully(channel, output);
                        assertArrayEquals(expected, output.array());
                    }
                }
            }
        }
    }

    /// Verifies a single-channel image starts at the transferred channel position and closes the physical channel.
    @Test
    void opensOwnedSingleChannelFromCurrentPosition() throws IOException {
        byte[] expected = sector(17);
        Path imagePath = writeImage(
                temporaryDirectory.resolve("single-channel-source.dmg"),
                List.of(new DMGTestFixtures.Run(DMGTestFixtures.RAW_RUN, expected))
        );
        byte[] imageBytes = Files.readAllBytes(imagePath);
        byte[] prefixedImage = new byte[31 + imageBytes.length];
        Arrays.fill(prefixedImage, 0, 31, (byte) 0x5a);
        System.arraycopy(imageBytes, 0, prefixedImage, 31, imageBytes.length);
        Path container = Files.write(temporaryDirectory.resolve("prefixed-image.bin"), prefixedImage);

        SeekableByteChannel source = Files.newByteChannel(container, StandardOpenOption.READ);
        source.position(31L);
        DMGImage image = DMGImage.open(source);
        try (image; SeekableByteChannel decoded = image.openChannel()) {
            assertTrue(source.isOpen());
            assertEquals(expected.length, image.size());
            ByteBuffer target = ByteBuffer.allocate(expected.length);
            readFully(decoded, target);
            assertArrayEquals(expected, target.array());
        }
        assertFalse(source.isOpen());
    }

    /// Verifies configured single-channel setup failures close the transferred source.
    @Test
    void closesOwnedSingleChannelAfterConfiguredOpenFailure() throws IOException {
        Path imagePath = writeImage(
                temporaryDirectory.resolve("limited-single-channel.dmg"),
                List.of(new DMGTestFixtures.Run(DMGTestFixtures.RAW_RUN, sector(19)))
        );
        ArchiveReadOptions options = ArchiveReadOptions.DEFAULT.withLimits(
                ArchiveReadLimits.builder().maximumDecodedArchiveSize(511L).build()
        );
        SeekableByteChannel source = Files.newByteChannel(imagePath, StandardOpenOption.READ);

        assertThrows(IOException.class, () -> DMGImage.open(source, options));

        assertFalse(source.isOpen());
    }

    /// Verifies source cleanup failure is suppressed without replacing the primary channel-open failure.
    @Test
    void suppressesSourceCleanupFailureAfterOpenFailure() {
        IOException openFailure = new IOException("source open failure");
        IOException closeFailure = new IOException("source close failure");
        FailingSource source = new FailingSource(openFailure, closeFailure);

        IOException actual = assertThrows(IOException.class, () -> DMGImage.open(source));

        assertSame(openFailure, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertSame(closeFailure, actual.getSuppressed()[0]);
        assertEquals(1, source.openCount());
        assertEquals(1, source.closeCount());
    }

    /// Verifies cleanup does not attempt to suppress an exception onto itself.
    @Test
    void avoidsSelfSuppressionWhenOpenAndCloseShareFailure() {
        IOException sharedFailure = new IOException("shared source failure");
        FailingSource source = new FailingSource(sharedFailure, sharedFailure);

        IOException actual = assertThrows(IOException.class, () -> DMGImage.open(source));

        assertSame(sharedFailure, actual);
        assertEquals(0, actual.getSuppressed().length);
        assertEquals(1, source.openCount());
        assertEquals(1, source.closeCount());
    }

    /// Preserves parser and cleanup failures at both channel-opening stages without closing an owner twice.
    @ParameterizedTest
    @ValueSource(strings = {"size", "metadata", "partition", "close"})
    void preservesPhysicalChannelFailures(String phase) throws IOException {
        Path path = writeImage(temporaryDirectory.resolve("channel-failure.dmg"),
                List.of(new DMGTestFixtures.Run(DMGTestFixtures.RAW_RUN, sector(73)),
                        new DMGTestFixtures.Run(DMGTestFixtures.RAW_RUN, sector(74))));
        for (int kind = 0; kind < 3; kind++) {
            for (boolean shared : new boolean[]{false, true}) {
                for (boolean fileSystem : new boolean[]{false, true}) {
                    Throwable failure = switch (kind) {
                        case 0 -> new IOException("Physical channel I/O failed");
                        case 1 -> new IllegalStateException("Physical channel I/O failed");
                        default -> new AssertionError("Physical channel I/O failed");
                    };
                    Throwable cleanup = shared ? failure : new IOException("Physical channel cleanup failed");
                    ChannelFailureSource source = new ChannelFailureSource(path, phase, failure, cleanup);
                    Throwable actual = assertThrows(Throwable.class, () -> {
                        if (fileSystem) {
                            DMGArkivoFileSystem.open(source).close();
                        } else {
                            DMGImage.open(source).close();
                        }
                    });
                    assertSame(phase.equals("close") ? cleanup : failure, actual);
                    boolean wrappedCleanup = shared && phase.equals("partition");
                    assertEquals(phase.equals("close") || shared && !wrappedCleanup ? 0 : 1,
                            actual.getSuppressed().length);
                    if (wrappedCleanup) {
                        assertSame(cleanup, actual.getSuppressed()[0].getCause());
                    } else if (!shared && !phase.equals("close")) {
                        assertSame(cleanup, actual.getSuppressed()[0]);
                    }
                    assertEquals(phase.equals("size") || phase.equals("metadata") ? 1 : 2, source.opens);
                    assertEquals(source.opens, source.channelCloses);
                    assertEquals(1, source.sourceCloses);
                }
            }
        }
    }

    /// Injects failures into either layout parsing or decoded partition probing.
    @NotNullByDefault
    private static final class ChannelFailureSource implements ArkivoSeekableChannelSource {
        /// The valid encoded image used before the injected failure.
        private final Path path;
        /// The operation that reports the primary failure.
        private final String phase;
        /// The primary failure instance.
        private final Throwable failure;
        /// The physical channel's cleanup failure instance.
        private final Throwable cleanup;
        /// Number of opened physical channels.
        private int opens;
        /// Number of physical channel close calls.
        private int channelCloses;
        /// Number of source close calls.
        private int sourceCloses;

        /// Selects the failing operation and exception identities.
        private ChannelFailureSource(Path path, String phase, Throwable failure, Throwable cleanup) {
            this.path = path;
            this.phase = phase;
            this.failure = failure;
            this.cleanup = cleanup;
        }

        @Override
        public SeekableByteChannel openChannel() throws IOException {
            int number = ++opens;
            boolean failing = number == (phase.equals("partition") || phase.equals("close") ? 2 : 1);
            return new Channel(Files.newByteChannel(path), failing);
        }

        @Override
        public void close() {
            sourceCloses++;
        }

        /// Retains ordinary positioning while failing the selected read, size, or close operation.
        @NotNullByDefault
        private final class Channel implements SeekableByteChannel {
            /// The independently positioned physical file.
            private final SeekableByteChannel delegate;
            /// Whether this is the channel selected for injected failures.
            private final boolean failing;

            /// Wraps one physical channel.
            private Channel(SeekableByteChannel delegate, boolean failing) {
                this.delegate = delegate;
                this.failing = failing;
            }

            @Override
            public int read(ByteBuffer target) throws IOException {
                if (failing && (phase.equals("metadata") || phase.equals("partition"))) {
                    throwFailure(failure);
                }
                return delegate.read(target);
            }

            @Override
            public int write(ByteBuffer source) throws IOException {
                return delegate.write(source);
            }

            @Override
            public long position() throws IOException {
                return delegate.position();
            }

            @Override
            public SeekableByteChannel position(long position) throws IOException {
                delegate.position(position);
                return this;
            }

            @Override
            public long size() throws IOException {
                if (failing && phase.equals("size")) {
                    throwFailure(failure);
                }
                return delegate.size();
            }

            @Override
            public SeekableByteChannel truncate(long size) throws IOException {
                delegate.truncate(size);
                return this;
            }

            @Override
            public boolean isOpen() {
                return delegate.isOpen();
            }

            @Override
            public void close() throws IOException {
                channelCloses++;
                delegate.close();
                if (failing) {
                    throwFailure(cleanup);
                }
            }
        }

        /// Throws an injected failure without changing its type or identity.
        private static void throwFailure(Throwable failure) throws IOException {
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            throw (Error) failure;
        }
    }

    /// Verifies the default repeatable-source overload retains ownership until image closure.
    @Test
    void ownsRepeatableSourceAcrossDerivedChannels() throws IOException {
        byte[] expected = sector(23);
        Path imagePath = writeImage(
                temporaryDirectory.resolve("repeatable-source.dmg"),
                List.of(new DMGTestFixtures.Run(DMGTestFixtures.RAW_RUN, expected))
        );
        TrackingSource source = new TrackingSource(imagePath);

        try (DMGImage image = DMGImage.open(source)) {
            assertFalse(source.isClosed());
            try (SeekableByteChannel decoded = image.openChannel()) {
                ByteBuffer target = ByteBuffer.allocateDirect(expected.length);
                readFully(decoded, target);
                target.flip();
                byte[] actual = new byte[target.remaining()];
                target.get(actual);
                assertArrayEquals(expected, actual);
            }
            assertTrue(source.openChannelCount() >= 3);
        }

        assertTrue(source.isClosed());
        assertEquals(1, source.closeCount());
    }

    /// Opens path-backed channels and records source lifecycle operations.
    @NotNullByDefault
    private static final class TrackingSource implements ArkivoSeekableChannelSource {
        /// Path containing immutable image bytes.
        private final Path path;

        /// Number of channels opened by the image.
        private int openChannelCount;

        /// Number of source close calls received.
        private int closeCount;

        /// Whether source closure has completed.
        private boolean closed;

        /// Creates an open repeatable source for the supplied image path.
        private TrackingSource(Path path) {
            this.path = path;
        }

        /// Opens an independent image channel while the source remains open.
        @Override
        public SeekableByteChannel openChannel() throws IOException {
            if (closed) {
                throw new ClosedChannelException();
            }
            openChannelCount++;
            return Files.newByteChannel(path, StandardOpenOption.READ);
        }

        /// Closes this source idempotently while recording every call.
        @Override
        public void close() {
            closeCount++;
            closed = true;
        }

        /// Returns the number of image channels opened so far.
        private int openChannelCount() {
            return openChannelCount;
        }

        /// Returns the number of source close calls received.
        private int closeCount() {
            return closeCount;
        }

        /// Returns whether source closure has completed.
        private boolean isClosed() {
            return closed;
        }
    }

    /// Fails channel creation and source cleanup with caller-supplied exception instances.
    @NotNullByDefault
    private static final class FailingSource implements ArkivoSeekableChannelSource {
        /// Failure thrown by every channel-open attempt.
        private final IOException openFailure;

        /// Failure thrown by every source-close attempt.
        private final IOException closeFailure;

        /// Number of channel-open attempts.
        private int openCount;

        /// Number of source-close attempts.
        private int closeCount;

        /// Creates a source with independently selectable open and close failures.
        ///
        /// @param openFailure failure thrown when opening a channel
        /// @param closeFailure failure thrown when closing the source
        private FailingSource(IOException openFailure, IOException closeFailure) {
            this.openFailure = openFailure;
            this.closeFailure = closeFailure;
        }

        /// Records one attempt and throws the configured channel-open failure.
        @Override
        public SeekableByteChannel openChannel() throws IOException {
            openCount++;
            throw openFailure;
        }

        /// Records one attempt and throws the configured source-close failure.
        @Override
        public void close() throws IOException {
            closeCount++;
            throw closeFailure;
        }

        /// Returns the number of channel-open attempts.
        private int openCount() {
            return openCount;
        }

        /// Returns the number of source-close attempts.
        private int closeCount() {
            return closeCount;
        }
    }
}
