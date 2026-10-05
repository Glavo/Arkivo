// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.deflate.DeflateCodec;
import org.glavo.arkivo.codec.internal.CodecChannelAdapters;
import org.glavo.arkivo.internal.StreamChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

/// Retains at most one healthy Deflate engine within a serialized ZIP writing session.
@NotNullByDefault
final class ZipDeflateEncoderPool implements AutoCloseable {
    /// A completed engine available for reset, or null.
    private @Nullable CompressionEncoder.Flushable idle;
    /// The sole active entry lease, or null.
    private @Nullable Lease active;
    /// Whether this writing session has released its engine resources.
    private boolean closed;

    /// Creates a channel that borrows the entry target and exclusively leases the reusable engine.
    CompressingWritableByteChannel open(OutputStream output) throws IOException {
        if (closed || active != null) throw new IllegalStateException("Deflate workspace is unavailable");
        @Nullable CompressionEncoder.Flushable engine = idle;
        idle = null;
        if (engine == null) {
            engine = DeflateCodec.DEFAULT.newEncoder();
        } else {
            try { engine.reset(); }
            catch (RuntimeException | Error exception) {
                try { engine.close(); }
                catch (RuntimeException | Error cleanup) { if (cleanup != exception) exception.addSuppressed(cleanup); }
                throw exception;
            }
        }
        Lease lease = new Lease(engine);
        active = lease;
        return CodecChannelAdapters.newFlushableWritableByteChannel(
                new Target(StreamChannelAdapters.writableChannel(output), lease), ResourceOwnership.BORROWED, () -> lease);
    }

    /// Releases cached or unfinished engines without producing output.
    @Override
    public void close() {
        closed = true;
        @Nullable Lease lease = active;
        if (lease != null) lease.close();
        @Nullable CompressionEncoder.Flushable engine = idle;
        idle = null;
        if (engine != null) engine.close();
    }

    /// Separates an entry lifecycle from the physical encoder lifecycle.
    @NotNullByDefault
    private final class Lease implements CompressionEncoder.Flushable {
        /// The exclusively leased engine.
        private final CompressionEncoder.Flushable engine;
        /// Whether final output has been produced by the engine.
        private boolean finished;
        /// Whether the engine or its target failed.
        private boolean failed;
        /// Whether the adapter has released this lease.
        private boolean released;

        /// Takes ownership of a fresh or successfully reset engine.
        private Lease(CompressionEncoder.Flushable engine) { this.engine = engine; }
        /// Rejects use of a lease already returned to its owner.
        private void ensureOpen() { if (released) throw new IllegalStateException("ZIP encoder lease is closed"); }
        /// Encodes entry input and records engine failures.
        @Override
        public CodecOutcome encode(ByteBuffer source, ByteBuffer target) throws IOException {
            ensureOpen();
            try { return engine.encode(source, target); }
            catch (IOException | RuntimeException | Error exception) { failed = true; throw exception; }
        }
        /// Flushes the entry without releasing its engine.
        @Override
        public CodecOutcome flush(ByteBuffer target) throws IOException {
            ensureOpen();
            try { return engine.flush(target); }
            catch (IOException | RuntimeException | Error exception) { failed = true; throw exception; }
        }
        /// Marks the engine reusable only after its final bytes have been produced.
        @Override
        public CodecOutcome finish(ByteBuffer target) throws IOException {
            ensureOpen();
            try {
                CodecOutcome outcome = engine.finish(target);
                finished = outcome == CodecOutcome.FINISHED;
                return outcome;
            } catch (IOException | RuntimeException | Error exception) { failed = true; throw exception; }
        }
        /// Rejects resetting an engine while its entry channel remains active.
        @Override
        public void reset() { throw new UnsupportedOperationException("Reset requires a new ZIP entry"); }
        /// Returns a successfully completed engine or destroys an incomplete or failed engine.
        @Override
        public void close() {
            if (released) return;
            released = true;
            active = null;
            if (finished && !failed && !closed) idle = engine;
            else engine.close();
        }
    }

    /// Marks target failures before the adapter decides whether to release the completed encoder.
    @NotNullByDefault
    private static final class Target implements WritableByteChannel {
        /// The entry's borrowed output channel.
        private final WritableByteChannel target;
        /// The entry lease whose output is being written.
        private final ZipDeflateEncoderPool.Lease lease;
        /// Binds the entry transport to its failure state.
        private Target(WritableByteChannel target, ZipDeflateEncoderPool.Lease lease) {
            this.target = target;
            this.lease = lease;
        }
        /// Preserves partial progress and prevents reuse after an output failure or stall.
        @Override
        public int write(ByteBuffer source) throws IOException {
            boolean nonempty = source.hasRemaining();
            try {
                int written = target.write(source);
                if (nonempty && written == 0) lease.failed = true;
                return written;
            } catch (IOException | RuntimeException | Error exception) { lease.failed = true; throw exception; }
        }
        /// Reports the entry transport state.
        @Override
        public boolean isOpen() { return target.isOpen(); }
        /// Closes the delegated transport when explicitly requested.
        @Override
        public void close() throws IOException { target.close(); }
    }
}
