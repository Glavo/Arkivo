// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/// Creates progress-safe, close-retryable adapters between streams and channels.
///
/// Endpoint failures retain their original type and identity when first reported. If cleanup rethrows an exception
/// already reported by this adapter, it is wrapped in a new `IOException` so automatic cleanup cannot suppress an
/// exception on itself. Distinct cleanup failures retain their original type and identity. Successful cleanup is
/// idempotent; failed cleanup may be retried.
///
/// @implNote Channel reads and writes are serialized independently of closure. Endpoint I/O and closure run outside
/// the monitor protecting failure identities.
@NotNullByDefault
public final class StreamChannelAdapters {
    /// The largest temporary transfer used for a direct buffer.
    private static final int TRANSFER_SIZE = 8192;

    /// Creates no instances.
    private StreamChannelAdapters() {
    }

    /// Returns a channel that owns and reads directly from the input stream.
    /// Concurrent reads are serialized. Closure invokes the stream's close method without acquiring the read lock;
    /// the stream determines whether closure unblocks an outstanding read.
    ///
    /// @param source the input stream whose ownership is transferred to the channel
    /// @return a readable channel that preserves close-retry behavior
    public static ReadableByteChannel readableChannel(InputStream source) {
        return new InputStreamChannel(Objects.requireNonNull(source, "source"));
    }

    /// Returns a channel that owns and writes directly to the output stream.
    ///
    /// A write advances the source position only for stream writes that complete normally; the limit is unchanged.
    /// If a later chunk fails, earlier completed chunks remain consumed. A failing stream write may have emitted an
    /// unknown number of bytes, which cannot be reflected in the source position. Empty writes do not access the stream.
    /// The channel does not retain source buffers or flush the stream after writing.
    /// Concurrent writes are serialized. Closure invokes the stream's close method without acquiring the write lock;
    /// the stream determines whether closure unblocks an outstanding write.
    ///
    /// @param target the output stream whose ownership is transferred to the channel
    /// @return a writable channel that preserves close-retry behavior
    public static WritableByteChannel writableChannel(OutputStream target) {
        return new OutputStreamChannel(Objects.requireNonNull(target, "target"));
    }

    /// Returns an input stream that owns the readable channel.
    ///
    /// @param source the channel whose ownership is transferred to the stream
    /// @return an input stream that rejects zero-progress channel reads and uses positioning for seekable-channel skips
    public static InputStream inputStream(ReadableByteChannel source) {
        return new ChannelInputStream(Objects.requireNonNull(source, "source"));
    }

    /// Returns an output stream that owns the writable channel.
    ///
    /// @param target the channel whose ownership is transferred to the stream
    /// @return an output stream that rejects zero-progress channel writes
    public static OutputStream outputStream(WritableByteChannel target) {
        return new ChannelOutputStream(Objects.requireNonNull(target, "target"));
    }

    /// Adapts one input stream to a readable channel.
    @NotNullByDefault
    private static final class InputStreamChannel implements ReadableByteChannel, ForceCloseable {
        /// Serializes transfers without preventing concurrent endpoint closure.
        private final Object ioLock = new Object();

        /// Serializes close attempts independently of transfers and failure bookkeeping.
        private final Object closeLock = new Object();

        /// Serializes abort attempts without waiting for graceful source closure.
        private final Object forceCloseLock = new Object();

        /// Whether source cleanup must bypass draining and final validation.
        private volatile boolean abortRequested;

        /// Previously reported endpoint failures, allocated only after an operation fails.
        private @Nullable Set<Throwable> reportedFailures;

        /// The backing input stream.
        private final InputStream source;

        /// Transfer storage allocated on the first nonempty read into an inaccessible backing array.
        private byte @Nullable [] transferBuffer;

        /// Whether this adapter remains open.
        private volatile boolean open = true;

        /// Creates an input-stream channel.
        private InputStreamChannel(InputStream source) {
            this.source = source;
        }

        /// Performs one stream read and preserves zero-progress results for the channel caller.
        @Override
        public int read(ByteBuffer target) throws IOException {
            synchronized (ioLock) {
                Objects.requireNonNull(target, "target");
                ensureOpen();
                if (target.isReadOnly()) {
                    throw new IllegalArgumentException("target must be writable");
                }
                if (!target.hasRemaining()) {
                    return 0;
                }
                if (target.hasArray()) {
                    int position = target.position();
                    int read = readSource(
                            target.array(),
                            target.arrayOffset() + position,
                            target.remaining()
                    );
                    if (read > 0) {
                        target.position(position + read);
                    }
                    return read;
                }

                byte @Nullable [] buffer = transferBuffer;
                if (buffer == null) {
                    buffer = new byte[TRANSFER_SIZE];
                    transferBuffer = buffer;
                }
                int read = readSource(buffer, 0, Math.min(target.remaining(), buffer.length));
                if (read > 0) {
                    target.put(buffer, 0, read);
                }
                return read;
            }
        }

