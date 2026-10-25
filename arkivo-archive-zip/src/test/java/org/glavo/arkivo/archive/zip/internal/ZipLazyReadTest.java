// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/// Checks lazy metadata I/O and the bounded ownership of cached path channels.
@NotNullByDefault
final class ZipLazyReadTest {
    /// Local-only extra fields are charged before attributes or content can expose the entry.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsLocalMetadataBeyondIndexBudget(boolean readBody) throws IOException {
        byte[] bytes = archiveWithLocalExtras(1, 60_000);
        try (var fs = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(bytes), metadataOptions(100))) {
            try (var names = Files.list(fs.getPath("/"))) { assertEquals(1, names.count()); }
            var failure = assertThrows(ArkivoReadLimitException.class, () -> {
                if (readBody) Files.readAllBytes(fs.getPath("a"));
                else Files.readAttributes(fs.getPath("a"), ZipArkivoEntryAttributes.class);
            });
            assertEquals(ArkivoReadLimitKind.METADATA_SIZE, failure.kind());
            assertEquals(bytes.length, failure.actual());
            assertEquals("a", failure.entryPath());
        }
    }

    /// Reservations survive failed reads, while cached local metadata can be used repeatedly at the exact limit.
    @Test
    void localMetadataRetriesAndCacheHitsDoNotConsumeMoreBudget() throws IOException {
        byte[] bytes = archiveWithLocalExtras(2, 128);
        CountingChannel source = new CountingChannel(bytes);
        try (var fs = ZipArkivoFileSystem.open(source, metadataOptions(bytes.length))) {
            try (var names = Files.list(fs.getPath("/"))) { assertEquals(2, names.count()); }
            source.failReadOffset = 31;
            assertThrows(IOException.class, () -> Files.size(fs.getPath("a")));
            for (int attempt = 0; attempt < 3; attempt++) {
                for (String name : List.of("a", "b")) {
                    assertEquals(0, Files.size(fs.getPath(name)));
                    assertArrayEquals(new byte[0], Files.readAllBytes(fs.getPath(name)));
                }
            }
        }
        try (var fs = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(bytes), metadataOptions(bytes.length - 1))) {
            assertEquals(0, Files.size(fs.getPath("a")));
            var failure = assertThrows(ArkivoReadLimitException.class, () -> Files.size(fs.getPath("b")));
            assertEquals(bytes.length, failure.actual());
        }
    }

    /// Concurrent first loads share a budget, but two accesses to one record reserve its bytes only once.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentLocalMetadataReservationsAreAtomic(boolean sameEntry) throws Exception {
        byte[] bytes = archiveWithLocalExtras(2, 128);
        long budget = 22 + 2 * 47 + 31 + 128;
        try (var fs = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(bytes), metadataOptions(budget))) {
            try (var names = Files.list(fs.getPath("/"))) { assertEquals(2, names.count()); }
            var executor = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            try {
                var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
                for (String name : List.of("a", sameEntry ? "a" : "b")) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        try {
                            assertEquals(0, Files.size(fs.getPath(name)));
                            return true;
                        } catch (ArkivoReadLimitException exception) {
                            assertEquals(ArkivoReadLimitKind.METADATA_SIZE, exception.kind());
                            return false;
                        }
                    }));
                }
                start.countDown();
                int successes = 0;
                for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) successes++;
                assertEquals(sameEntry ? 2 : 1, successes);
            } finally {
                start.countDown();
                executor.shutdownNow();
            }
        }
    }

    /// Update snapshots enforce local metadata limits before publishing or modifying any source bytes.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updateSnapshotEnforcesLocalMetadataLimit(boolean repeatableSource, @TempDir Path directory) throws IOException {
        byte[] bytes = archiveWithLocalExtras(1, 128);
        Path path = directory.resolve("source.zip");
        Files.write(path, bytes);
        var options = ZipArchiveOptions.UPDATE_DEFAULTS.withCommon(ArchiveUpdateOptions.DEFAULT.withLimits(
                ArchiveReadLimits.builder().maximumMetadataSize(bytes.length - 1).build()));
        if (repeatableSource) {
            try (var source = ArkivoSeekableChannelSource.of(new ReadOnlyByteArrayChannel(bytes))) {
                var configured = options.withCommon(options.common().withCommitTarget(
                        ArkivoCommitTarget.writeTo(directory.resolve("output.zip"))));
                assertThrows(ArkivoReadLimitException.class, () -> ZipArkivoFileSystem.update(source, configured));
            }
            assertFalse(Files.exists(directory.resolve("output.zip")));
        } else {
            assertThrows(ArkivoReadLimitException.class, () -> ZipArkivoFileSystem.update(path, options));
        }
        assertArrayEquals(bytes, Files.readAllBytes(path));
    }

    /// Rechecking local consistency for an update snapshot does not reserve the same metadata twice.
    @Test
    void updateConsistencyCheckDoesNotChargeLocalMetadataTwice(@TempDir Path directory) throws IOException {
        byte[] bytes = archiveWithLocalExtras(1, 128);
        Path path = Files.write(directory.resolve("source.zip"), bytes);
        var options = ZipArchiveOptions.UPDATE_DEFAULTS.withCommon(ArchiveUpdateOptions.DEFAULT.withLimits(
                ArchiveReadLimits.builder().maximumMetadataSize(bytes.length).build()));
        try (var fs = ZipArkivoFileSystem.update(path, options)) {
            assertEquals(0, Files.size(fs.getPath("a")));
        }
        assertArrayEquals(bytes, Files.readAllBytes(path));
    }

    /// End-record comments consume metadata budget even when the central directory is empty.
    @Test
    void emptyArchiveCommentUsesMetadataBudget() throws IOException {
        var target = new ByteArrayOutputStream();
        try (var output = new ZipOutputStream(target)) { output.setComment("comment".repeat(100)); }
        byte[] bytes = target.toByteArray();
        for (int budget : new int[]{bytes.length - 1, bytes.length}) {
            try (var fs = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(bytes), metadataOptions(budget))) {
                if (budget < bytes.length) {
                    assertThrows(ArkivoReadLimitException.class, () -> Files.newDirectoryStream(fs.getPath("/")));
                } else {
                    try (var names = Files.list(fs.getPath("/"))) { assertEquals(0, names.count()); }
                }
            }
        }
    }

    /// ZIP64 end records and their locator share the cumulative budget with the classic end record.
    @Test
    void zip64EndRecordsUseMetadataBudget() throws IOException {
        var bytes = ByteBuffer.allocate(56 + 20 + 22).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x06064b50).putLong(44).putShort((short) 45).putShort((short) 45);
        bytes.putInt(0).putInt(0).putLong(0).putLong(0).putLong(0).putLong(0);
        bytes.putInt(0x07064b50).putInt(0).putLong(0).putInt(1);
        bytes.putInt(0x06054b50).putShort((short) 0).putShort((short) 0);
        bytes.putShort((short) 0).putShort((short) 0).putInt(0).putInt(0).putShort((short) 0);
        for (int budget : new int[]{bytes.capacity() - 1, bytes.capacity()}) {
            try (var fs = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(bytes.array()), metadataOptions(budget))) {
                if (budget < bytes.capacity()) {
                    var failure = assertThrows(ArkivoReadLimitException.class,
                            () -> Files.newDirectoryStream(fs.getPath("/")));
                    assertEquals(bytes.capacity(), failure.actual());
                } else {
                    try (var names = Files.list(fs.getPath("/"))) { assertEquals(0, names.count()); }
                }
            }
        }
    }

    /// End-record search windows may contain body bytes without charging those bytes as metadata.
    @Test
    void endRecordSearchDoesNotChargeEntryContent() throws IOException {
        byte[] bytes = archive();
        long centralSize = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(bytes.length - 10);
        long metadataSize = centralSize + 22 + 2 * 31;
        try (var fs = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(bytes), metadataOptions(metadataSize))) {
            assertEquals(262144, Files.readAllBytes(fs.getPath("a")).length);
            assertEquals("valid", Files.readString(fs.getPath("b")));
        }
    }

    /// Returns read options with only a cumulative metadata limit.
    private static ZipArchiveOptions.Read metadataOptions(long maximum) {
        return ZipArchiveOptions.READ_DEFAULTS.withCommon(ZipArchiveOptions.READ_DEFAULTS.common().withLimits(
                ArchiveReadLimits.builder().maximumMetadataSize(maximum).build()));
    }

    /// Creates empty STORED entries with unknown local-only extra fields and one-byte ASCII names.
    private static byte[] archiveWithLocalExtras(int count, int extraLength) {
        int localSize = 31 + extraLength;
        int centralOffset = count * localSize;
        var bytes = ByteBuffer.allocate(centralOffset + 47 * count + 22).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            bytes.putInt(0x04034b50).putShort((short) 20).putShort((short) 0).putShort((short) 0);
            bytes.putInt(0).putInt(0).putInt(0).putInt(0);
            bytes.putShort((short) 1).putShort((short) extraLength).put((byte) ('a' + i));
            bytes.putShort((short) 0x9999).putShort((short) (extraLength - 4));
            bytes.position((i + 1) * localSize);
        }
        for (int i = 0; i < count; i++) {
            bytes.putInt(0x02014b50).putShort((short) 20).putShort((short) 20);
            bytes.putShort((short) 0).putShort((short) 0).putInt(0).putInt(0).putInt(0).putInt(0);
            bytes.putShort((short) 1).putShort((short) 0).putShort((short) 0);
            bytes.putShort((short) 0).putShort((short) 0).putInt(0).putInt(i * localSize).put((byte) ('a' + i));
        }
        bytes.putInt(0x06054b50).putShort((short) 0).putShort((short) 0);
        bytes.putShort((short) count).putShort((short) count).putInt(47 * count).putInt(centralOffset).putShort((short) 0);
        return bytes.array();
    }

    /// Descriptor reads handle fragmentation and reject stalled sources before handing out an entry stream.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 7, 24})
    void validatesDescriptorsWithShortReads(int chunk) throws IOException {
        CountingChannel source = new CountingChannel(archive());
        try (var fs = ZipArkivoFileSystem.open(source)) {
            Path entry = fs.getPath("b");
            assertEquals(5, Files.size(entry));
            source.maximumReadSize = chunk;
            if (chunk == 0) {
                assertThrows(IOException.class, () -> Files.newInputStream(entry));
                source.maximumReadSize = 1;
            }
            assertEquals("valid", Files.readString(entry));
        }
    }

    /// Repeated and alternating DOS timestamps retain each entry's own modification time.
    @Test
    void preservesDosTimestampsAcrossIndexCacheHits() throws IOException {
        var first = java.time.LocalDateTime.of(2020, 1, 2, 3, 4, 6);
        var times = List.of(first, first, first.plusDays(1), first, first.plusSeconds(2));
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < times.size(); i++) {
                var entry = new ZipEntry("entry-" + i);
                entry.setTimeLocal(times.get(i));
                zip.putNextEntry(entry);
                zip.closeEntry();
            }
        }
        try (var fs = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(bytes.toByteArray()))) {
            for (int i = 0; i < times.size(); i++) {
                assertEquals(times.get(i).atZone(java.time.ZoneId.systemDefault()).toInstant(),
                        Files.getLastModifiedTime(fs.getPath("entry-" + i)).toInstant());
            }
        }
    }

    /// Enumerating a sufficiently large archive does not read its local header; attributes load it once.
    @ParameterizedTest
    @ValueSource(ints = {1, 7, 30, 4096})
    void loadsLocalMetadataOnlyWhenRequested(int chunk) throws IOException {
        CountingChannel source = new CountingChannel(archive());
        try (var fs = ZipArkivoFileSystem.open(source)) {
            try (var names = Files.list(fs.getPath("/"))) { assertEquals(2, names.count()); }
            assertEquals(0, source.headerReads);
            source.maximumReadSize = chunk;
            var attributes = Files.readAttributes(fs.getPath("a"), ZipArkivoEntryAttributes.class);
            assertEquals(262144, attributes.size());
            assertEquals((30 + chunk - 1) / chunk, source.headerReads);
            source.maximumReadSize = Integer.MAX_VALUE;
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

    /// Loading an entry without local extra fields does not seek to an empty metadata range.
    @Test
    void skipsEmptyLocalExtraRange(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("empty-extra.zip");
        Files.write(path, archive());
        try (var fs = ZipArkivoFileSystem.open(path)) {
            try (var names = Files.list(fs.getPath("/"))) { assertEquals(2, names.count()); }
            var attributes = Files.readAttributes(fs.getPath("a"), ZipArkivoEntryAttributes.class);
            assertEquals(262144, attributes.size());
            assertEquals(0, attributes.localExtraData().length);
            var channel = (SeekableByteChannel) idleChannels(fs).iterator().next();
            assertEquals(30, channel.position());
        }
    }

    /// SFX archives retain signature-based selection between relative and already-absolute local offsets.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resolvesPreambleOffsetsWithBothLocalConventions(boolean absoluteOffsets) throws IOException {
        byte[] bytes = archive();
        int prefixSize = 64;
        if (absoluteOffsets) {
            ByteBuffer records = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int offset = records.getInt(bytes.length - 6);
            while (records.getInt(offset) == 0x02014b50) {
                records.putInt(offset + 42, records.getInt(offset + 42) + prefixSize);
                offset += 46 + Short.toUnsignedInt(records.getShort(offset + 28))
                        + Short.toUnsignedInt(records.getShort(offset + 30))
                        + Short.toUnsignedInt(records.getShort(offset + 32));
            }
        }
        byte[] prefixed = new byte[prefixSize + bytes.length];
        Arrays.fill(prefixed, 0, prefixSize, (byte) 0x5a);
        System.arraycopy(bytes, 0, prefixed, prefixSize, bytes.length);
        try (var fs = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(prefixed))) {
            assertEquals(262144, Files.size(fs.getPath("a")));
            assertEquals("valid", Files.readString(fs.getPath("b")));
        }
    }

    /// Update snapshots reuse their strictly validated local layout when calculating raw-copy ranges.
    @Test
    void updateSnapshotDoesNotReadLocalHeaderAgainForRange() throws IOException {
        byte[] bytes = archive();
        var opened = new ArrayList<CountingChannel>();
        ArkivoVolumeSource source = index -> {
            if (index != 0) return null;
            var channel = new CountingChannel(bytes);
            opened.add(channel);
            return channel;
        };
        var snapshot = ZipArkivoReadOnlyFileSystemImpl.readCentralDirectorySnapshot(
                source, ZipArkivoFileSystemConfig.DEFAULTS);
        assertEquals(2, snapshot.entries().size());
        var first = snapshot.entries().get(0);
        assertEquals("a", first.entryName());
        assertEquals(31, first.localHeaderSize());
        assertEquals(31 + 262144, first.localRecordSize());
        var second = snapshot.entries().get(1);
        long centralOffset = Integer.toUnsignedLong(
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(bytes.length - 6));
        assertEquals(31, second.localHeaderSize());
        assertEquals(centralOffset - second.localHeaderOffset(), second.localRecordSize());
        // Preamble discovery, lazy metadata resolution, and strict copy validation each inspect the header once.
        assertEquals(3, opened.stream().mapToInt(channel -> channel.fullHeaderReads).sum());
        assertTrue(opened.stream().noneMatch(CountingChannel::isOpen));
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
                // A failed read cancels cleanup without starting another end-of-entry validation read.
                interrupted.close();
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
        /// Full fixed-header requests at the first entry, excluding signature-only probes.
        private int fullHeaderReads;
        /// Maximum bytes delivered by a physical read; zero simulates a stalled source.
        private int maximumReadSize = Integer.MAX_VALUE;
        /// The physical offset of a one-shot read failure, or -1 when no failure is armed.
        private long failReadOffset = -1L;
        /// Owns a defensive snapshot of the archive.
        private CountingChannel(byte[] bytes) { delegate = new ReadOnlyByteArrayChannel(bytes); }
        /// Counts local-header access before delegating the read.
        @Override
        public int read(ByteBuffer target) throws IOException {
            if (delegate.position() == failReadOffset) {
                failReadOffset = -1L;
                throw new IOException("Injected local metadata read failure");
            }
            if (delegate.position() < 31) headerReads++;
            if (delegate.position() == 0 && target.remaining() == 30) fullHeaderReads++;
            ByteBuffer view = target.slice();
            view.limit(Math.min(view.remaining(), maximumReadSize));
            int count = delegate.read(view);
            target.position(target.position() + view.position());
            return count;
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
