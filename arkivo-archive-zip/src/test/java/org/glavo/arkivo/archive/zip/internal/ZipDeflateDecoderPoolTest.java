// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.codec.DecompressionOutputLimitException;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies workspace reuse is gated by engine completion, ZIP integrity, and successful source cleanup.
@NotNullByDefault
final class ZipDeflateDecoderPoolTest {
    /// File-system validation confirms healthy leases and retains at most four idle workspaces after concurrent reads.
    @Test
    void reusesThroughConcurrentFileSystemEntries(@TempDir Path directory) throws Exception {
        byte[] body = new byte[65_536];
        new Random(42).nextBytes(body);
        System.arraycopy(body, 0, body, 32_768, 32_768);
        Path path = directory.resolve("concurrent.zip");
        Files.write(path, archive(body));
        try (var fs = ZipArkivoFileSystem.open(path)) {
            var pool = pool(fs);
            var inputs = new ArrayList<InputStream>();
            try {
                for (int i = 0; i < 9; i++) {
                    var input = Files.newInputStream(fs.getPath("a"));
                    inputs.add(input);
                    assertEquals(Byte.toUnsignedInt(body[0]), input.read());
                }
                assertEquals(9, members(pool, "active").size());
                assertTrue(members(pool, "idle").isEmpty());
            } finally {
                for (var input : inputs) input.close();
            }
            assertTrue(members(pool, "active").isEmpty());
            assertEquals(4, members(pool, "idle").size());
            var retained = new HashSet<>(members(pool, "idle"));
            assertArrayEquals(body, Files.readAllBytes(fs.getPath("b")));
            assertEquals(retained, new HashSet<>(members(pool, "idle")));
        }
    }

    /// Central-directory and descriptor agreement cannot authorize reuse when decoded content has the wrong CRC.
    @Test
    void rejectsCorruptContentBeforeFileSystemReuse(@TempDir Path directory) throws Exception {
        byte[] body = {1, 2, 3};
        byte[] bytes = archive(body);
        int central = recordOffset(bytes, 0x02014b50);
        int descriptor = recordOffset(bytes, 0x08074b50);
        int crc = ByteArrayAccess.readIntLittleEndian(bytes, central + 16) ^ 1;
        ByteArrayAccess.writeIntLittleEndian(bytes, central + 16, crc);
        ByteArrayAccess.writeIntLittleEndian(bytes, descriptor + 4, crc);
        Path path = directory.resolve("crc.zip");
        Files.write(path, bytes);
        try (var fs = ZipArkivoFileSystem.open(path)) {
            var pool = pool(fs);
            try (var input = Files.newInputStream(fs.getPath("a"))) {
                assertThrows(IOException.class, input::readAllBytes);
            }
            assertTrue(members(pool, "idle").isEmpty());
            assertTrue(members(pool, "active").isEmpty());
            assertArrayEquals(body, Files.readAllBytes(fs.getPath("b")));
            assertEquals(1, members(pool, "idle").size());
        }
    }

    /// Completed entries share algorithm storage without sharing counters, limits, or history.
    @Test
    void reusesVerifiedWorkspaceWithFreshEntryState() throws Exception {
        try (var pool = new ZipDeflateDecoderPool(1)) {
            Object expected = new Object();
            boolean first = true;
            for (int size : new int[]{0, 1, 4096, 70_000, 0, 37}) {
                byte[] body = new byte[size];
                new Random(size).nextBytes(body);
                var lease = pool.open(new ByteArrayInputStream(compress(body)), size, ArchiveReadLimits.UNLIMITED);
                Object workspace = workspace(lease);
                if (!first) assertSame(expected, workspace);
                first = false;
                expected = workspace;
                assertEquals(0, lease.inputBytes());
                assertEquals(0, lease.outputBytes());
                assertArrayEquals(body, read(lease, size));
                lease.verified();
                lease.close();
                assertEquals(1, members(pool, "idle").size());
                assertSame(workspace, members(pool, "idle").iterator().next());
                assertThrows(ClosedChannelException.class, () -> lease.read(ByteBuffer.allocate(1)));
                lease.close();
            }
        }
    }

    /// Engine completion alone is insufficient when CRC, descriptor, or authentication checks did not succeed.
    @Test
    void destroysUnverifiedAndUnfinishedWorkspaces() throws Exception {
        byte[] body = {1, 2, 3, 4};
        try (var pool = new ZipDeflateDecoderPool(1)) {
            var unverified = pool.open(new ByteArrayInputStream(compress(body)), body.length, ArchiveReadLimits.UNLIMITED);
            assertArrayEquals(body, read(unverified, body.length));
            unverified.close();
            assertTrue(members(pool, "idle").isEmpty());
            var unfinished = pool.open(new ByteArrayInputStream(compress(body)), body.length, ArchiveReadLimits.UNLIMITED);
            assertNotSame(workspace(unverified), workspace(unfinished));
            assertEquals(1, unfinished.read(ByteBuffer.allocate(1)));
            unfinished.verified();
            unfinished.close();
            assertTrue(members(pool, "idle").isEmpty());
        }
    }

