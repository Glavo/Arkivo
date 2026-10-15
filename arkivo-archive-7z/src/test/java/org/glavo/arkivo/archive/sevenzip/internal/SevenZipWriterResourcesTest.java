// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/// Checks cancellation ownership independently of coder and buffer finalization.
@NotNullByDefault
final class SevenZipWriterResourcesTest {
    /// Failed physical closes remain registered while healthy resources close only once.
    @Test
    void retriesFailedCloseWithoutRetainingHealthyChannels() throws IOException {
        var resources = new SevenZipWriterResources();
        var failed = new FaultChannel();
        var healthy = new FaultChannel();
        var failedView = resources.own(failed);
        var healthyView = resources.own(healthy);
        failed.closeFailure = new IOException("Physical close failed");
        assertSame(failed.closeFailure, resources.cancel());
        assertFalse(failedView.isOpen());
        assertFalse(healthyView.isOpen());
        assertThrows(IOException.class, () -> failedView.write(ByteBuffer.wrap(new byte[]{1})));
        assertSame(failed.closeFailure, assertThrows(IOException.class, resources::close));
        failed.closeFailure = null;
        resources.close();
        resources.close();
        assertEquals(3, failed.closeCalls);
        assertEquals(1, healthy.closeCalls);
        assertFalse(failed.delegate.isOpen());
    }

    /// Channels opened after cancellation are immediately closed and failed closure remains retryable.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsLateRegistration(boolean failClose) throws IOException {
        var resources = new SevenZipWriterResources();
        assertNull(resources.cancel());
        var late = new FaultChannel();
        if (failClose) late.closeFailure = new IOException("Late close failed");
        IOException failure = assertThrows(IOException.class, () -> resources.own(late));
        assertEquals(failClose ? 1 : 0, failure.getSuppressed().length);
        if (failClose) assertSame(late.closeFailure, failure.getSuppressed()[0]);
        assertEquals(1, late.closeCalls);
        late.closeFailure = null;
        resources.close();
        resources.close();
        assertEquals(failClose ? 2 : 1, late.closeCalls);
        assertFalse(late.delegate.isOpen());
    }

    /// Cancellation leaves temporary-path deletion to coordinated close, even if folder construction fails.
    @Test
    void retainsTemporaryFileBeforeChannelRegistration() throws IOException {
        try (var resources = new SevenZipWriterResources()) {
            Path path = resources.createTemporaryFile("arkivo-7z-owner-test-");
            assertNull(resources.cancel());
            assertTrue(Files.exists(path));
            var channel = Files.newByteChannel(path);
            assertThrows(IOException.class, () -> resources.own(channel));
            assertFalse(channel.isOpen());
            assertTrue(Files.exists(path));
            resources.close();
            assertFalse(Files.exists(path));
            assertThrows(IOException.class, () -> resources.createTemporaryFile("arkivo-7z-owner-test-"));
        }
    }

    /// Failed path deletion is retained until a later close can complete it.
    @Test
    void retriesTemporaryDeletion() throws IOException {
        var resources = new SevenZipWriterResources();
        Path path = resources.createTemporaryFile("arkivo-7z-owner-test-");
        Files.delete(path);
        Files.createDirectory(path);
        Path child = Files.createFile(path.resolve("held"));
        try {
            assertThrows(IOException.class, () -> resources.deleteTemporaryFile(path));
            assertThrows(IOException.class, resources::close);
            assertTrue(Files.exists(child));
            Files.delete(child);
            resources.close();
            assertFalse(Files.exists(path));
            resources.close();
        } finally {
            Files.deleteIfExists(child);
            resources.close();
        }
    }

    /// Successful deletion releases ownership so later cleanup cannot delete a replacement path.
    @Test
    void forgetsSuccessfullyDeletedPaths() throws IOException {
        var resources = new SevenZipWriterResources();
        Path path = resources.createTemporaryFile("arkivo-7z-owner-test-");
        resources.deleteTemporaryFile(path);
        Files.createFile(path);
        try {
            resources.deleteTemporaryFile(path);
            resources.close();
            assertTrue(Files.exists(path));
        } finally {
            Files.delete(path);
        }
    }

    /// Supplies retryable physical close failure without changing the owner wrapper's state.
    @NotNullByDefault
    private static final class FaultChannel implements SeekableByteChannel {
        /// The physical in-memory destination.
        private final SeekableInMemoryByteChannel delegate = new SeekableInMemoryByteChannel();
        /// An optional persistent cleanup failure.
        private @Nullable IOException closeFailure;
        /// Number of physical close attempts.
        private int closeCalls;

        @Override
        public int read(ByteBuffer target) throws IOException { return delegate.read(target); }

        @Override
        public int write(ByteBuffer source) throws IOException { return delegate.write(source); }

        @Override
        public long position() throws IOException { return delegate.position(); }

        @Override
        public SeekableByteChannel position(long position) throws IOException {
            delegate.position(position);
            return this;
        }

        @Override
        public long size() throws IOException { return delegate.size(); }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        @Override
        public boolean isOpen() { return delegate.isOpen(); }

        @Override
        public void close() throws IOException {
            closeCalls++;
            if (closeFailure != null) throw closeFailure;
            delegate.close();
        }
    }
}
