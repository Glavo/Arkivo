// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.deflate.DeflateCodec;
import org.glavo.arkivo.codec.internal.CodecChannelAdapters;
import org.glavo.arkivo.codec.internal.CompressionDecoderSupport;
import org.glavo.arkivo.internal.StreamChannelAdapters;
import org.glavo.arkivo.internal.ForceCloseable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/// Retains bounded Deflate workspaces while giving each active ZIP entry an independent adapter and limits.
@NotNullByDefault
final class ZipDeflateDecoderPool implements AutoCloseable {
    /// The initial input capacity used when no compressed-size hint is supplied.
    private static final int DEFAULT_INPUT_CAPACITY = 8192;

    /// The largest compressed-input buffer retained by one workspace.
    private static final int MAXIMUM_INPUT_CAPACITY = 65_536;

    /// The maximum number of idle workspaces retained by this owner.
    private final int maximumIdle;

    /// Workspaces whose preceding entries completed all transport and integrity checks.
    private final ArrayDeque<Workspace> idle = new ArrayDeque<>();

    /// Leases still decoding or awaiting successful transport cleanup.
    private final Set<Lease> active = Collections.newSetFromMap(new IdentityHashMap<>());

    /// Whether the owner has begun closing its resources.
    private boolean closed;

    /// Creates a cache with the specified idle-workspace bound.
    ZipDeflateDecoderPool(int maximumIdle) {
        this.maximumIdle = maximumIdle;
    }

    /// Opens a lease without requesting larger input storage. Previously enlarged storage remains reusable.
    /// Streaming readers use only this overload to keep read-ahead within their pushback capacity.
    Lease open(InputStream source, long decodedSize, ArchiveReadLimits limits) throws IOException {
        return open(StreamChannelAdapters.readableChannel(source), decodedSize,
                ZipArkivoEntryAttributes.UNKNOWN_SIZE, limits);
    }

