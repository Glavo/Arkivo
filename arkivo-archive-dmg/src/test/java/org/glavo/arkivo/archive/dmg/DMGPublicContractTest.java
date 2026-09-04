// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.dmg.internal.DMGArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.createHFSPlusDisk;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.readFully;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.sector;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.writeImage;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.writeRawImage;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies public DMG ownership, descriptor validation, and NIO file-system infrastructure contracts.
@NotNullByDefault
final class DMGPublicContractTest {
    /// Temporary directory used for generated flattened UDIF images.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies a single-channel image starts at the borrowed channel position and owns the physical channel.
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

    /// Verifies the default file-system channel factory honors its origin and closes the transferred source.
    @Test
    void opensFileSystemFromOwnedChannelAtCurrentPosition() throws IOException {
        Path imagePath = writeRawImage(
                temporaryDirectory.resolve("single-channel-filesystem.dmg"),
                createHFSPlusDisk()
        );
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

    /// Verifies partition descriptors reject negative geometry and require a partitioning scheme.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesPartitionDescriptors() {
        DMGPartition partition = new DMGPartition(0, 0L, 0L, null, null, DMGPartitionScheme.RAW);
        assertEquals(0, partition.index());
        assertEquals(DMGPartitionScheme.RAW, partition.scheme());

        assertThrows(
                IllegalArgumentException.class,
                () -> new DMGPartition(-1, 0L, 0L, null, null, DMGPartitionScheme.RAW)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new DMGPartition(0, -1L, 0L, null, null, DMGPartitionScheme.RAW)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new DMGPartition(0, 0L, -1L, null, null, DMGPartitionScheme.RAW)
        );
        assertThrows(
                NullPointerException.class,
                () -> new DMGPartition(0, 0L, 0L, null, null, null)
        );
    }

    /// Verifies path-provider options, NIO infrastructure, principals, matchers, and read-only mutations.
    @Test
    void exposesProviderAndFileSystemContracts() throws IOException {
        Path imagePath = writeRawImage(
                temporaryDirectory.resolve("provider-contract.dmg"),
                createHFSPlusDisk()
        );
        DMGArkivoFileSystemProvider provider = new DMGArkivoFileSystemProvider();
        Map<String, Object> environment = Map.of(
                "arkivo.dmg.partitionIndex", 0,
                "arkivo.threadSafety", "strict"
        );

        try (DMGArkivoFileSystem fileSystem = provider.newFileSystem(imagePath, environment)) {
            Path root = fileSystem.getPath("/");
            Path file = fileSystem.getPath("/hello.txt");
            assertSame(provider, fileSystem.provider());
            assertEquals(DMGArkivoFileSystemProvider.SCHEME, provider.getScheme());
            assertEquals(ArkivoFileSystemThreadSafety.STRICT, fileSystem.threadSafety());
            assertEquals("/", fileSystem.getSeparator());

            Iterator<Path> roots = fileSystem.getRootDirectories().iterator();
            assertEquals(root, roots.next());
            assertFalse(roots.hasNext());
            Iterator<FileStore> stores = fileSystem.getFileStores().iterator();
            FileStore store = stores.next();
            assertFalse(stores.hasNext());
            assertSame(store, Files.getFileStore(file));

            assertTrue(fileSystem.getPathMatcher("glob:**/*.txt").matches(file));
            assertFalse(fileSystem.getPathMatcher("glob:**/*.bin").matches(file));
            assertTrue(fileSystem.getPathMatcher("regex:.*/hello\\.txt").matches(file));
            assertEquals(
                    "501",
                    fileSystem.getUserPrincipalLookupService().lookupPrincipalByName("501").getName()
            );
            assertEquals(
                    "20",
                    fileSystem.getUserPrincipalLookupService().lookupPrincipalByGroupName("20").getName()
            );
            UnsupportedOperationException watchFailure = assertThrows(
                    UnsupportedOperationException.class,
                    fileSystem::newWatchService
            );
            assertEquals("DMG watch services are not supported", watchFailure.getMessage());

            assertThrows(ReadOnlyFileSystemException.class, () -> Files.newOutputStream(file));
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> Files.createDirectory(fileSystem.getPath("/created"))
            );
            assertThrows(ReadOnlyFileSystemException.class, () -> Files.delete(file));
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> Files.move(file, fileSystem.getPath("/renamed.txt"))
            );
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> Files.setAttribute(file, "basic:lastModifiedTime", FileTime.fromMillis(1L))
            );
        }

        assertThrows(
                UnsupportedOperationException.class,
                () -> provider.newFileSystem(
                        imagePath,
                        Map.of("arkivo.openOptions", Set.of(StandardOpenOption.WRITE))
                )
        );
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
}