    /// A borrowed engine receives a new output limiter for every entry, including after a limiting failure.
    @Test
    void enforcesFreshEntryLimits() throws Exception {
        byte[] body = {1, 2, 3, 4, 5, 6, 7};
        try (var pool = new ZipDeflateDecoderPool(1)) {
            var first = pool.open(new ByteArrayInputStream(compress(body)), body.length, ArchiveReadLimits.UNLIMITED);
            assertArrayEquals(body, read(first, body.length));
            first.verified();
            first.close();
            var limited = pool.open(new ByteArrayInputStream(compress(body)), 3, ArchiveReadLimits.UNLIMITED);
            assertSame(workspace(first), workspace(limited));
            assertThrows(DecompressionOutputLimitException.class, () -> read(limited, body.length));
            limited.close();
            assertTrue(members(pool, "idle").isEmpty());
            var next = pool.open(new ByteArrayInputStream(compress(body)), body.length, ArchiveReadLimits.UNLIMITED);
            assertNotSame(workspace(limited), workspace(next));
            assertArrayEquals(body, read(next, body.length));
            next.verified();
            next.close();
        }
    }

    /// Active leases use separate engines even after the idle cache reaches its configured bound.
    @Test
    void boundsIdleStorageWithoutLimitingActiveEntries() throws Exception {
        byte[] body = new byte[4096];
        Arrays.fill(body, (byte) 42);
        try (var pool = new ZipDeflateDecoderPool(2)) {
            var leases = new ArrayList<ZipDeflateDecoderPool.Lease>();
            var identities = new HashSet<>();
            for (int i = 0; i < 6; i++) {
                var lease = pool.open(new ByteArrayInputStream(compress(body)), body.length, ArchiveReadLimits.UNLIMITED);
                assertTrue(identities.add(workspace(lease)));
                leases.add(lease);
            }
            assertEquals(6, members(pool, "active").size());
            for (var lease : leases) {
                assertArrayEquals(body, read(lease, body.length));
                lease.verified();
                lease.close();
            }
            assertEquals(2, members(pool, "idle").size());
            assertTrue(members(pool, "active").isEmpty());
        }
    }

    /// A cached decoder cannot bypass a new entry's window policy, and failed construction closes its source.
    @Test
    void validatesWindowBeforeBorrowingCachedWorkspace() throws Exception {
        byte[] body = {1, 2, 3};
        try (var pool = new ZipDeflateDecoderPool(1)) {
            var lease = pool.open(new ByteArrayInputStream(compress(body)), body.length, ArchiveReadLimits.UNLIMITED);
            assertArrayEquals(body, read(lease, body.length));
            lease.verified();
            lease.close();
            var source = new FailingSource(compress(body), false, true);
            var limits = ArchiveReadLimits.builder().maximumCompressionWindowSize(16).build();
            var failure = assertThrows(DecompressionWindowLimitException.class, () -> pool.open(source, 3, limits));
            assertEquals(1, source.closeAttempts);
            assertArrayEquals(new Throwable[]{source.failure}, failure.getSuppressed());
            assertEquals(1, members(pool, "idle").size());
            assertSame(workspace(lease), members(pool, "idle").iterator().next());
            source.close();
        }
    }

    /// A recoverable source failure does not change adapter recovery, but still disqualifies its workspace.
    @Test
    void discardsWorkspaceAfterRecoveredSourceFailure() throws Exception {
        byte[] body = {8, 9, 10};
        try (var pool = new ZipDeflateDecoderPool(1)) {
            var source = new FailingSource(compress(body), true, false);
            var lease = pool.open(source, body.length, ArchiveReadLimits.UNLIMITED);
            assertSame(source.failure, assertThrows(IOException.class, () -> lease.read(ByteBuffer.allocate(4))));
            assertArrayEquals(body, read(lease, body.length));
            lease.verified();
            lease.close();
            assertTrue(members(pool, "idle").isEmpty());
            assertEquals(1, source.closeAttempts);
        }
    }

