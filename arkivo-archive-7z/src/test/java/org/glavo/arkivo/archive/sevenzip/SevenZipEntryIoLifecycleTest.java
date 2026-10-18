// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests cancellation through complete 7z entry decoding graphs.
@NotNullByDefault
final class SevenZipEntryIoLifecycleTest {
    /// Strict closure reaches packed sources without draining Copy, compressed, filtered, or encrypted entries.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void cancelsEntryStreamsWithoutDraining(int operation) throws Exception {
        for (int graph = 0; graph < 3; graph++) {
            for (boolean encrypted : new boolean[]{false, true}) {
                for (boolean failClose : new boolean[]{false, true}) {
                    var password = ArkivoPasswordProvider.fixed(new byte[]{'p', 0});
                    var create = SevenZipArchiveOptions.CREATE_DEFAULTS
                            .withCompression(graph == 0 ? SevenZipCompression.copy() : SevenZipCompression.lzma2(65536))
                            .withFilters(graph == 0 ? SevenZipFilterChain.EMPTY : SevenZipFilterChain.of(
                                    graph == 1 ? SevenZipFilter.delta(1) : SevenZipFilter.of(SevenZipFilterMethod.BCJ2)));
                    if (encrypted) create = create.withPasswordProvider(password);
                    var output = new ByteArrayOutputStream();
                    try (var writer = SevenZipArkivoStreamingWriter.open(output, create);
                         var body = writer.beginFile("a").openOutputStream()) {
                        body.write(new byte[]{1, 2, 3});
                    }
                    var gate = new Gate();
                    var physical = new ArrayList<GatedArchiveChannel>();
                    byte[] archive = output.toByteArray();
                    ArkivoSeekableChannelSource source = () -> {
                        var channel = new GatedArchiveChannel(archive, gate);
                        physical.add(channel);
                        return channel;
                    };
                    var defaults = SevenZipArchiveOptions.READ_DEFAULTS.withPasswordProvider(password);
                    var options = defaults.withCommon(defaults.common().withThreadSafety(ArkivoFileSystemThreadSafety.STRICT));
                    try (var fs = SevenZipArkivoFileSystem.open(source, options)) {
                        physical.clear();
                        var input = Files.newInputStream(fs.getPath("a"));
                        gate.armed = true;
                        gate.failClose.set(failClose);
                        var active = new FutureTask<>(() -> {
                            if (operation == 2) input.close();
                            else input.read();
                            return true;
                        });
                        if (operation != 0) start(active);
                        try {
                            if (operation != 0) assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
                            var cancel = new FutureTask<>(() -> {
                                if (failClose) assertThrows(IOException.class, fs::close);
                                fs.close();
                                return true;
                            });
                            start(cancel);
                            assertTrue(cancel.get(5, TimeUnit.SECONDS));
                            if (operation != 0) {
                                assertTrue(assertThrows(ExecutionException.class,
                                        () -> active.get(5, TimeUnit.SECONDS)).getCause() instanceof IOException);
                            }
                            assertEquals(operation == 0 ? 0 : 1, gate.readCalls.get(), "graph=" + graph);
                            input.close();
                            assertEquals(physical.size() + (failClose ? 1 : 0), gate.closeCalls.get());
                            for (var channel : physical) assertTrue(channel.closed);
                            assertThrows(IOException.class, input::read);
                        } finally {
                            gate.release.countDown();
                            input.close();
                        }
                    }
                }
            }
        }
    }

    /// Starts a bounded operation without leaving a non-daemon worker on regression.
    private static void start(FutureTask<?> task) {
        Thread worker = new Thread(task, "sevenzip-entry-cancellation-test");
        worker.setDaemon(true);
        worker.start();
    }

    /// Gates the first physical data read across all packed branches.
    @NotNullByDefault
    private static final class Gate {
        /// Enables gating after entry setup has completed.
        private volatile boolean armed;
        /// Signals that one branch is waiting for source closure.
        private final CountDownLatch entered = new CountDownLatch(1);
        /// Releases the active read when its physical source closes.
        private final CountDownLatch release = new CountDownLatch(1);
        /// Selects only the first read for gating.
        private final AtomicBoolean selected = new AtomicBoolean();
        /// Makes one physical cleanup fail without releasing the active read.
        private final AtomicBoolean failClose = new AtomicBoolean();
        /// Counts physical reads after entry setup.
        private final AtomicInteger readCalls = new AtomicInteger();
        /// Counts physical cleanup attempts after entry setup.
        private final AtomicInteger closeCalls = new AtomicInteger();
    }

    /// Owns an independent cursor and makes cancellation observable before releasing a blocked read.
    @NotNullByDefault
    private static final class GatedArchiveChannel implements SeekableByteChannel {
        /// The immutable archive's independent cursor.
        private final ReadOnlyByteArrayChannel delegate;
        /// The gate shared across this entry's packed branches.
        private final Gate gate;
        /// Whether this channel owns the blocked read.
        private volatile boolean selected;
        /// Whether physical cleanup succeeded.
        private volatile boolean closed;

        /// Creates one independently positioned packed-source handle.
        private GatedArchiveChannel(byte[] archive, Gate gate) {
            this.delegate = new ReadOnlyByteArrayChannel(archive);
            this.gate = gate;
        }

        /// Reads bytes and pauses the first armed data operation.
        @Override
        public int read(ByteBuffer target) throws IOException {
            int count = delegate.read(target);
            if (gate.armed) {
                gate.readCalls.incrementAndGet();
                if (gate.selected.compareAndSet(false, true)) {
                    selected = true;
                    gate.entered.countDown();
                    try {
                        if (!gate.release.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for cancellation");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException(exception);
                    }
                    if (closed) throw new AsynchronousCloseException();
                }
            }
            return count;
        }

        /// Rejects writes.
        @Override
        public int write(ByteBuffer source) { throw new NonWritableChannelException(); }

        /// Returns the independent archive position.
        @Override
        public long position() throws IOException { return delegate.position(); }

        /// Changes the independent archive position.
        @Override
        public SeekableByteChannel position(long value) throws IOException { delegate.position(value); return this; }

        /// Returns the archive size.
        @Override
        public long size() throws IOException { return delegate.size(); }

        /// Rejects truncation.
        @Override
        public SeekableByteChannel truncate(long size) { throw new NonWritableChannelException(); }

        /// Returns whether physical cleanup remains outstanding.
        @Override
        public boolean isOpen() { return !closed; }

        /// Closes the physical source, failing once when requested before releasing its active read.
        @Override
        public void close() throws IOException {
            if (gate.armed) {
                gate.closeCalls.incrementAndGet();
                if (gate.failClose.getAndSet(false)) throw new IOException("Physical close failed");
            }
            closed = true;
            delegate.close();
            if (selected) gate.release.countDown();
        }
    }
}
