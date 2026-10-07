// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies that only completed, successfully written streams return their engine for reuse.
@NotNullByDefault
final class ZipDeflateEncoderPoolTest {
    /// Each lease starts a fresh raw Deflate stream while retaining the same completed engine.
    @Test
    void reusesFinishedEngineWithoutLeakingHistory() throws Exception {
        try (var pool = new ZipDeflateEncoderPool()) {
            @Nullable Object expectedEngine = null;
            byte @Nullable [] expectedStorage = null;
            for (int size : new int[]{0, 1, 4096, 100000, 0, 37}) {
                byte[] body = new byte[size];
                new Random(size).nextBytes(body);
                var output = new RecordingOutput();
                try (var channel = pool.open(output)) { channel.write(ByteBuffer.wrap(body)); }
                assertNotNull(output.storage);
                if (expectedStorage != null) assertSame(expectedStorage, output.storage);
                expectedStorage = output.storage;
                @Nullable Object engine = idle(pool);
                assertNotNull(engine);
                if (expectedEngine != null) assertSame(expectedEngine, engine);
                expectedEngine = engine;
                Inflater inflater = new Inflater(true);
                try (var input = new InflaterInputStream(new ByteArrayInputStream(output.toByteArray()), inflater)) {
                    assertArrayEquals(body, input.readAllBytes());
                } finally { inflater.end(); }
            }
        }
    }

    /// A target failure after the engine returns FINISHED still destroys the engine.
    @Test
    void doesNotRecycleFinalOutputFailure() throws Exception {
        IOException failure = new IOException("injected final write failure");
        try (var pool = new ZipDeflateEncoderPool()) {
            var channel = pool.open(new FailingOutput(failure));
            channel.write(ByteBuffer.wrap(new byte[]{1, 2, 3}));
            assertSame(failure, assertThrows(IOException.class, channel::finish));
            assertFalse(channel.isOpen());
            assertNull(idle(pool));
            channel.close();
            try (var next = pool.open(new ByteArrayOutputStream())) { next.write(ByteBuffer.wrap(new byte[]{4})); }
            assertNotNull(idle(pool));
        }
    }

    /// Session closure releases an unfinished lease without emitting its final bytes.
    @Test
    void closingOwnerAbortsActiveLease() throws Exception {
        var pool = new ZipDeflateEncoderPool();
        var output = new ByteArrayOutputStream();
        var channel = pool.open(output);
        channel.write(ByteBuffer.wrap(new byte[]{1, 2, 3}));
        pool.close();
        assertEquals(0, output.size());
        assertNull(idle(pool));
        pool.close();
        assertThrows(IllegalStateException.class, () -> pool.open(output));
    }

    /// Reads the internal cache identity without exposing a public pooling API.
    private static @Nullable Object idle(ZipDeflateEncoderPool pool) throws ReflectiveOperationException {
        Field field = ZipDeflateEncoderPool.class.getDeclaredField("idle");
        field.setAccessible(true);
        return field.get(pool);
    }

    /// Records the array passed to stream writes to detect intermediate copies or per-entry allocations.
    @NotNullByDefault
    private static final class RecordingOutput extends ByteArrayOutputStream {
        /// The staging array used by every write to this entry.
        private byte @Nullable [] storage;

        /// Copies output while checking that the leased staging array remains stable.
        @Override
        public void write(byte[] bytes, int offset, int length) {
            if (storage != null) assertSame(storage, bytes);
            storage = bytes;
            super.write(bytes, offset, length);
        }
    }

    /// Fails every physical write with one stable exception object.
    @NotNullByDefault
    private static final class FailingOutput extends OutputStream {
        /// The injected transport failure.
        private final IOException failure;
        /// Retains the failure used by the assertion.
        private FailingOutput(IOException failure) { this.failure = failure; }
        /// Rejects a single-byte write.
        @Override
        public void write(int value) throws IOException { throw failure; }
        /// Rejects a bulk write without consuming bytes.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException { throw failure; }
    }
}
