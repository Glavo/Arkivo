// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.createHFSPlusDisk;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.writeRawImage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies DMG file-system source ownership, terminal closure, and cleanup retry behavior.
@NotNullByDefault
final class DMGArkivoFileSystemLifecycleTest {
    /// The directory containing each generated disk image.
    @TempDir
    Path temporaryDirectory;

    /// Verifies the channel factory honors its origin and closes the transferred source with the file system.
    @Test
    void opensFromOwnedChannelAtCurrentPosition() throws IOException {
        Path imagePath = createImage("single-channel-filesystem.dmg");
        byte[] imageBytes = Files.readAllBytes(imagePath);
        int imageOffset = 29;
        byte[] prefixedImage = new byte[imageOffset + imageBytes.length];
        Arrays.fill(prefixedImage, 0, imageOffset, (byte) 0x6b);
        System.arraycopy(imageBytes, 0, prefixedImage, imageOffset, imageBytes.length);
        Path container = Files.write(temporaryDirectory.resolve("prefixed-filesystem.bin"), prefixedImage);

        SeekableByteChannel source = Files.newByteChannel(container, StandardOpenOption.READ);
        source.position(imageOffset);
        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(source)) {
            assertTrue(source.isOpen());
            assertEquals("hello", Files.readString(fileSystem.getPath("/hello.txt")));
            assertThrows(UnsupportedOperationException.class, () -> fileSystem.getPath("/").toUri());
        }
        assertFalse(source.isOpen());
    }

    /// Closes channels owned by a file system and rejects subsequent path access.
    @Test
    void closesManagedResources() throws IOException {
        Path imagePath = createImage("close.dmg");
        DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(imagePath);
        Path file = fileSystem.getPath("/hello.txt");
        SeekableByteChannel channel = Files.newByteChannel(file);

        fileSystem.close();
        fileSystem.close();
        assertFalse(fileSystem.isOpen());
        assertFalse(channel.isOpen());
        assertThrows(ClosedFileSystemException.class, () -> Files.size(file));
    }

    /// Verifies a failed source close makes the file system terminal while allowing cleanup to be retried.
    @Test
    void retriesIncompleteSourceCleanup() throws IOException {
        Path imagePath = createImage("retry-close.dmg");
        RetryingCloseSource source = new RetryingCloseSource(imagePath);
        DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(source);

        IOException failure = assertThrows(IOException.class, fileSystem::close);
        assertEquals("source close failure", failure.getMessage());
        assertFalse(fileSystem.isOpen());
        assertFalse(source.closed);
        assertEquals(1, source.closeAttempts);
        assertThrows(ClosedFileSystemException.class, fileSystem::partition);

        fileSystem.close();
        fileSystem.close();
        assertTrue(source.closed);
        assertEquals(2, source.closeAttempts);
    }

    /// Writes the shared generated HFS Plus disk as one flattened UDIF image.
    ///
    /// @param name the image file name within the temporary directory
    /// @return the written image path
    /// @throws IOException if the fixture cannot be written
    private Path createImage(String name) throws IOException {
        return writeRawImage(temporaryDirectory.resolve(name), createHFSPlusDisk());
    }

    /// Opens repeatable path channels and fails its first cleanup attempt without completing it.
    @NotNullByDefault
    private static final class RetryingCloseSource implements ArkivoSeekableChannelSource {
        /// The immutable fixture path.
        private final Path path;

        /// Number of close attempts.
        private int closeAttempts;

        /// Whether source cleanup completed.
        private boolean closed;

        /// Creates a repeatable source for the supplied fixture.
        ///
        /// @param path the fixture opened by each derived channel
        private RetryingCloseSource(Path path) {
            this.path = path;
        }

        /// Opens one independently positioned fixture channel.
        ///
        /// @return a new readable fixture channel
        /// @throws IOException if this source has closed or the fixture cannot be opened
        @Override
        public SeekableByteChannel openChannel() throws IOException {
            if (closed) {
                throw new IOException("source is closed");
            }
            return Files.newByteChannel(path);
        }

        /// Fails once while remaining open and completes cleanup on the next attempt.
        ///
        /// @throws IOException on the first close attempt
        @Override
        public void close() throws IOException {
            closeAttempts++;
            if (closed) {
                return;
            }
            if (closeAttempts == 1) {
                throw new IOException("source close failure");
            }
            closed = true;
        }
    }
}
