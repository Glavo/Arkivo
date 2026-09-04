// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the public AR format descriptor and its generic streaming factories.
@NotNullByDefault
public final class ArArkivoFormatTest {
    /// Verifies descriptor metadata, capability declarations, and non-mutating signature detection.
    @Test
    public void describesAndDetectsArArchives() {
        ArArkivoFormat format = ArArkivoFormat.instance();

        assertSame(format, ArArkivoFormat.instance());
        assertEquals(ArArkivoFormat.NAME, format.name());
        assertEquals(List.of("a", "ar", "deb"), format.fileExtensions());
        assertEquals(8, format.probeSize());
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.FileSystem.Writable);
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.StreamingReadable);
        assertTrue(format instanceof org.glavo.arkivo.archive.ArkivoFormat.StreamingWritable);

        byte[] signature = "!<arch>\n".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer prefix = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        prefix.position(3).put(signature).limit(3 + signature.length);
        prefix.position(2).mark().position(3);
        int position = prefix.position();
        int limit = prefix.limit();
        ByteOrder order = prefix.order();

        assertTrue(format.matches(prefix));
        assertEquals(position, prefix.position());
        assertEquals(limit, prefix.limit());
        assertSame(order, prefix.order());
        prefix.reset();
        assertEquals(2, prefix.position());

        assertFalse(format.matches(ByteBuffer.wrap("!<arch>".getBytes(StandardCharsets.US_ASCII))));
        for (int index = 0; index < signature.length; index++) {
            byte[] wrongSignature = signature.clone();
            wrongSignature[index] ^= 1;
            assertFalse(format.matches(ByteBuffer.wrap(wrongSignature).asReadOnlyBuffer()), "index " + index);
        }
    }

    /// Verifies every stream and channel factory can produce or consume a valid empty archive.
    @Test
    public void roundTripsEmptyArchivesThroughGenericFactories() throws IOException {
        ArArkivoFormat format = ArArkivoFormat.instance();
        byte[] expected = "!<arch>\n".getBytes(StandardCharsets.US_ASCII);

        ByteArrayOutputStream streamDefault = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(streamDefault)) {
            // Closing an empty writer emits only the global header.
        }
        assertArrayEquals(expected, streamDefault.toByteArray());

        ByteArrayOutputStream streamConfigured = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(streamConfigured, ArchiveCreateOptions.DEFAULT)) {
            // Closing an empty writer emits only the global header.
        }
        assertArrayEquals(expected, streamConfigured.toByteArray());

        ByteArrayOutputStream channelDefault = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(Channels.newChannel(channelDefault))) {
            // Closing an empty writer emits only the global header.
        }
        assertArrayEquals(expected, channelDefault.toByteArray());

        ByteArrayOutputStream channelConfigured = new ByteArrayOutputStream();
        try (var writer = format.openStreamingWriter(
                Channels.newChannel(channelConfigured),
                ArchiveCreateOptions.DEFAULT
        )) {
            // Closing an empty writer emits only the global header.
        }
        assertArrayEquals(expected, channelConfigured.toByteArray());

        try (var reader = format.openStreamingReader(new ByteArrayInputStream(expected))) {
            assertFalse(reader.next());
        }
        try (var reader = format.openStreamingReader(
                new ByteArrayInputStream(expected),
                ArchiveReadOptions.DEFAULT
        )) {
            assertFalse(reader.next());
        }
        try (var reader = format.openStreamingReader(Channels.newChannel(new ByteArrayInputStream(expected)))) {
            assertFalse(reader.next());
        }
        try (var reader = format.openStreamingReader(
                Channels.newChannel(new ByteArrayInputStream(expected)),
                ArchiveReadOptions.DEFAULT
        )) {
            assertFalse(reader.next());
        }
    }

    /// Verifies generic path, direct-channel, and repeatable-source factories preserve an empty archive.
    ///
    /// @param directory the temporary directory used for the path-backed archive
    @Test
    public void opensFileSystemsThroughGenericFactories(@TempDir Path directory) throws IOException {
        ArArkivoFormat format = ArArkivoFormat.instance();
        Path archive = directory.resolve("empty.ar");

        try (var fileSystem = format.create(archive, ArchiveCreateOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }
        assertArrayEquals("!<arch>\n".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(archive));

        try (var fileSystem = format.open(archive)) {
            assertTrue(fileSystem.isOpen());
        }
        try (var fileSystem = format.open(archive, ArchiveReadOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }
        try (var fileSystem = format.update(archive, ArchiveUpdateOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }

        SeekableByteChannel defaultChannel = Files.newByteChannel(archive, StandardOpenOption.READ);
        try (var fileSystem = format.open(defaultChannel)) {
            assertTrue(fileSystem.isOpen());
        }
        assertFalse(defaultChannel.isOpen());

        SeekableByteChannel configuredChannel = Files.newByteChannel(archive, StandardOpenOption.READ);
        try (var fileSystem = format.open(configuredChannel, ArchiveReadOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }
        assertFalse(configuredChannel.isOpen());

        SeekableByteChannel repeatableBacking = Files.newByteChannel(archive, StandardOpenOption.READ);
        ArkivoSeekableChannelSource repeatableSource = ArkivoSeekableChannelSource.of(repeatableBacking);
        try (var fileSystem = format.open(repeatableSource, ArchiveReadOptions.DEFAULT)) {
            assertTrue(fileSystem.isOpen());
        }
        assertFalse(repeatableBacking.isOpen());
    }

    /// Verifies common creation options supply operation-owned body storage to a channel writer.
    @Test
    public void appliesCustomBodyStorageToChannelWriter() throws IOException {
        ArArkivoFormat format = ArArkivoFormat.instance();
        TrackingEditStorage storage = new TrackingEditStorage();
        ByteArrayOutputStream archive = new ByteArrayOutputStream();

        try (var writer = format.openStreamingWriter(
                Channels.newChannel(archive),
                ArchiveCreateOptions.DEFAULT.withEditStorageFactory(() -> storage)
        )) {
            // No member body is staged for an empty archive.
        }

        assertEquals(1, storage.closeCount());
        assertArrayEquals("!<arch>\n".getBytes(StandardCharsets.US_ASCII), archive.toByteArray());
    }

    /// Verifies null prefixes fail at the public probing boundary.
    @Test
    @SuppressWarnings("DataFlowIssue")
    public void rejectsNullPrefix() {
        assertThrows(NullPointerException.class, () -> ArArkivoFormat.instance().matches((ByteBuffer) null));
    }

    /// Edit storage that records ownership transfer without accepting staged member bodies.
    @NotNullByDefault
    private static final class TrackingEditStorage implements ArkivoEditStorage {
        /// The number of close attempts.
        private int closeCount;

        /// Rejects unexpected attempts to stage an entry body.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) {
            throw new AssertionError("Empty archive unexpectedly staged content for " + path);
        }

        /// Records release of the operation-owned storage.
        @Override
        public void close() {
            closeCount++;
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }
}