        /// Reads the source and records failures that must remain primary during automatic cleanup.
        private int readSource(byte[] bytes, int offset, int length) throws IOException {
            try {
                return source.read(bytes, offset, length);
            } catch (IOException | RuntimeException | Error failure) {
                recordFailure(failure);
                throw failure;
            }
        }

        /// Returns whether this adapter remains open.
        @Override
        public boolean isOpen() {
            return open && !abortRequested;
        }

        /// Closes the stream and commits closure only after success.
        @Override
        public void close() throws IOException {
            synchronized (closeLock) {
                if (!open) {
                    return;
                }
                if (abortRequested) {
                    forceClose();
                    return;
                }
                try {
                    source.close();
                    synchronized (forceCloseLock) {
                        if (!abortRequested) {
                            synchronized (this) {
                                open = false;
                                reportedFailures = null;
                            }
                        }
                    }
                } catch (IOException | RuntimeException | Error failure) {
                    if (!recordFailure(failure)) {
                        IOException cleanupFailure = new IOException("Endpoint cleanup repeated an earlier failure", failure);
                        recordFailure(cleanupFailure);
                        throw cleanupFailure;
                    }
                    throw failure;
                }
            }
        }

        /// Cancels source operations without acquiring the ordinary close or transfer lock.
        @Override
        public void forceClose() throws IOException {
            abortRequested = true;
            synchronized (forceCloseLock) {
                if (!open) {
                    return;
                }
                try {
                    ForceCloseable.forceClose(source);
                    synchronized (this) {
                        open = false;
                    }
                } catch (IOException | RuntimeException | Error failure) {
                    if (!recordFailure(failure)) {
                        IOException cleanupFailure = new IOException("Endpoint cleanup repeated an earlier failure", failure);
                        recordFailure(cleanupFailure);
                        throw cleanupFailure;
                    }
                    throw failure;
                }
            }
        }

        /// Retains failures after cancellation because a concurrent graceful close may still report them.
        private synchronized boolean recordFailure(Throwable failure) {
            if (!open && !abortRequested) {
                return true;
            }
            if (reportedFailures == null) {
                reportedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
            }
            return reportedFailures.add(failure);
        }

        /// Requires this adapter to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open || abortRequested) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Adapts one output stream to a writable channel.
    @NotNullByDefault
    private static final class OutputStreamChannel implements WritableByteChannel {
        /// Serializes transfers without preventing concurrent endpoint closure.
        private final Object ioLock = new Object();

        /// Serializes close attempts independently of transfers and failure bookkeeping.
        private final Object closeLock = new Object();

        /// Previously reported endpoint failures, allocated only after an operation fails.
        private @Nullable Set<Throwable> reportedFailures;

        /// The backing output stream.
        private final OutputStream target;

        /// Transfer storage allocated on the first nonempty write without an accessible backing array.
        private byte @Nullable [] transferBuffer;

        /// Whether this adapter remains open.
        private volatile boolean open = true;

        /// Creates an output-stream channel.
        private OutputStreamChannel(OutputStream target) {
            this.target = target;
        }

        /// Writes all remaining source bytes and advances only successfully written chunks.
        @Override
        public int write(ByteBuffer source) throws IOException {
            synchronized (ioLock) {
                Objects.requireNonNull(source, "source");
                ensureOpen();
                if (!source.hasRemaining()) {
                    return 0;
                }
                int start = source.position();
                if (source.hasArray()) {
                    int count = source.remaining();
                    writeTarget(
                            source.array(),
                            source.arrayOffset() + source.position(),
                            count
                    );
                    source.position(source.limit());
                    return count;
                }

                byte[] buffer = transferBuffer;
                if (buffer == null) {
                    buffer = new byte[TRANSFER_SIZE];
                    transferBuffer = buffer;
                }
                while (source.hasRemaining()) {
                    int count = Math.min(source.remaining(), buffer.length);
                    source.get(source.position(), buffer, 0, count);
                    writeTarget(buffer, 0, count);
                    source.position(source.position() + count);
                }
                return source.position() - start;
            }
        }

