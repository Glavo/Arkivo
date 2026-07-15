// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.OpenOption;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies shared indexed-content transfer and lifecycle behavior.
@NotNullByDefault
final class StoredContentSupportTest {
    /// Verifies configured storage selection, successful input storage, copying, and empty-body adapters.
    @Test
    void storesAndCopiesIndexedContent() throws IOException {
        ArkivoEditStorage storage = ArkivoEditStorage.memory();
        assertSame(storage, StoredContentSupport.selectStorage(Map.of(
                ArkivoFileSystem.EDIT_STORAGE.key(),
                storage
        )));

        Set<ArkivoStoredContent> ownedContents = StoredContentSupport.newIdentitySet();
        ArkivoStoredContent source = StoredContentSupport.storeInput(
                storage,
                ownedContents,
                "source",
                4L,
                new ByteArrayInputStream(new byte[]{1, 2, 3, 4})
        );
        assertTrue(ownedContents.contains(source));
        try (InputStream input = StoredContentSupport.openInputStream(source)) {
            assertArrayEquals(new byte[]{1, 2, 3, 4}, input.readAllBytes());
        }

        ArkivoStoredContent destination = storage.createContent("destination", 0L);
        ownedContents.add(destination);
        StoredContentSupport.copyContent(source, destination);
        try (SeekableByteChannel input = StoredContentSupport.openReadChannel(destination)) {
            ByteBuffer bytes = ByteBuffer.allocate(4);
            assertEquals(4, input.read(bytes));
            assertArrayEquals(new byte[]{1, 2, 3, 4}, bytes.array());
        }

        try (InputStream empty = StoredContentSupport.openInputStream(null)) {
            assertEquals(0, empty.readAllBytes().length);
        }
        try (SeekableByteChannel empty = StoredContentSupport.openReadChannel(null)) {
            assertEquals(0L, empty.size());
            assertEquals(-1, empty.read(ByteBuffer.allocate(1)));
        }

        StoredContentSupport.closeAfterOpenFailure(storage, ownedContents, new IOException("open failed"));
        assertThrows(IOException.class, source::size);
        assertThrows(IOException.class, destination::size);
    }

    /// Verifies failed stores retain content whose first cleanup fails and suppress all cleanup failures.
    @Test
    void retainsFailedCleanupForRetry() throws IOException {
        RetryCloseStorage storage = new RetryCloseStorage(true);
        Set<ArkivoStoredContent> ownedContents = StoredContentSupport.newIdentitySet();

        IOException storeFailure = assertThrows(IOException.class, () -> StoredContentSupport.storeInput(
                storage,
                ownedContents,
                "failed",
                ArkivoEditStorage.UNKNOWN_SIZE,
                new FailingInputStream()
        ));
        assertEquals("input failed", storeFailure.getMessage());
        assertEquals(1, storeFailure.getSuppressed().length);
        assertEquals("content close failed", storeFailure.getSuppressed()[0].getMessage());
        assertTrue(ownedContents.contains(storage.content));

        IOException openFailure = new IOException("open failed");
        StoredContentSupport.closeAfterOpenFailure(storage, ownedContents, openFailure);
        assertEquals(1, openFailure.getSuppressed().length);
        assertEquals("storage close failed", openFailure.getSuppressed()[0].getMessage());
        assertEquals(2, storage.content.closeAttempts);
        assertTrue(storage.closed);
        assertFalse(storage.content.delegateOpen());
    }

    /// Verifies nonempty transfers reject writable channels that make no progress.
    @Test
    void rejectsZeroProgressOutput() {
        ZeroProgressChannel output = new ZeroProgressChannel();
        IOException exception = assertThrows(IOException.class, () -> StoredContentSupport.copyInput(
                new ByteArrayInputStream(new byte[]{1}),
                output
        ));
        assertEquals("Stored content channel made no write progress", exception.getMessage());
        output.close();
        assertFalse(output.isOpen());
    }

    /// Produces a deterministic checked read failure.
    @NotNullByDefault
    private static final class FailingInputStream extends InputStream {
        /// Creates a failing input stream.
        private FailingInputStream() {
        }

        /// Reports the configured read failure.
        @Override
        public int read() throws IOException {
            throw new IOException("input failed");
        }
    }

    /// Implements a writable channel that never consumes a nonempty source buffer.
    @NotNullByDefault
    private static final class ZeroProgressChannel implements WritableByteChannel {
        /// Whether this channel remains open.
        private boolean open = true;

        /// Creates a zero-progress channel.
        private ZeroProgressChannel() {
        }

        /// Reports no progress without consuming source bytes.
        @Override
        public int write(ByteBuffer source) {
            return 0;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }
    }

    /// Provides one stored-content handle whose first close fails.
    @NotNullByDefault
    private static final class RetryCloseStorage implements ArkivoEditStorage {
        /// The content returned by this storage.
        private final RetryCloseContent content;

        /// Whether storage close should fail.
        private final boolean failClose;

        /// Whether this storage has been closed.
        private boolean closed;

        /// Creates retry-close storage.
        private RetryCloseStorage(boolean failClose) throws IOException {
            this.failClose = failClose;
            this.content = new RetryCloseContent();
        }

        /// Returns the shared test content.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) {
            return content;
        }

        /// Closes this storage and optionally reports a failure.
        @Override
        public void close() throws IOException {
            closed = true;
            if (failClose) {
                throw new IOException("storage close failed");
            }
        }
    }

    /// Wraps memory content and fails its first close attempt.
    @NotNullByDefault
    private static final class RetryCloseContent implements ArkivoStoredContent {
        /// The wrapped memory content.
        private final ArkivoStoredContent delegate;

        /// The number of close attempts.
        private int closeAttempts;

        /// Creates retry-close content.
        @SuppressWarnings("resource")
        private RetryCloseContent() throws IOException {
            this.delegate = ArkivoEditStorage.memory().createContent("test", 0L);
        }

        /// Opens a channel over wrapped content.
        @Override
        public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
            return delegate.openChannel(options);
        }

        /// Returns the wrapped content size.
        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        /// Fails the first close attempt and closes wrapped content thereafter.
        @Override
        public void close() throws IOException {
            closeAttempts++;
            if (closeAttempts == 1) {
                throw new IOException("content close failed");
            }
            delegate.close();
        }

        /// Returns whether wrapped content remains open.
        private boolean delegateOpen() {
            try {
                delegate.size();
                return true;
            } catch (IOException exception) {
                return false;
            }
        }
    }
}
