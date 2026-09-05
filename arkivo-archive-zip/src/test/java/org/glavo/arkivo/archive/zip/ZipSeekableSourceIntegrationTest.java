// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.createTemporaryArchivePath;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.emptyZipWithPreamble;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.singleEntryZipWithPreambleAndAdjustedOffsets;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.singleStoredZipArchive;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.updateSourceZip;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests repeatable seekable ZIP sources, update publication, and ownership cleanup.
@NotNullByDefault
public final class ZipSeekableSourceIntegrationTest {
    /// Verifies public preamble channels retain progress delivered by a failing archive source.
    @ParameterizedTest
    @ValueSource(ints = {0, 2, 4})
    public void preambleReadRetainsPartialSourceProgress(int count) throws IOException {
        byte[] preamble = {1, 2, 3, 4};
        TestSeekableChannelSource source = new TestSeekableChannelSource(emptyZipWithPreamble(preamble));
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(source);
             SeekableByteChannel channel = fileSystem.openPreambleChannel()) {
            IOException failure = new IOException("partial source read failed");
            source.readFailure = failure;
            source.bytesBeforeFailure = count;
            ByteBuffer target = ByteBuffer.allocateDirect(8).position(2).limit(7);
            assertSame(failure, assertThrows(IOException.class, () -> channel.read(target)));
            assertEquals(2 + count, target.position());
            assertEquals(7, target.limit());
            assertEquals(count, channel.position());
            assertEquals(count < 4 ? 4 - count : -1, channel.read(target));
            assertEquals(4L, channel.position());
            assertEquals(6, target.position());
            assertEquals(7, target.limit());
            byte[] actual = new byte[4];
            target.flip().position(2).get(actual);
            assertArrayEquals(preamble, actual);
        }
        assertEquals(true, source.allOpenedChannelsClosed());
        assertEquals(1, source.closeCount());
    }

    /// Verifies a failed stored-entry read cannot bypass CRC validation on continuation or close.
    @ParameterizedTest
    @ValueSource(ints = {0, 2, 4})
    public void storedEntryValidatesAfterPartialSourceFailure(int count) throws IOException {
        for (Throwable failure : List.of(new IOException("partial source read failed"),
                new IllegalStateException("partial source read failed"), new AssertionError("partial source read failed"))) {
            for (boolean corrupt : new boolean[]{false, true}) {
                for (boolean closeImmediately : new boolean[]{false, true}) {
                    byte[] content = {1, 2, 3, 4};
                    byte[] archive = singleStoredZipArchive("entry", content);
                    if (corrupt) {
                        int dataOffset = 30 + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, 26))
                                + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(archive, 28));
                        archive[dataOffset] ^= 1;
                        content[0] ^= 1;
                    }
                    TestSeekableChannelSource source = new TestSeekableChannelSource(archive);
                    try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(source)) {
                        SeekableByteChannel channel = Files.newByteChannel(fileSystem.getPath("/entry"));
                        try {
                            source.readFailure = failure;
                            source.bytesBeforeFailure = count;
                            ByteBuffer target = ByteBuffer.allocateDirect(6).position(1).limit(5);
                            assertSame(failure, assertThrows(failure.getClass(), () -> channel.read(target)));
                            assertEquals(1 + count, target.position());
                            assertEquals(5, target.limit());
                            assertEquals(count, channel.position());
                            assertEquals(0, failure.getSuppressed().length);
                            if (!closeImmediately) {
                                if (count < content.length) {
                                    assertEquals(content.length - count, channel.read(target));
                                }
                                assertEquals(content.length, channel.position());
                                byte[] actual = new byte[4];
                                target.flip().position(1).get(actual);
                                assertArrayEquals(content, actual);
                                if (corrupt) {
                                    IOException mismatch = assertThrows(IOException.class,
                                            () -> channel.read(ByteBuffer.allocate(1)));
                                    assertEquals("ZIP entry data does not match central directory", mismatch.getMessage());
                                } else {
                                    assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
                                }
                            } else if (corrupt) {
                                IOException mismatch = assertThrows(IOException.class, channel::close);
                                assertEquals("ZIP entry data does not match central directory", mismatch.getMessage());
                            }
                        } finally {
                            channel.close();
                        }
                    }
                    assertEquals(true, source.allOpenedChannelsClosed());
                    assertEquals(1, source.closeCount());
                }
            }
        }
    }

    /// Verifies that a repeatable seekable channel source supports random-access ZIP file system operations.
    @Test
    public void randomAccessFileSystemFromSeekableChannelSource() throws IOException {
        byte[] preamble = new byte[]{7, 6, 5, 4};
        TestSeekableChannelSource source = new TestSeekableChannelSource(
                singleEntryZipWithPreambleAndAdjustedOffsets(preamble)
        );

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(source)) {
            assertEquals(preamble.length, fileSystem.preambleSize());
            assertPreambleContent(preamble, fileSystem);
            assertArrayEquals(new byte[0], Files.readAllBytes(fileSystem.getPath("/a")));
            assertEquals(true, source.openCount() > 1);
            assertEquals(true, source.allOpenedChannelsClosed());
            assertEquals(0, source.closeCount());
        }

        assertEquals(true, source.allOpenedChannelsClosed());
        assertEquals(1, source.closeCount());
    }

    /// Verifies that failed ZIP parsing closes channels opened from a seekable channel source.
    @Test
    public void failedSeekableChannelSourceReadClosesOpenedChannels() throws IOException {
        TestSeekableChannelSource source = new TestSeekableChannelSource(new byte[0]);

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(source)) {
            assertThrows(IOException.class, fileSystem::preambleSize);
            assertEquals(1, source.openCount());
            assertEquals(true, source.allOpenedChannelsClosed());
            assertEquals(0, source.closeCount());
        }

        assertEquals(1, source.closeCount());
    }

    /// Verifies channel and source cleanup when a channel-source update cannot parse its ZIP index.
    @Test
    public void failedSeekableChannelSourceUpdateOpenClosesOwnership() throws IOException {
        TestSeekableChannelSource source = new TestSeekableChannelSource(new byte[0]);
        Path targetPath = createTemporaryArchivePath("failed-channel-update-");
        Files.deleteIfExists(targetPath);
        try {
            assertThrows(
                    IOException.class,
                    () -> ZipArkivoFileSystem.update(
                            source,
                            updateOptions(ArchiveUpdateOptions.DEFAULT.withCommitTarget(ArkivoCommitTarget.writeTo(targetPath)))
                    )
            );
            assertEquals(true, source.openCount() > 0);
            assertEquals(true, source.allOpenedChannelsClosed());
            assertEquals(1, source.closeCount());
            assertEquals(false, Files.exists(targetPath));
        } finally {
            Files.deleteIfExists(targetPath);
            Files.deleteIfExists(targetPath.getParent());
        }
    }

    /// Verifies that channel-source update mode requires a commit target before opening source channels.
    @Test
    public void seekableChannelSourceUpdateRequiresCommitTarget() throws IOException {
        TestSeekableChannelSource source = new TestSeekableChannelSource(emptyZipWithPreamble(new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> ZipArkivoFileSystem.update(source, ZipArchiveOptions.UPDATE_DEFAULTS)
        );
        assertEquals(0, source.openCount());
        assertEquals(1, source.closeCount());
    }

    /// Verifies complete-rewrite mutation and preamble preservation from a repeatable single-volume source.
    @Test
    public void updatesSeekableChannelSourceIntoDerivedArchive() throws IOException {
        byte[] preamble = new byte[]{9, 7, 5, 3};
        byte[] original = updateSourceZip(preamble);
        TestSeekableChannelSource source = new TestSeekableChannelSource(original);
        Path targetPath = createTemporaryArchivePath("channel-update-derived-");
        Files.deleteIfExists(targetPath);
        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(
                    source,
                    updateOptions(ArchiveUpdateOptions.DEFAULT.withCommitTarget(ArkivoCommitTarget.writeTo(targetPath)))
            )) {
                assertEquals(false, fileSystem.isReadOnly());
                assertEquals(preamble.length, fileSystem.preambleSize());
                assertPreambleContent(preamble, fileSystem);
                assertEquals("keep", Files.readString(fileSystem.getPath("/keep.txt"), StandardCharsets.UTF_8));
                assertEquals("replace", Files.readString(fileSystem.getPath("/replace.txt"), StandardCharsets.UTF_8));
                try (SeekableByteChannel entry = Files.newByteChannel(
                        fileSystem.getPath("/replace.txt"),
                        Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
                )) {
                    ByteBuffer prefix = ByteBuffer.allocate(3);
                    assertEquals(3, entry.read(prefix));
                    assertArrayEquals("rep".getBytes(StandardCharsets.UTF_8), prefix.array());
                    entry.position(0L);
                    entry.write(ByteBuffer.wrap("new".getBytes(StandardCharsets.UTF_8)));
                    entry.truncate(3L);
                }
                assertEquals("new", Files.readString(fileSystem.getPath("/replace.txt"), StandardCharsets.UTF_8));
                Files.delete(fileSystem.getPath("/remove.txt"));
                assertThrows(NoSuchFileException.class, () -> Files.readAllBytes(fileSystem.getPath("/remove.txt")));
                Files.writeString(fileSystem.getPath("/added.txt"), "added", StandardCharsets.UTF_8);
                assertEquals("added", Files.readString(fileSystem.getPath("/added.txt"), StandardCharsets.UTF_8));
            }

            assertEquals(true, source.openCount() > 1);
            assertEquals(true, source.allOpenedChannelsClosed());
            assertEquals(1, source.closeCount());
            try (ZipArkivoFileSystem derived = ZipArkivoFileSystem.open(targetPath)) {
                assertEquals(preamble.length, derived.preambleSize());
                assertPreambleContent(preamble, derived);
                assertEquals("keep", Files.readString(derived.getPath("/keep.txt"), StandardCharsets.UTF_8));
                assertEquals("new", Files.readString(derived.getPath("/replace.txt"), StandardCharsets.UTF_8));
                assertEquals(false, Files.exists(derived.getPath("/remove.txt")));
                assertEquals("added", Files.readString(derived.getPath("/added.txt"), StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(targetPath);
            Files.deleteIfExists(targetPath.getParent());
        }
    }

    /// Verifies complete-rewrite updates from one owned seekable channel.
    @Test
    public void updatesOwnedSeekableChannelIntoDerivedArchive() throws IOException {
        ReadOnlyByteArrayChannel channel =
                new ReadOnlyByteArrayChannel(updateSourceZip(new byte[0]));
        Path targetPath = createTemporaryArchivePath("owned-channel-update-derived-");
        Files.deleteIfExists(targetPath);
        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(
                    channel,
                    updateOptions(ArchiveUpdateOptions.DEFAULT.withCommitTarget(ArkivoCommitTarget.writeTo(targetPath)))
            )) {
                assertEquals("keep", Files.readString(fileSystem.getPath("/keep.txt"), StandardCharsets.UTF_8));
                Files.delete(fileSystem.getPath("/remove.txt"));
                Files.writeString(fileSystem.getPath("/added.txt"), "owned", StandardCharsets.UTF_8);
                assertEquals("owned", Files.readString(fileSystem.getPath("/added.txt"), StandardCharsets.UTF_8));
            }

            assertEquals(false, channel.isOpen());
            try (ZipArkivoFileSystem derived = ZipArkivoFileSystem.open(targetPath)) {
                assertEquals(false, Files.exists(derived.getPath("/remove.txt")));
                assertEquals("owned", Files.readString(derived.getPath("/added.txt"), StandardCharsets.UTF_8));
            }
        } finally {
            channel.close();
            Files.deleteIfExists(targetPath);
            Files.deleteIfExists(targetPath.getParent());
        }
    }

    /// Verifies source cleanup when channel-source commit setup fails.
    @Test
    public void failedSeekableChannelSourceCommitClosesSource() throws IOException {
        byte[] original = updateSourceZip(new byte[0]);
        TestSeekableChannelSource source = new TestSeekableChannelSource(original);
        ArkivoCommitTarget failingTarget = (@Nullable Path sourcePath) -> {
            assertNull(sourcePath);
            throw new IOException("channel commit target failed");
        };
        ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(
                source,
                updateOptions(ArchiveUpdateOptions.DEFAULT.withCommitTarget(failingTarget))
        );
        Files.writeString(fileSystem.getPath("/added.txt"), "added", StandardCharsets.UTF_8);
        IOException exception = assertThrows(IOException.class, fileSystem::close);

        assertEquals("channel commit target failed", exception.getMessage());
        assertEquals(false, fileSystem.isOpen());
        assertEquals(true, source.allOpenedChannelsClosed());
        assertEquals(1, source.closeCount());
    }

    /// Verifies that a failed source close can be retried after a successful derived commit.
    @Test
    public void seekableChannelSourceCloseCanRetryAfterUpdate() throws IOException {
        TestSeekableChannelSource source =
                new TestSeekableChannelSource(updateSourceZip(new byte[0]), true);
        Path targetPath = createTemporaryArchivePath("channel-update-close-retry-");
        Files.deleteIfExists(targetPath);
        try {
            ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(
                    source,
                    updateOptions(ArchiveUpdateOptions.DEFAULT.withCommitTarget(ArkivoCommitTarget.writeTo(targetPath)))
            );
            Files.writeString(fileSystem.getPath("/added.txt"), "retry", StandardCharsets.UTF_8);

            IOException exception = assertThrows(IOException.class, fileSystem::close);
            assertEquals("source close failed", exception.getMessage());
            assertEquals(false, fileSystem.isOpen());
            assertEquals(1, source.closeCount());

            fileSystem.close();
            fileSystem.close();
            assertEquals(2, source.closeCount());
            try (ZipArkivoFileSystem derived = ZipArkivoFileSystem.open(targetPath)) {
                assertEquals("retry", Files.readString(derived.getPath("/added.txt"), StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(targetPath);
            Files.deleteIfExists(targetPath.getParent());
        }
    }


    /// Returns ZIP update options using the supplied format-independent configuration.
    private static ZipArchiveOptions.Update updateOptions(ArchiveUpdateOptions common) {
        return ZipArchiveOptions.UPDATE_DEFAULTS.withCommon(common);
    }

    /// Asserts that the preamble channel exposes exactly the expected preamble bytes.
    private static void assertPreambleContent(byte[] expected, ZipArkivoFileSystem fileSystem) throws IOException {
        try (SeekableByteChannel channel = fileSystem.openPreambleChannel()) {
            assertEquals(expected.length, channel.size());
            ByteBuffer buffer = ByteBuffer.allocate(expected.length);
            assertEquals(expected.length, channel.read(buffer));
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
            assertArrayEquals(expected, buffer.array());
        }
    }

    /// Repeatable single-archive source that records opened channel and source lifecycles.
    @NotNullByDefault
    private static final class TestSeekableChannelSource implements ArkivoSeekableChannelSource {
        /// The archive bytes exposed by each opened channel.
        private final byte @Unmodifiable [] content;

        /// The channels opened from this source.
        private final ArrayList<SeekableByteChannel> openedChannels = new ArrayList<>();

        /// A failure consumed by the next physical read after test setup, or `null` while disabled.
        private @Nullable Throwable readFailure;

        /// Maximum bytes delivered by the pending failing physical read.
        private int bytesBeforeFailure;

        /// Whether the first close attempt should fail.
        private final boolean failFirstClose;

        /// The number of times this source has been closed.
        private int closeCount;

        /// Creates a repeatable source over the given archive bytes.
        private TestSeekableChannelSource(byte[] content) {
            this(content, false);
        }

        /// Creates a repeatable source with an optional first-close failure.
        private TestSeekableChannelSource(byte[] content, boolean failFirstClose) {
            this.content = Objects.requireNonNull(content, "content").clone();
            this.failFirstClose = failFirstClose;
        }

        /// Opens an independent channel over the archive bytes.
        @Override
        public SeekableByteChannel openChannel() throws IOException {
            if (closeCount > 0) {
                throw new IOException("source is closed");
            }
            SeekableByteChannel channel = new SourceChannel(new ReadOnlyByteArrayChannel(content));
            openedChannels.add(channel);
            return channel;
        }

        /// Records that this source has been closed.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (failFirstClose && closeCount == 1) {
                throw new IOException("source close failed");
            }
        }

        /// Returns the number of channels opened from this source.
        private int openCount() {
            return openedChannels.size();
        }

        /// Returns whether every channel opened from this source has been closed.
        private boolean allOpenedChannelsClosed() {
            for (SeekableByteChannel channel : openedChannels) {
                if (channel.isOpen()) {
                    return false;
                }
            }
            return true;
        }

        /// Returns the number of times this source has been closed.
        private int closeCount() {
            return closeCount;
        }

        /// Delegates archive reads while allowing one post-setup failure with observable partial progress.
        @NotNullByDefault
        private final class SourceChannel implements SeekableByteChannel {
            /// The independent in-memory archive channel.
            private final SeekableByteChannel delegate;

            /// Creates an independently positioned source view.
            private SourceChannel(SeekableByteChannel delegate) {
                this.delegate = delegate;
            }

            /// Reads normally or delivers a bounded prefix before the armed failure.
            @Override
            public int read(ByteBuffer target) throws IOException {
                @Nullable Throwable failure = readFailure;
                if (failure == null) {
                    return delegate.read(target);
                }
                readFailure = null;
                int limit = target.limit();
                target.limit(target.position() + Math.min(target.remaining(), bytesBeforeFailure));
                try {
                    delegate.read(target);
                } finally {
                    target.limit(limit);
                }
                if (failure instanceof IOException exception) {
                    throw exception;
                }
                if (failure instanceof RuntimeException exception) {
                    throw exception;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                throw new AssertionError(failure);
            }

            /// Delegates the read-only write rejection.
            @Override
            public int write(ByteBuffer source) throws IOException {
                return delegate.write(source);
            }

            /// Returns the physical archive position.
            @Override
            public long position() throws IOException {
                return delegate.position();
            }

            /// Changes the physical archive position.
            @Override
            public SeekableByteChannel position(long position) throws IOException {
                delegate.position(position);
                return this;
            }

            /// Returns the archive size.
            @Override
            public long size() throws IOException {
                return delegate.size();
            }

            /// Delegates the read-only truncation rejection.
            @Override
            public SeekableByteChannel truncate(long size) throws IOException {
                delegate.truncate(size);
                return this;
            }

            /// Returns whether the underlying archive view remains open.
            @Override
            public boolean isOpen() {
                return delegate.isOpen();
            }

            /// Closes this independent archive view.
            @Override
            public void close() throws IOException {
                delegate.close();
            }
        }
    }

}