        /// Writes the target and records failures that must remain primary during automatic cleanup.
        private void writeTarget(byte[] bytes, int offset, int length) throws IOException {
            try {
                target.write(bytes, offset, length);
            } catch (IOException | RuntimeException | Error failure) {
                recordFailure(failure);
                throw failure;
            }
        }

        /// Returns whether this adapter remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes the stream and commits closure only after success.
        @Override
        public void close() throws IOException {
            synchronized (closeLock) {
                if (!open) {
                    return;
                }
                try {
                    target.close();
                    synchronized (this) {
                        open = false;
                        reportedFailures = null;
                    }
                } catch (IOException | RuntimeException | Error failure) {
                    if (!recordFailure(failure)) {
                        IOException cleanupFailure = new IOException("Endpoint cleanup repeated an earlier failure", failure);
                        recordFailure(cleanupFailure);
                        throw cleanupFailure;
                    }
                    throw failure;
                }
            }
        }

        /// Records a failure while open and reports whether it needs no repeated-cleanup wrapper.
        private synchronized boolean recordFailure(Throwable failure) {
            if (!open) {
                return true;
            }
            if (reportedFailures == null) {
                reportedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
            }
            return reportedFailures.add(failure);
        }

        /// Requires this adapter to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Adapts one readable channel to an input stream.
    @NotNullByDefault
    private static final class ChannelInputStream extends InputStream implements ForceCloseable {
        /// Serializes graceful cleanup without preventing concurrent cancellation.
        private final Object closeLock = new Object();

        /// Serializes abort attempts without waiting for graceful source closure.
        private final Object forceCloseLock = new Object();

        /// Whether source cleanup must bypass draining and final validation.
        private volatile boolean abortRequested;

        /// Previously reported endpoint failures, allocated only after an operation fails.
        private @Nullable Set<Throwable> reportedFailures;

        /// The backing readable channel.
        private final ReadableByteChannel source;

        /// Single-byte storage and its channel view, allocated on the first scalar read.
        private @Nullable ByteBuffer singleByte;

        /// Lazily allocated storage for one bounded skip from a non-seekable source.
        private @Nullable ByteBuffer skipBuffer;

        /// Whether this adapter remains open.
        private volatile boolean open = true;

        /// Creates a readable-channel input stream.
        private ChannelInputStream(ReadableByteChannel source) {
            this.source = source;
        }

        /// Reads one byte from the channel.
        @Override
        public int read() throws IOException {
            ensureOpen();
            @Nullable ByteBuffer target = singleByte;
            if (target == null) singleByte = target = ByteBuffer.allocate(1);
            target.clear();
            int read = readOnce(target);
            return read < 0 ? -1 : Byte.toUnsignedInt(target.get(0));
        }

        /// Performs one channel read and rejects zero progress for a nonempty request.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            ensureOpen();
            if (length == 0) {
                return 0;
            }
            return readOnce(ByteBuffer.wrap(bytes, offset, length));
        }

        /// Reads into a nonempty target, retaining endpoint failure identities and rejecting zero progress.
        private int readOnce(ByteBuffer target) throws IOException {
            try {
                int read = source.read(target);
                if (read == 0) {
                    throw new IOException("Readable channel made no progress");
                }
                return read;
            } catch (IOException | RuntimeException | Error failure) {
                recordFailure(failure);
                throw failure;
            }
        }

        /// Skips by repositioning a seekable source or by performing one bounded read.
        /// A non-seekable source may skip fewer bytes than requested, exposing progress before another read can fail.
        @Override
        public long skip(long count) throws IOException {
            ensureOpen();
            if (count <= 0L) {
                return 0L;
            }
            if (source instanceof SeekableByteChannel seekableSource) {
                try {
                    long position = seekableSource.position();
                    long available = Math.max(0L, seekableSource.size() - position);
                    long skipped = Math.min(count, available);
                    seekableSource.position(position + skipped);
                    return skipped;
                } catch (IOException | RuntimeException | Error failure) {
                    recordFailure(failure);
                    throw failure;
                }
            }
            @Nullable ByteBuffer target = skipBuffer;
            if (target == null) skipBuffer = target = ByteBuffer.allocate(TRANSFER_SIZE);
            target.clear().limit((int) Math.min(count, target.capacity()));
            int read = readOnce(target);
            return Math.max(0, read);
        }

