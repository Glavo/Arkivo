// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies staged-body access, mutation failure isolation, and exactly-once completion.
@NotNullByDefault
final class StagedSeekableByteChannelTest {
    /// Verifies staged read/write access, append positioning, change tracking, and idempotent completion.
    @Test
    void managesStagedRandomAccessChannels() throws IOException {
        Path path = Files.createTempFile("arkivo-staged-channel", ".bin");
        try {
            Files.write(path, new byte[]{1, 2, 3});
            AtomicBoolean commit = new AtomicBoolean();
            AtomicInteger completions = new AtomicInteger();
            try (SeekableByteChannel storage = Files.newByteChannel(
                    path,
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
            )) {
                StagedSeekableByteChannel channel = new StagedSeekableByteChannel(
                        storage,
                        true,
                        true,
                        true,
                        false,
                        (completed, shouldCommit) -> {
                            assertFalse(completed.isOpen());
                            commit.set(shouldCommit);
                            completions.incrementAndGet();
                        }
                );
                assertEquals(3L, channel.size());
                assertEquals(3L, channel.position());
                channel.position(0L);
                assertEquals(1, channel.write(ByteBuffer.wrap(new byte[]{4})));
                assertEquals(4L, channel.position());
                assertEquals(4L, channel.size());
                channel.position(0L);
                ByteBuffer contents = ByteBuffer.allocate(4);
                assertEquals(4, channel.read(contents));
                assertArrayEquals(new byte[]{1, 2, 3, 4}, contents.array());
                channel.close();
                channel.close();
            }
            assertEquals(1, completions.get());
            assertTrue(commit.get());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies access rejection, write validation, truncation changes, and force-commit behavior.
    @Test
    void enforcesStagedChannelPolicies() throws IOException {
        Path path = Files.createTempFile("arkivo-staged-policy", ".bin");
        try {
            Files.write(path, new byte[]{1, 2, 3});
            AtomicBoolean truncatedCommit = new AtomicBoolean();
            StagedSeekableByteChannel truncated = new StagedSeekableByteChannel(
                    Files.newByteChannel(path, Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)),
                    false,
                    true,
                    false,
                    false,
                    (position, count) -> {
                        if (position + count > 3L) {
                            throw new IOException("staged body too large");
                        }
                    },
                    (channel, commit) -> truncatedCommit.set(commit)
            );
            assertThrows(NonReadableChannelException.class, () -> truncated.read(ByteBuffer.allocate(1)));
            truncated.position(3L);
            IOException limitFailure = assertThrows(
                    IOException.class,
                    () -> truncated.write(ByteBuffer.wrap(new byte[]{4}))
            );
            assertEquals("staged body too large", limitFailure.getMessage());
            truncated.truncate(2L);
            truncated.close();
            assertTrue(truncatedCommit.get());

            AtomicBoolean forcedCommit = new AtomicBoolean();
            StagedSeekableByteChannel forced = new StagedSeekableByteChannel(
                    Files.newByteChannel(path, Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)),
                    true,
                    true,
                    false,
                    true,
                    (channel, commit) -> forcedCommit.set(commit)
            );
            forced.close();
            assertTrue(forcedCommit.get());

            AtomicBoolean readOnlyCommit = new AtomicBoolean(true);
            StagedSeekableByteChannel readOnly = new StagedSeekableByteChannel(
                    Files.newByteChannel(path, StandardOpenOption.READ),
                    true,
                    false,
                    false,
                    true,
                    (channel, commit) -> readOnlyCommit.set(commit)
            );
            assertThrows(NonWritableChannelException.class, () -> readOnly.write(ByteBuffer.allocate(1)));
            assertThrows(NonWritableChannelException.class, () -> readOnly.truncate(0L));
            readOnly.close();
            assertFalse(readOnlyCommit.get());
            assertThrows(ClosedChannelException.class, readOnly::position);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies zero-byte writes and nonshrinking truncation do not mark staged content as changed.
    @Test
    void ignoresNoOpStagedMutations() throws IOException {
        Path path = Files.createTempFile("arkivo-staged-no-op", ".bin");
        try {
            Files.write(path, new byte[]{1, 2, 3});
            AtomicBoolean commit = new AtomicBoolean(true);
            StagedSeekableByteChannel channel = new StagedSeekableByteChannel(
                    Files.newByteChannel(path, Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)),
                    true,
                    true,
                    false,
                    false,
                    (completed, shouldCommit) -> commit.set(shouldCommit)
            );

            assertEquals(0, channel.write(ByteBuffer.allocate(0)));
            assertEquals(channel, channel.truncate(3L));
            assertEquals(channel, channel.truncate(4L));
            assertEquals(3L, channel.size());
            channel.close();

            assertFalse(commit.get());
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(path));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies that storage close failures prevent commits and retain completion failures as suppressed exceptions.
    @Test
    void preservesStagedChannelCloseFailures() throws IOException {
        Path path = Files.createTempFile("arkivo-staged-close", ".bin");
        try {
            AtomicBoolean commit = new AtomicBoolean(true);
            StagedSeekableByteChannel channel = new StagedSeekableByteChannel(
                    new CloseFailingChannel(Files.newByteChannel(
                            path,
                            Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
                    )),
                    true,
                    true,
                    false,
                    true,
                    (completed, shouldCommit) -> {
                        commit.set(shouldCommit);
                        throw new IOException("completion failed");
                    }
            );

            IOException exception = assertThrows(IOException.class, channel::close);
            assertEquals("storage close failed", exception.getMessage());
            assertEquals(1, exception.getSuppressed().length);
            assertEquals("completion failed", exception.getSuppressed()[0].getMessage());
            assertFalse(commit.get());
            channel.close();
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies completion failures preserve their unchecked type and identity after storage closes successfully.
    @Test
    void preservesUncheckedStagedCompletionFailures() throws IOException {
        assertUncheckedCompletionFailure(new IllegalStateException("runtime completion failure"));
        assertUncheckedCompletionFailure(new AssertionError("error completion failure"));
    }

    /// Verifies one unchecked completion failure is propagated without wrapping or suppression.
    private static <T extends Throwable> void assertUncheckedCompletionFailure(T failure) throws IOException {
        StagedSeekableByteChannel channel = new StagedSeekableByteChannel(
                new ReadOnlyByteArrayChannel(new byte[0]),
                true,
                false,
                false,
                false,
                (completed, commit) -> {
                    assertFalse(completed.isOpen());
                    assertFalse(commit);
                    if (failure instanceof RuntimeException exception) {
                        throw exception;
                    }
                    throw (Error) failure;
                }
        );

        Throwable thrown = assertThrows(failure.getClass(), channel::close);

        assertSame(failure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertFalse(channel.isOpen());
        channel.close();
    }

    /// Verifies storage failures latch before a changed or force-committed body can be published.
    @ParameterizedTest
    @MethodSource("mutationFailures")
    void discardsFailedMutations(Throwable failure, boolean truncate, boolean partial, boolean forceCommit,
            @TempDir Path directory) throws IOException {
        Path path = directory.resolve("body");
        Files.write(path, new byte[]{1, 2, 3});
        AtomicBoolean commit = new AtomicBoolean(true);
        AtomicInteger completions = new AtomicInteger();
        SeekableByteChannel delegate = Files.newByteChannel(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        StagedSeekableByteChannel channel = new StagedSeekableByteChannel(
                new MutationFailingChannel(delegate, failure, partial, null),
                true, true, false, forceCommit, (completed, shouldCommit) -> {
                    assertFalse(completed.isOpen());
                    completions.incrementAndGet();
                    commit.set(shouldCommit);
                });
        ByteBuffer source = ByteBuffer.wrap(new byte[]{9, 8});
        assertSame(failure, assertThrows(failure.getClass(), () -> {
            if (truncate) {
                channel.truncate(1L);
            } else {
                channel.write(source);
            }
        }));
        assertTrue(channel.isOpen());
        assertEquals(partial && !truncate ? 1 : 0, source.position());
        assertEquals(2, source.limit());
        assertSame(failure, assertThrows(IOException.class, () -> channel.write(source)).getCause());
        assertSame(failure, assertThrows(IOException.class, () -> channel.truncate(0L)).getCause());
        channel.position(0L);
        ByteBuffer contents = ByteBuffer.allocate(3);
        assertEquals(partial && truncate ? 1 : 3, channel.read(contents));
        assertEquals(partial && !truncate ? 9 : 1, contents.get(0));
        IOException closeFailure = assertThrows(IOException.class, channel::close);
        assertSame(failure, closeFailure.getCause());
        assertEquals(0, closeFailure.getSuppressed().length);
        assertFalse(commit.get());
        assertFalse(delegate.isOpen());
        channel.close();
        assertEquals(1, completions.get());
    }

    /// Verifies storage-close and discard failures do not replace the mutation that caused the discard.
    @ParameterizedTest
    @MethodSource("failureKinds")
    void retainsMutationAndCleanupFailures(Throwable failure, @TempDir Path directory) throws IOException {
        Throwable storageFailure = new IllegalStateException("storage close");
        Throwable discardFailure = new AssertionError("discard cleanup");
        SeekableByteChannel delegate = Files.newByteChannel(directory.resolve("body"),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        StagedSeekableByteChannel channel = new StagedSeekableByteChannel(
                new MutationFailingChannel(delegate, failure, true, storageFailure),
                false, true, false, true, (completed, commit) -> {
                    assertFalse(commit);
                    throwFailure(discardFailure);
                });
        assertSame(failure, assertThrows(failure.getClass(), () -> channel.write(ByteBuffer.wrap(new byte[]{1}))));
        IOException reported = assertThrows(IOException.class, channel::close);
        assertSame(failure, reported.getCause());
        assertArrayEquals(new Throwable[]{storageFailure, discardFailure}, reported.getSuppressed());
        assertFalse(delegate.isOpen());
        channel.close();
    }

    /// Verifies automatic resource closure never attempts to suppress the mutation failure onto itself.
    @ParameterizedTest
    @MethodSource("failureKinds")
    void supportsTryWithResourcesAfterMutationFailure(Throwable failure, @TempDir Path directory) throws IOException {
        SeekableByteChannel delegate = Files.newByteChannel(directory.resolve("body"),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        StagedSeekableByteChannel channel = new StagedSeekableByteChannel(
                new MutationFailingChannel(delegate, failure, true, null),
                false, true, false, true, (completed, commit) -> assertFalse(commit));
        Throwable reported = assertThrows(failure.getClass(), () -> {
            try (channel) {
                channel.write(ByteBuffer.wrap(new byte[]{1}));
            }
        });
        assertSame(failure, reported);
        assertEquals(1, reported.getSuppressed().length);
        assertInstanceOf(IOException.class, reported.getSuppressed()[0]);
        assertSame(failure, reported.getSuppressed()[0].getCause());
        assertFalse(delegate.isOpen());
    }

    /// Verifies rejected arguments and pre-write checks leave successful staged changes eligible for commit.
    @ParameterizedTest
    @MethodSource("failureKinds")
    void preservesBodyAfterValidationFailure(Throwable failure, @TempDir Path directory) throws IOException {
        AtomicBoolean rejectWrite = new AtomicBoolean();
        AtomicBoolean commit = new AtomicBoolean();
        Path path = directory.resolve("body");
        StagedSeekableByteChannel channel = new StagedSeekableByteChannel(
                Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                false, true, false, false,
                (position, count) -> {
                    if (rejectWrite.getAndSet(false)) {
                        throwFailure(failure);
                    }
                }, (completed, shouldCommit) -> commit.set(shouldCommit));
        assertEquals(1, channel.write(ByteBuffer.wrap(new byte[]{1})));
        assertThrows(IllegalArgumentException.class, () -> channel.truncate(-1L));
        assertThrows(IllegalArgumentException.class, () -> channel.position(-1L));
        assertThrows(NullPointerException.class, () -> channel.write(null));
        rejectWrite.set(true);
        ByteBuffer source = ByteBuffer.wrap(new byte[]{2});
        assertSame(failure, assertThrows(failure.getClass(), () -> channel.write(source)));
        assertEquals(0, source.position());
        assertEquals(1, channel.write(source));
        channel.close();
        assertTrue(commit.get());
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(path));
    }

    /// Supplies fresh failures for checked, unchecked, and error paths.
    private static Stream<Throwable> failureKinds() {
        return Stream.of(new IOException("mutation"), new IllegalStateException("mutation"), new AssertionError("mutation"));
    }

    /// Supplies mutation failures with and without partial progress or a preexisting commit requirement.
    private static Stream<Arguments> mutationFailures() {
        return Stream.of(false, true).flatMap(truncate -> Stream.of(false, true)
                .flatMap(partial -> Stream.of(false, true)
                        .flatMap(forceCommit -> failureKinds()
                                .map(failure -> Arguments.of(failure, truncate, partial, forceCommit)))));
    }

    /// Throws a configured fault without changing its category or identity.
    private static void throwFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        throw (Error) failure;
    }

    /// Injects mutation failures before or after partial progress and an independent close failure.
    ///
    /// @param delegate the owned staging channel
    /// @param failure the mutation failure
    /// @param partial whether to change storage before throwing
    /// @param closeFailure a failure reported after closing storage, or `null` for successful close
    @NotNullByDefault
    private record MutationFailingChannel(
            SeekableByteChannel delegate, Throwable failure, boolean partial, @Nullable Throwable closeFailure
    ) implements SeekableByteChannel {
        /// Reads staged bytes for inspection after a failed mutation.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            return delegate.read(destination);
        }

        /// Writes at most one offered byte before reporting the mutation failure.
        @Override
        public int write(ByteBuffer source) throws IOException {
            if (partial && source.hasRemaining()) {
                int limit = source.limit();
                source.limit(source.position() + 1);
                try {
                    delegate.write(source);
                } finally {
                    source.limit(limit);
                }
            }
            throwFailure(failure);
            throw new AssertionError("Unreachable");
        }

        /// Returns the staging position.
        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        /// Changes the staging position without injecting a fault.
        @Override
        public SeekableByteChannel position(long position) throws IOException {
            delegate.position(position);
            return this;
        }

        /// Returns the stored size.
        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        /// Optionally truncates storage before reporting the mutation failure.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            if (partial) {
                delegate.truncate(size);
            }
            throwFailure(failure);
            throw new AssertionError("Unreachable");
        }

        /// Returns whether the backing channel is open.
        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /// Closes storage before reporting any separately configured cleanup failure.
        @Override
        public void close() throws IOException {
            delegate.close();
            if (closeFailure != null) {
                throwFailure(closeFailure);
            }
        }
    }

    /// Delegates seekable operations and reports a deterministic failure after closing its storage.
    ///
    /// @param channel the wrapped storage channel
    @NotNullByDefault
    private record CloseFailingChannel(SeekableByteChannel channel) implements SeekableByteChannel {
        /// Reads from wrapped storage.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            return channel.read(destination);
        }

        /// Writes to wrapped storage.
        @Override
        public int write(ByteBuffer source) throws IOException {
            return channel.write(source);
        }

        /// Returns the wrapped position.
        @Override
        public long position() throws IOException {
            return channel.position();
        }

        /// Changes the wrapped position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            channel.position(newPosition);
            return this;
        }

        /// Returns the wrapped size.
        @Override
        public long size() throws IOException {
            return channel.size();
        }

        /// Truncates wrapped storage.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            channel.truncate(size);
            return this;
        }

        /// Returns whether wrapped storage remains open.
        @Override
        public boolean isOpen() {
            return channel.isOpen();
        }

        /// Closes wrapped storage and reports the configured failure.
        @Override
        public void close() throws IOException {
            channel.close();
            throw new IOException("storage close failed");
        }
    }

}
