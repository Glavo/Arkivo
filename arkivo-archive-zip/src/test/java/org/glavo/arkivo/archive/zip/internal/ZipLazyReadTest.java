// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/// Checks lazy metadata I/O and the bounded ownership of cached path channels.
@NotNullByDefault
final class ZipLazyReadTest {
    /// Enumerating a sufficiently large archive does not read its local header; attributes load it once.
    @Test
    void loadsLocalMetadataOnlyWhenRequested() throws IOException {
        CountingChannel source = new CountingChannel(archive());
        try (var fs = ZipArkivoFileSystem.open(source)) {
            try (var names = Files.list(fs.getPath("/"))) { assertEquals(2, names.count()); }
            assertEquals(0, source.headerReads);
            var attributes = Files.readAttributes(fs.getPath("a"), ZipArkivoEntryAttributes.class);
            assertEquals(262144, attributes.size());
            assertTrue(source.headerReads > 0);
            int loadedReads = source.headerReads;
            assertEquals(attributes.size(), Files.readAttributes(fs.getPath("a"), ZipArkivoEntryAttributes.class).size());
            try (var input = Files.newInputStream(fs.getPath("a"))) { assertEquals(262144, input.readAllBytes().length); }
            assertEquals(loadedReads, source.headerReads);
        }
        assertFalse(source.isOpen());
    }

    /// A corrupt local record is rejected on access without hiding unrelated indexed entries.
    @Test
    void defersLocalCorruptionUntilEntryAccess() throws IOException {
        byte[] bytes = archive();
        bytes[0] ^= 1;
        try (var fs = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(bytes))) {
            try (var names = Files.list(fs.getPath("/"))) { assertEquals(2, names.count()); }
            assertThrows(IOException.class, () -> Files.readAttributes(fs.getPath("a"), ZipArkivoEntryAttributes.class));
            assertThrows(IOException.class, () -> Files.newInputStream(fs.getPath("a")));
            assertEquals("valid", Files.readString(fs.getPath("b")));
        }
    }

    /// Sequential reads reuse a physical handle and parallel leases retain at most four idle handles.
    @Test
    void boundsAndClosesCachedHandles(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("entries.zip");
        Files.write(path, archive());
        @Unmodifiable List<SeekableByteChannel> retained;
        try (var fs = ZipArkivoFileSystem.open(path)) {
            try (var names = Files.list(fs.getPath("/"))) { assertEquals(2, names.count()); }
            Collection<?> idle = idleChannels(fs);
            assertEquals(1, idle.size());
            Object first = idle.iterator().next();
            for (int i = 0; i < 5; i++) assertEquals("valid", Files.readString(fs.getPath("b")));
            assertSame(first, idleChannels(fs).iterator().next());
            List<InputStream> streams = new ArrayList<>();
            try {
                for (int i = 0; i < 9; i++) streams.add(Files.newInputStream(fs.getPath("a")));
                for (InputStream stream : streams) assertTrue(stream.read() >= 0);
            } finally {
                for (InputStream stream : streams) stream.close();
            }
            assertEquals(4, idleChannels(fs).size());
            retained = idleChannels(fs).stream().map(SeekableByteChannel.class::cast).toList();
        }
        for (var channel : retained) assertFalse(channel.isOpen());
        Files.delete(path);
    }

    /// Interrupting one physical reader leaves another lease usable and discards the interrupted channel.
    @Test
    void isolatesInterruptedReader(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("interrupted.zip");
        Files.write(path, archive());
        try (var fs = ZipArkivoFileSystem.open(path)) {
            var executor = Executors.newSingleThreadExecutor();
            try (InputStream interrupted = Files.newInputStream(fs.getPath("a"));
                 InputStream independent = Files.newInputStream(fs.getPath("a"))) {
                executor.submit(() -> {
                    Thread.currentThread().interrupt();
                    try {
                        assertThrows(ClosedByInterruptException.class, interrupted::read);
                        assertTrue(Thread.currentThread().isInterrupted());
                    } finally {
                        Thread.interrupted();
                    }
                }).get();
                byte[] expected = new byte[262144];
                new Random(42).nextBytes(expected);
                assertArrayEquals(expected, independent.readAllBytes());
                // Closing an unread stream still attempts its required end-of-entry validation.
                assertThrows(ClosedChannelException.class, interrupted::close);
            } finally {
                executor.shutdownNow();
            }
            assertEquals(1, idleChannels(fs).size());
            assertEquals("valid", Files.readString(fs.getPath("b")));
        }
    }

    /// Inspects the internal ownership cache without adding diagnostics to the public API.
    private static Collection<?> idleChannels(ZipArkivoFileSystem fs) throws ReflectiveOperationException {
        Field field = ZipArkivoReadOnlyFileSystemImpl.class.getDeclaredField("idlePathChannels");
        field.setAccessible(true);
        return (Collection<?>) field.get(fs);
    }

    /// Creates a large stored entry so the end-record search cannot overlap its local header.
    private static byte[] archive() throws IOException {
        byte[] body = new byte[262144];
        new Random(42).nextBytes(body);
        var target = new ByteArrayOutputStream();
        try (var out = new ZipOutputStream(target)) {
            CRC32 crc = new CRC32(); crc.update(body);
            var first = new ZipEntry("a");
            first.setMethod(ZipEntry.STORED); first.setSize(body.length); first.setCrc(crc.getValue());
            out.putNextEntry(first); out.write(body); out.closeEntry();
            out.putNextEntry(new ZipEntry("b")); out.write("valid".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return target.toByteArray();
    }

    /// Counts physical reads beginning at the first local header.
    @NotNullByDefault
    private static final class CountingChannel implements SeekableByteChannel {
        /// The immutable archive channel.
        private final ReadOnlyByteArrayChannel delegate;
        /// Reads beginning within the first local header.
        private int headerReads;
        /// Owns a defensive snapshot of the archive.
        private CountingChannel(byte[] bytes) { delegate = new ReadOnlyByteArrayChannel(bytes); }
        /// Counts local-header access before delegating the read.
        @Override
        public int read(ByteBuffer target) throws IOException {
            if (delegate.position() < 31) headerReads++;
            return delegate.read(target);
        }
        /// Delegates write rejection.
        @Override
        public int write(ByteBuffer source) throws IOException { return delegate.write(source); }
        /// Returns the physical position.
        @Override
        public long position() throws IOException { return delegate.position(); }
        /// Updates the physical position.
        @Override
        public SeekableByteChannel position(long position) throws IOException { delegate.position(position); return this; }
        /// Returns the archive length.
        @Override
        public long size() throws IOException { return delegate.size(); }
        /// Delegates truncation rejection.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException { delegate.truncate(size); return this; }
        /// Returns the delegate's state.
        @Override
        public boolean isOpen() { return delegate.isOpen(); }
        /// Closes the archive snapshot.
        @Override
        public void close() { delegate.close(); }
    }
}
