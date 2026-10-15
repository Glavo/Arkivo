// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoVolumeOutput;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Checks cancellation of direct ZIP writes without publishing an incomplete entry.
@NotNullByDefault
final class ZipConcurrentOutputCloseTest {
    /// Creates a strict file system over a close-aware physical destination.
    private static ZipArkivoFileSystem create(GatedOutput output) {
        return ZipArkivoFileSystem.create(output, ZipArchiveOptions.CREATE_DEFAULTS.withCommon(
                ArchiveCreateOptions.DEFAULT.withThreadSafety(ArkivoFileSystemThreadSafety.STRICT)));
    }

    /// Opens the same entry through either writable public entry API.
    private static OutputStream openBody(ZipArkivoFileSystem fileSystem, boolean channel) throws IOException {
        return channel ? Channels.newOutputStream(Files.newByteChannel(fileSystem.getPath("entry"),
                Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)))
                : Files.newOutputStream(fileSystem.getPath("entry"));
    }

    /// Releases physical I/O before waiting for the entry lock and never completes a cancelled archive.
    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void strictCloseCancelsActiveWrite(boolean channel, boolean successfulWrite) throws Exception {
        GatedOutput target = new GatedOutput(successfulWrite);
        ZipArkivoFileSystem fileSystem = create(target);
        OutputStream body = openBody(fileSystem, channel);
        byte[] content = new byte[256 * 1024];
        new Random(42).nextBytes(content);
        target.armed.set(true);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var write = executor.submit(() -> failure(() -> body.write(content)));
            try {
                assertTrue(target.writing.await(5, TimeUnit.SECONDS));
                var close = executor.submit(() -> failure(fileSystem::close));
                assertTrue(target.closed.await(5, TimeUnit.SECONDS), "Close must reach the physical channel");
                assertThrows(TimeoutException.class, () -> close.get(100, TimeUnit.MILLISECONDS),
                        "Close must not finalize while the body write is active");
                target.release.countDown();
                @Nullable Throwable writeFailure = write.get(5, TimeUnit.SECONDS);
                assertInstanceOf(IOException.class, writeFailure);
                if (!successfulWrite) assertSame(target.writeFailure, writeFailure);
                assertInstanceOf(IOException.class, close.get(5, TimeUnit.SECONDS));
                byte[] partial = target.bytes.toByteArray();
                body.close();
                fileSystem.close();
                fileSystem.close();
                assertArrayEquals(partial, target.bytes.toByteArray());
                assertEquals(0, target.writesAfterClose);
                assertEquals(1, target.closeCalls);
            } finally {
                target.release.countDown();
            }
        } finally {
            target.release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            failure(body::close);
            failure(fileSystem::close);
        }
    }

    /// Idle close finishes the current entry, and closing an older entry never cancels its successor.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void strictCloseFinishesIdleEntry(boolean channel) throws Exception {
        GatedOutput target = new GatedOutput(false);
        try (ZipArkivoFileSystem fileSystem = create(target)) {
            OutputStream first = openBody(fileSystem, channel);
            first.write(new byte[]{1, 2, 3});
            first.close();
            OutputStream second = Files.newOutputStream(fileSystem.getPath("second"));
            first.close();
            first.close();
            second.write(new byte[]{4, 5, 6});
        }
        assertEquals(1, target.closeCalls);
        try (var input = new ZipInputStream(new ByteArrayInputStream(target.bytes.toByteArray()))) {
            assertEquals("entry", input.getNextEntry().getName());
            assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
            assertEquals("second", input.getNextEntry().getName());
            assertArrayEquals(new byte[]{4, 5, 6}, input.readAllBytes());
            assertNull(input.getNextEntry());
        }
    }

    /// Strict close also cancels physical output already in progress during explicit entry completion.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void strictCloseCancelsEntryFinish(boolean channel) throws Exception {
        GatedOutput target = new GatedOutput(false);
        ZipArkivoFileSystem fileSystem = create(target);
        OutputStream body = openBody(fileSystem, channel);
        body.write(new byte[]{1, 2, 3});
        target.armed.set(true);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var entryClose = executor.submit(() -> failure(body::close));
            assertTrue(target.writing.await(5, TimeUnit.SECONDS));
            var archiveClose = executor.submit(() -> failure(fileSystem::close));
            assertTrue(target.closed.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> archiveClose.get(100, TimeUnit.MILLISECONDS));
            target.release.countDown();
            assertSame(target.writeFailure, entryClose.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, archiveClose.get(5, TimeUnit.SECONDS));
            assertEquals(0, target.writesAfterClose);
            assertEquals(1, target.closeCalls);
        } finally {
            target.release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            failure(body::close);
            failure(fileSystem::close);
        }
    }

    /// Cancels the active split volume and defers rollback until every physical close succeeds.
    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void strictCloseCancelsCurrentVolume(boolean channel, boolean failClose) throws Exception {
        GatedVolumes volumes = new GatedVolumes();
        var options = ZipArchiveOptions.CREATE_DEFAULTS.withCommon(
                ArchiveCreateOptions.DEFAULT.withThreadSafety(ArkivoFileSystemThreadSafety.STRICT));
        var fileSystem = ZipArkivoFileSystem.create(() -> volumes, ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE, options);
        OutputStream body = openBody(fileSystem, channel);
        byte[] content = new byte[256 * 1024];
        new Random(42).nextBytes(content);
        GatedOutput current = volumes.blocked;
        current.failClose = failClose;
        var executor = Executors.newFixedThreadPool(2);
        try {
            var write = executor.submit(() -> failure(() -> body.write(content)));
            assertTrue(current.writing.await(5, TimeUnit.SECONDS));
            assertEquals(2, volumes.outputs.size());
            assertEquals(1, volumes.outputs.get(0).closeCalls);
            var close = executor.submit(() -> failure(fileSystem::close));
            assertTrue(current.closed.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> close.get(100, TimeUnit.MILLISECONDS));
            assertEquals(0, volumes.rollbacks);
            assertEquals(0, volumes.transactionCloses);
            current.release.countDown();
            assertSame(current.writeFailure, write.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, close.get(5, TimeUnit.SECONDS));
            assertEquals(0, volumes.commits);
            assertEquals(failClose ? 0 : 1, volumes.rollbacks);
            assertEquals(failClose ? 0 : 1, volumes.transactionCloses);
            byte[] partial = current.bytes.toByteArray();
            current.failClose = false;
            body.close();
            fileSystem.close();
            fileSystem.close();
            assertEquals(2, volumes.outputs.size(), "Cancellation must not open another volume");
            assertEquals(0, volumes.commits);
            assertEquals(1, volumes.rollbacks);
            assertEquals(1, volumes.transactionCloses);
            assertEquals(0, current.writesAfterClose);
            assertArrayEquals(partial, current.bytes.toByteArray());
        } finally {
            current.release.countDown();
            current.failClose = false;
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            failure(body::close);
            failure(fileSystem::close);
        }
    }

    /// A second closer can retry a failed physical close while the first closer still waits for body I/O.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retriesCancellationBeforeActiveWriteReturns(boolean channel) throws Exception {
        GatedOutput target = new GatedOutput(false);
        ZipArkivoFileSystem fileSystem = create(target);
        OutputStream body = openBody(fileSystem, channel);
        byte[] content = new byte[256 * 1024];
        new Random(42).nextBytes(content);
        target.armed.set(true);
        target.failClose = true;
        target.releaseOnSuccessfulClose = true;
        var executor = Executors.newFixedThreadPool(3);
        try {
            var write = executor.submit(() -> failure(() -> body.write(content)));
            assertTrue(target.writing.await(5, TimeUnit.SECONDS));
            var archiveClose = executor.submit(() -> failure(fileSystem::close));
            assertTrue(target.closed.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> archiveClose.get(100, TimeUnit.MILLISECONDS));
            target.failClose = false;
            var entryClose = executor.submit(() -> failure(body::close));
            assertSame(target.writeFailure, write.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, archiveClose.get(5, TimeUnit.SECONDS));
            @Nullable Throwable entryFailure = entryClose.get(5, TimeUnit.SECONDS);
            if (entryFailure != null) assertInstanceOf(IOException.class, entryFailure);
            assertEquals(2, target.closeCalls);
            assertEquals(0, target.writesAfterClose);
        } finally {
            target.release.countDown();
            target.failClose = false;
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            failure(body::close);
            failure(fileSystem::close);
        }
    }

    /// Returns the failure of a checked I/O action without changing its identity.
    private static @Nullable Throwable failure(IOAction action) {
        try {
            action.run();
            return null;
        } catch (IOException | RuntimeException | Error exception) {
            return exception;
        }
    }

    /// An I/O action executed by one test worker.
    @FunctionalInterface
    @NotNullByDefault
    private interface IOAction {
        /// Performs the operation.
        void run() throws IOException;
    }

    /// Holds a physical write across close, optionally reporting successful progress after cancellation.
    @NotNullByDefault
    private static final class GatedOutput implements WritableByteChannel {
        /// The emitted archive prefix.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        /// Whether a held write returns normally after closure.
        private final boolean successfulWrite;
        /// Arms exactly one physical write.
        private final AtomicBoolean armed = new AtomicBoolean();
        /// Signals that a physical write is pending.
        private final CountDownLatch writing = new CountDownLatch(1);
        /// Signals the first physical close attempt.
        private final CountDownLatch closed = new CountDownLatch(1);
        /// Releases the pending write after the test has observed closure.
        private final CountDownLatch release = new CountDownLatch(1);
        /// The original write failure, retained for identity checks.
        private final IOException writeFailure = new IOException("Interrupted physical ZIP write");
        /// Whether physical closure has been requested.
        private volatile boolean open = true;
        /// Number of physical close calls.
        private int closeCalls;
        /// Number of writes attempted after physical closure.
        private int writesAfterClose;
        /// Whether each close attempt fails until the test allows cleanup to complete.
        private volatile boolean failClose;
        /// Whether successful physical closure itself releases the pending write.
        private boolean releaseOnSuccessfulClose;

        /// Selects whether the pending write throws or returns its consumed byte count.
        private GatedOutput(boolean successfulWrite) {
            this.successfulWrite = successfulWrite;
        }

        /// Emits bytes before optionally blocking, without holding a monitor needed by close.
        @Override
        public int write(ByteBuffer source) throws IOException {
            if (!open) {
                writesAfterClose++;
                throw new IOException("Physical ZIP output is closed");
            }
            boolean hold = armed.compareAndSet(true, false);
            int count = hold && !successfulWrite ? 1 : source.remaining();
            byte[] chunk = new byte[count];
            source.get(chunk);
            bytes.writeBytes(chunk);
            if (hold) {
                writing.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Write release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
                if (!successfulWrite) throw writeFailure;
            }
            return count;
        }

        /// Returns whether closure has been requested.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Signals physical closure without waiting for the held write.
        @Override
        public void close() throws IOException {
            boolean fail = failClose;
            closeCalls++;
            open = false;
            closed.countDown();
            if (fail) throw new IOException("Physical ZIP close failed");
            if (releaseOnSuccessfulClose) release.countDown();
        }
    }

    /// Tracks physical volumes separately from publication and rollback.
    @NotNullByDefault
    private static final class GatedVolumes implements ArkivoVolumeOutput {
        /// The second volume whose first write is held for cancellation.
        private final GatedOutput blocked = new GatedOutput(false);
        /// All opened physical outputs in disk order.
        private final List<GatedOutput> outputs = new ArrayList<>();
        /// Number of publication attempts.
        private int commits;
        /// Number of rollback calls.
        private int rollbacks;
        /// Number of transaction close calls.
        private int transactionCloses;

        /// Opens an ordinary first volume and a held second volume.
        @Override
        public WritableByteChannel openVolume(long index) {
            assertEquals(outputs.size(), index);
            GatedOutput output = index == 1 ? blocked : new GatedOutput(false);
            if (index == 1) output.armed.set(true);
            outputs.add(output);
            return output;
        }

        /// Counts forbidden publication of a cancelled archive.
        @Override
        public void commit(long finalVolumeIndex) {
            commits++;
        }

        /// Requires all physical outputs to have closed before rollback.
        @Override
        public void rollback() {
            for (GatedOutput output : outputs) assertFalse(output.isOpen());
            rollbacks++;
        }

        /// Records transaction cleanup after rollback.
        @Override
        public void close() {
            assertEquals(1, rollbacks);
            transactionCloses++;
        }
    }
}