        /// Closes the channel and commits closure only after success.
        @Override
        public void close() throws IOException {
            synchronized (closeLock) {
                if (!open) {
                    return;
                }
                if (abortRequested) {
                    forceClose();
                    return;
                }
                try {
                    source.close();
                    synchronized (forceCloseLock) {
                        if (!abortRequested) {
                            synchronized (this) {
                                open = false;
                                reportedFailures = null;
                            }
                        }
                    }
                } catch (IOException | RuntimeException | Error failure) {
                    if (!recordFailure(failure)) {
                        IOException cleanupFailure = new IOException("Endpoint cleanup repeated an earlier failure", failure);
                        recordFailure(cleanupFailure);
                        throw cleanupFailure;
                    }
                    throw failure;
                }
            }
        }

        /// Cancels source operations without acquiring the ordinary close or transfer lock.
        @Override
        public void forceClose() throws IOException {
            abortRequested = true;
            synchronized (forceCloseLock) {
                if (!open) {
                    return;
                }
                try {
                    ForceCloseable.forceClose(source);
                    synchronized (this) {
                        open = false;
                    }
                } catch (IOException | RuntimeException | Error failure) {
                    if (!recordFailure(failure)) {
                        IOException cleanupFailure = new IOException("Endpoint cleanup repeated an earlier failure", failure);
                        recordFailure(cleanupFailure);
                        throw cleanupFailure;
                    }
                    throw failure;
                }
            }
        }

        /// Retains failures after cancellation because a concurrent graceful close may still report them.
        private synchronized boolean recordFailure(Throwable failure) {
            if (!open && !abortRequested) {
                return true;
            }
            if (reportedFailures == null) {
                reportedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
            }
            return reportedFailures.add(failure);
        }

        /// Requires this adapter to remain open.
        private void ensureOpen() throws IOException {
            if (!open || abortRequested) {
                throw new IOException("Stream closed");
            }
        }
    }

    /// Adapts one writable channel to an output stream.
    @NotNullByDefault
    private static final class ChannelOutputStream extends OutputStream {
        /// Serializes endpoint closure and failure bookkeeping independently of writes.
        private final Object closeLock = new Object();

        /// Previously reported endpoint failures, allocated only after an operation fails.
        private @Nullable Set<Throwable> reportedFailures;

        /// The backing writable channel.
        private final WritableByteChannel target;

        /// Single-byte storage allocated on the first scalar write.
        private @Nullable ByteBuffer singleByte;

        /// Whether this adapter remains open.
        private volatile boolean open = true;

        /// Creates a writable-channel output stream.
        private ChannelOutputStream(WritableByteChannel target) {
            this.target = target;
        }

        /// Writes one byte to the channel.
        @Override
        public void write(int value) throws IOException {
            ensureOpen();
            @Nullable ByteBuffer source = singleByte;
            if (source == null) singleByte = source = ByteBuffer.allocate(1);
            source.clear().put((byte) value).flip();
            writeFully(source);
        }

        /// Writes all requested bytes and rejects a zero-progress channel.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            ensureOpen();
            writeFully(ByteBuffer.wrap(bytes, offset, length));
        }

        /// Drains one call's source without retaining it and rejects a stalled channel.
        private void writeFully(ByteBuffer source) throws IOException {
            try {
                while (source.hasRemaining()) {
                    if (target.write(source) == 0) {
                        throw new IOException("Writable channel made no progress");
                    }
                }
            } catch (IOException | RuntimeException | Error failure) {
                recordFailure(failure);
                throw failure;
            }
        }

        /// Closes the channel and commits closure only after success.
        @Override
        public void close() throws IOException {
            synchronized (closeLock) {
                if (!open) {
                    return;
                }
                try {
                    target.close();
                    synchronized (this) {
                        open = false;
                        reportedFailures = null;
                    }
                } catch (IOException | RuntimeException | Error failure) {
                    if (!recordFailure(failure)) {
                        IOException cleanupFailure = new IOException("Endpoint cleanup repeated an earlier failure", failure);
                        recordFailure(cleanupFailure);
                        throw cleanupFailure;
                    }
                    throw failure;
                }
            }
        }

        /// Records a failure while open and reports whether it needs no repeated-cleanup wrapper.
        private synchronized boolean recordFailure(Throwable failure) {
            if (!open) {
                return true;
            }
            if (reportedFailures == null) {
                reportedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
            }
            return reportedFailures.add(failure);
        }

        /// Requires this adapter to remain open.
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new IOException("Stream closed");
            }
        }
    }
}