    /// Owner closure retains a failed source close for retry without retaining its damaged engine for reuse.
    @Test
    void retriesFailedOwnerClosure() throws Exception {
        var pool = new ZipDeflateDecoderPool(1);
        var source = new FailingSource(compress(new byte[]{1}), false, true);
        var lease = pool.open(source, 1, ArchiveReadLimits.UNLIMITED);
        assertArrayEquals(new byte[]{1}, read(lease, 1));
        lease.verified();
        assertSame(source.failure, assertThrows(IOException.class, pool::close));
        assertFalse(lease.isOpen());
        assertEquals(1, source.closeAttempts);
        assertEquals(1, members(pool, "active").size());
        assertTrue(members(pool, "idle").isEmpty());
        assertThrows(IllegalStateException.class,
                () -> pool.open(new ByteArrayInputStream(new byte[0]), 0, ArchiveReadLimits.UNLIMITED));
        pool.close();
        pool.close();
        assertEquals(2, source.closeAttempts);
        assertTrue(members(pool, "active").isEmpty());
    }

    /// Interrupt-driven cleanup never returns an otherwise completed engine to the idle cache.
    @Test
    void discardsWorkspaceOnInterruptedCleanup() throws Exception {
        try (var pool = new ZipDeflateDecoderPool(1)) {
            var lease = pool.open(new ByteArrayInputStream(compress(new byte[]{1})), 1, ArchiveReadLimits.UNLIMITED);
            assertArrayEquals(new byte[]{1}, read(lease, 1));
            lease.verified();
            Thread.currentThread().interrupt();
            try {
                lease.close();
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            assertTrue(members(pool, "idle").isEmpty());
        }
    }

    /// Reads a complete entry with enough room to observe its end-of-stream separately from its exact size.
    private static byte[] read(ZipDeflateDecoderPool.Lease lease, int size) throws IOException {
        ByteBuffer output = ByteBuffer.allocate(size + 1);
        while (lease.read(output) >= 0) assertTrue(output.hasRemaining());
        return Arrays.copyOf(output.array(), output.position());
    }

    /// Generates independent raw Deflate input without depending on the ZIP encoder pool.
    private static byte[] compress(byte[] bytes) {
        Deflater deflater = new Deflater(6, true);
        try {
            deflater.setInput(bytes);
            deflater.finish();
            var output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /// Generates two JDK-produced entries whose validation runs through the production ZIP read path.
    private static byte[] archive(byte[] body) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var writer = new ZipOutputStream(output)) {
            for (String name : new String[]{"a", "b"}) {
                writer.putNextEntry(new ZipEntry(name));
                writer.write(body);
                writer.closeEntry();
            }
        }
        return output.toByteArray();
    }

    /// Locates a generated record by its four-byte signature.
    private static int recordOffset(byte[] bytes, int signature) {
        for (int offset = 0; offset <= bytes.length - 4; offset++) {
            if (ByteArrayAccess.readIntLittleEndian(bytes, offset) == signature) return offset;
        }
        throw new AssertionError("ZIP record was not found");
    }

    /// Obtains the file system's session-local workspace owner.
    private static ZipDeflateDecoderPool pool(ZipArkivoFileSystem fs) throws ReflectiveOperationException {
        var field = ZipArkivoReadOnlyFileSystemImpl.class.getDeclaredField("deflateDecoders");
        field.setAccessible(true);
        return assertInstanceOf(ZipDeflateDecoderPool.class, field.get(fs));
    }

    /// Obtains workspace identity without adding an externally visible diagnostic or pooling API.
    private static Object workspace(ZipDeflateDecoderPool.Lease lease) throws ReflectiveOperationException {
        var field = ZipDeflateDecoderPool.Lease.class.getDeclaredField("workspace");
        field.setAccessible(true);
        @Nullable Object value = field.get(lease);
        assertNotNull(value);
        return value;
    }

    /// Obtains an internal collection for count-based lifecycle assertions.
    private static Collection<?> members(ZipDeflateDecoderPool pool, String name) throws ReflectiveOperationException {
        var field = ZipDeflateDecoderPool.class.getDeclaredField(name);
        field.setAccessible(true);
        return assertInstanceOf(Collection.class, field.get(pool));
    }

    /// Fails one selected transport operation, then delegates all subsequent operations normally.
    @NotNullByDefault
    private static final class FailingSource extends FilterInputStream {
        /// The stable injected failure.
        private final IOException failure = new IOException("injected source failure");
        /// Whether the next bulk read fails.
        private boolean failRead;
        /// Whether the next close fails.
        private boolean failClose;
        /// Physical close attempts.
        private int closeAttempts;

        /// Selects one read or cleanup failure for this source.
        private FailingSource(byte[] bytes, boolean failRead, boolean failClose) {
            super(new ByteArrayInputStream(bytes));
            this.failRead = failRead;
            this.failClose = failClose;
        }

        /// Injects the selected read failure before any bytes have been consumed.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (failRead) {
                failRead = false;
                throw failure;
            }
            return super.read(bytes, offset, length);
        }

        /// Counts and, once, rejects source cleanup.
        @Override
        public void close() throws IOException {
            closeAttempts++;
            if (failClose) {
                failClose = false;
                throw failure;
            }
            super.close();
        }
    }
}
