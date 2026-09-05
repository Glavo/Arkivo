// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