    /// Opens an owning decoder lease with a fresh output limiter and independent counters.
    /// The compressed size selects reusable input storage between 8 and 64 KiB; it does not bound source reads.
    /// Sources that must not read beyond an entry must already be bounded by the caller.
    ///
    /// @param source compressed entry data
    /// @param decodedSize expected output size, or [ZipArkivoEntryAttributes#UNKNOWN_SIZE]
    /// @param compressedSize input size hint, or [ZipArkivoEntryAttributes#UNKNOWN_SIZE]
    /// @param limits per-entry decompression limits
    /// @return an independent lease on an exclusive workspace
    /// @throws IllegalArgumentException if either size is less than `UNKNOWN_SIZE`
    /// @throws IOException if decoder setup or source cleanup fails
    synchronized Lease open(ReadableByteChannel source, long decodedSize, long compressedSize, ArchiveReadLimits limits)
            throws IOException {
        if (closed) {
            throw new IllegalStateException("ZIP decoder workspace owner is closed");
        }
        if (compressedSize < ZipArkivoEntryAttributes.UNKNOWN_SIZE) {
            throw new IllegalArgumentException("Invalid compressed size: " + compressedSize);
        }
        int inputCapacity = DEFAULT_INPUT_CAPACITY;
        // Grow geometrically so a sequence of slightly larger entries does not repeatedly replace storage.
        while (inputCapacity < compressedSize && inputCapacity < MAXIMUM_INPUT_CAPACITY) {
            inputCapacity *= 2;
        }
        CompressionDecoderSupport.validateLimit(decodedSize, "decodedSize");
        try {
            CompressionDecoderSupport.requireWindowSize(limits.effectiveCompressionWindowSize(), 32768);
        } catch (IOException failure) {
            try {
                ForceCloseable.forceClose(source);
            } catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
        @Nullable Workspace workspace = idle.pollFirst();
        if (workspace == null) {
            workspace = new Workspace(inputCapacity);
        }
        try {
            if (workspace.input.capacity() < inputCapacity) {
                workspace.input = ByteBuffer.allocate(inputCapacity);
            }
            workspace.engine.reset();
            Lease lease = new Lease(workspace, source, decodedSize);
            active.add(lease);
            return lease;
        } catch (IOException | RuntimeException | Error failure) {
            workspace.close();
            throw failure;
        }
    }

    /// Releases a successfully closed lease, caching it only after explicit ZIP integrity confirmation.
    private synchronized void release(Lease lease) {
        if (lease.released) {
            return;
        }
        lease.released = true;
        active.remove(lease);
        if (!closed && lease.finished && lease.verified && !lease.failed && idle.size() < maximumIdle) {
            idle.addFirst(lease.workspace);
        } else {
            lease.workspace.close();
        }
    }

    /// Aborts active entries and releases idle engines, retaining source-close failures for retry.
    @Override
    public void close() throws IOException {
        ArrayList<Lease> leases;
        synchronized (this) {
            closed = true;
            leases = new ArrayList<>(active);
            while (!idle.isEmpty()) {
                idle.removeFirst().close();
            }
        }
        @Nullable Throwable failure = null;
        for (Lease lease : leases) {
            lease.failed = true;
            try {
                lease.forceClose();
            } catch (IOException | RuntimeException | Error exception) {
                if (failure == null) {
                    failure = exception;
                } else if (exception != failure) {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error exception) {
            throw exception;
        }
    }

    /// Owns reusable algorithm state and compressed-input storage, but no entry metadata or endpoint.
    @NotNullByDefault
    private static final class Workspace {
        /// The raw Deflate engine without an entry-specific output limiter.
        private final CompressionDecoder engine = DeflateCodec.DEFAULT.newDecoder();

        /// The heap input buffer, exclusive to the active lease.
        private ByteBuffer input;

        /// Whether the algorithm state has been disposed.
        private boolean closed;

        /// Creates the engine and its bounded staging buffer.
        private Workspace(int inputCapacity) throws IOException {
            input = ByteBuffer.allocate(inputCapacity);
        }

        /// Releases algorithm state once without producing or consuming bytes.
        private void close() {
            if (!closed) {
                closed = true;
                engine.close();
            }
        }
    }

    /// Keeps an entry's adapter and verification state separate from its reusable engine.
    @NotNullByDefault
    final class Lease implements DecompressingReadableByteChannel, ForceCloseable {
        /// The exclusive algorithm workspace.
        private final Workspace workspace;

        /// The independent transport adapter and output limiter.
        private final DecompressingReadableByteChannel channel;

        /// Whether the engine reached the raw Deflate stream boundary.
        private boolean finished;

        /// Whether all ZIP size, CRC, descriptor and authentication checks completed.
        private boolean verified;

        /// Whether any engine, source, integrity or cleanup operation failed.
        private volatile boolean failed;

        /// Whether closure would abort a decoding operation rather than close an idle adapter.
        private volatile boolean reading;

        /// Whether cleanup must continue cancelling an incompletely closed source.
        private volatile boolean aborted;

        /// Whether this workspace has been returned or disposed.
        private boolean released;

        /// Binds one source and a fresh output limiter to the exclusive workspace.
        private Lease(Workspace workspace, ReadableByteChannel source, long maximumOutputSize) throws IOException {
            this.workspace = workspace;
            CompressionDecoder limited = CompressionDecoderSupport.limitEngineOutput(
                    new EngineLease(this), maximumOutputSize);
            channel = CodecChannelAdapters.newReadableByteChannel(
                    new Source(source, this),
                    ResourceOwnership.OWNED, () -> limited, workspace.input);
        }

        /// Confirms ZIP integrity after the end-of-stream and external metadata checks succeed.
        void verified() {
            verified = true;
        }

        /// Reads bytes with this entry's independent counters and limit.
        @Override
        public int read(ByteBuffer target) throws IOException {
            reading = true;
            try {
                return channel.read(target);
            } catch (IOException | RuntimeException | Error exception) {
                failed = true;
                throw exception;
            } finally {
                reading = false;
            }
        }

        /// Decodes one incremental operation without changing the adapter's source-failure recovery behavior.
        @Override
        public CodecResult decode(ByteBuffer target) throws IOException {
            reading = true;
            try {
                return channel.decode(target);
            } catch (IOException | RuntimeException | Error exception) {
                failed = true;
                throw exception;
            } finally {
                reading = false;
            }
        }

        /// Returns compressed bytes consumed by this entry's engine.
        @Override
        public long inputBytes() {
            return channel.inputBytes();
        }

        /// Returns compressed bytes fetched for this entry.
        @Override
        public long sourceBytes() {
            return channel.sourceBytes();
        }

        /// Returns the adapter's view, valid until the next operation or closure.
        @Override
        public @UnmodifiableView ByteBuffer unconsumedInput() {
            return channel.unconsumedInput();
        }

        /// Returns decoded bytes delivered by this entry.
        @Override
        public long outputBytes() {
            return channel.outputBytes();
        }

        /// Returns whether this entry adapter is open.
        @Override
        public boolean isOpen() {
            return channel.isOpen();
        }

        /// Releases the engine only after owning-source cleanup succeeds; source closure remains retryable.
        @Override
        public void close() throws IOException {
            if (aborted) {
                forceClose();
                return;
            }
            if (released) {
                return;
            }
            if (reading || Thread.currentThread().isInterrupted()) {
                failed = true;
            }
            try {
                channel.close();
            } catch (IOException | RuntimeException | Error exception) {
                failed = true;
                // Failed cancellation may leave a decoder operation active. Dispose only after cleanup succeeds.
                throw exception;
            }
            release(this);
        }

        /// Cancels the transport before releasing an engine that must not return to the idle pool.
        @Override
        public void forceClose() throws IOException {
            if (released) return;
            aborted = true;
            failed = true;
            ForceCloseable.forceClose(channel);
            release(this);
        }
    }

    /// Records engine completion while leaving physical disposal to the ZIP lease owner.
    @NotNullByDefault
    private static final class EngineLease implements CompressionDecoder {
        /// The entry owning this engine operation.
        private final ZipDeflateDecoderPool.Lease owner;

        /// Creates the entry's logical engine view.
        private EngineLease(ZipDeflateDecoderPool.Lease owner) {
            this.owner = owner;
        }

        /// Decodes bytes and records a completed compression boundary, before ZIP integrity checks.
        @Override
        public CodecOutcome decode(ByteBuffer source, ByteBuffer target) throws IOException {
            CodecOutcome result = owner.workspace.engine.decode(source, target);
            if (result == CodecOutcome.FINISHED) {
                owner.finished = true;
            }
            return result;
        }

        /// Decodes final input and records a completed compression boundary, before ZIP integrity checks.
        @Override
        public CodecOutcome finish(ByteBuffer source, ByteBuffer target) throws IOException {
            CodecOutcome result = owner.workspace.engine.finish(source, target);
            if (result == CodecOutcome.FINISHED) {
                owner.finished = true;
            }
            return result;
        }

        /// Rejects resetting the engine while its owning entry remains active.
        @Override
        public void reset() {
            throw new UnsupportedOperationException("Reset requires a new ZIP entry");
        }

        /// Leaves disposal to the owner after ZIP validation and source cleanup.
        @Override
        public void close() {
        }
    }

    /// Prevents reuse of a workspace after a source operation fails, even if the adapter later recovers.
    @NotNullByDefault
    private static final class Source implements ReadableByteChannel, ForceCloseable {
        /// The source owned by this entry adapter.
        private final ReadableByteChannel source;

        /// The lease whose health is tracked.
        private final ZipDeflateDecoderPool.Lease owner;

        /// Wraps the entry source without sharing its position or lifetime.
        private Source(ReadableByteChannel source, ZipDeflateDecoderPool.Lease owner) {
            this.source = source;
            this.owner = owner;
        }

        /// Reads compressed bytes and records transport failures or stalls.
        @Override
        public int read(ByteBuffer target) throws IOException {
            try {
                int count = source.read(target);
                if (count == 0 && target.hasRemaining()) {
                    owner.failed = true;
                }
                return count;
            } catch (IOException | RuntimeException | Error exception) {
                owner.failed = true;
                throw exception;
            }
        }

        /// Returns the entry source state.
        @Override
        public boolean isOpen() {
            return source.isOpen();
        }

        /// Cancels source operations and permanently disqualifies the workspace from reuse.
        @Override
        public void forceClose() throws IOException {
            owner.failed = true;
            ForceCloseable.forceClose(source);
        }

        /// Closes the source while preserving failed closure for another attempt.
        @Override
        public void close() throws IOException {
            try {
                source.close();
            } catch (IOException | RuntimeException | Error exception) {
                owner.failed = true;
                throw exception;
            }
        }
    }
}
