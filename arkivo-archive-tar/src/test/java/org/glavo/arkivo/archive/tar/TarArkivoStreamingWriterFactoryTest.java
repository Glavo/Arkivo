// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoEditStorageFactory;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies ownership, validation, and cleanup across TAR streaming-writer factories.
@NotNullByDefault
final class TarArkivoStreamingWriterFactoryTest {
    /// Temporary directory used by path-backed writer factories.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies the stream, explicit-storage, and options overload owns both supplied resources after success.
    @Test
    void opensOutputStreamWithExplicitStorageAndOptions() throws IOException {
        TrackingOutputStream output = new TrackingOutputStream(null);
        TrackingEditStorage storage = new TrackingEditStorage();

        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(
                output,
                storage,
                TarArchiveOptions.CREATE_DEFAULTS
        )) {
            writeFile(writer, "value.txt", "stream");
        }

        assertFalse(output.isOpen());
        assertEquals(1, output.closeCalls());
        assertFalse(storage.isOpen());
        assertEquals(1, storage.closeCalls());
        assertArchive(output.bytes(), "value.txt", "stream");
    }

    /// Verifies path creation opens and owns storage selected by the creation options.
    @Test
    void createsPathWithConfiguredStorageFactory() throws IOException {
        Path path = temporaryDirectory.resolve("configured.tar");
        TrackingEditStorage storage = new TrackingEditStorage();
        TarArchiveOptions.Create options = TarArchiveOptions.CREATE_DEFAULTS.withCommon(
                ArchiveCreateOptions.DEFAULT.withEditStorageFactory(() -> storage)
        );

        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.create(path, options)) {
            writeFile(writer, "configured.txt", "configured");
        }

        assertFalse(storage.isOpen());
        assertEquals(1, storage.closeCalls());
        assertArchive(Files.readAllBytes(path), "configured.txt", "configured");
    }

    /// Verifies path creation accepts caller-provided staging storage and transfers ownership on success.
    @Test
    void createsPathWithExplicitStorage() throws IOException {
        Path path = temporaryDirectory.resolve("explicit.tar");
        TrackingEditStorage storage = new TrackingEditStorage();

        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.create(path, storage)) {
            writeFile(writer, "explicit.txt", "explicit");
        }

        assertFalse(storage.isOpen());
        assertEquals(1, storage.closeCalls());
        assertArchive(Files.readAllBytes(path), "explicit.txt", "explicit");
    }

    /// Verifies the fully configured path overload accepts explicit storage when options do not select another one.
    @Test
    void createsPathWithExplicitStorageAndOptions() throws IOException {
        Path path = temporaryDirectory.resolve("explicit-options.tar");
        TrackingEditStorage storage = new TrackingEditStorage();

        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.create(
                path,
                storage,
                TarArchiveOptions.CREATE_DEFAULTS
        )) {
            writeFile(writer, "options.txt", "options");
        }

        assertFalse(storage.isOpen());
        assertEquals(1, storage.closeCalls());
        assertArchive(Files.readAllBytes(path), "options.txt", "options");
    }

    /// Verifies storage-factory failure closes the validated output and retains cleanup failure details.
    @Test
    void closesOutputAfterStorageFactoryFailure() throws IOException {
        IOException setupFailure = new IOException("storage setup failed");
        IOException cleanupFailure = new IOException("output cleanup failed");
        TrackingOutputStream output = new TrackingOutputStream(cleanupFailure);
        TarArchiveOptions.Create options = optionsWithStorageFactory(() -> {
            throw setupFailure;
        });

        IOException thrown = assertThrows(
                IOException.class,
                () -> TarArkivoStreamingWriter.open(output, options)
        );

        assertSame(setupFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cleanupFailure, thrown.getSuppressed()[0]);
        assertFalse(output.isOpen());
        assertEquals(1, output.closeCalls());
    }

    /// Verifies storage setup failure still closes an output whose cleanup succeeds.
    @Test
    void closesOutputCleanlyAfterStorageFactoryFailure() {
        IOException setupFailure = new IOException("storage setup failed");
        TrackingOutputStream output = new TrackingOutputStream(null);
        TarArchiveOptions.Create options = optionsWithStorageFactory(() -> {
            throw setupFailure;
        });

        IOException thrown = assertThrows(
                IOException.class,
                () -> TarArkivoStreamingWriter.open(output, options)
        );

        assertSame(setupFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertFalse(output.isOpen());
        assertEquals(1, output.closeCalls());
    }

    /// Verifies one shared setup and cleanup failure is not suppressed onto itself.
    @Test
    void avoidsSelfSuppressionAfterStorageFactoryFailure() {
        IOException sharedFailure = new IOException("shared setup failure");
        TrackingOutputStream output = new TrackingOutputStream(sharedFailure);
        TarArchiveOptions.Create options = optionsWithStorageFactory(() -> {
            throw sharedFailure;
        });

        IOException thrown = assertThrows(
                IOException.class,
                () -> TarArkivoStreamingWriter.open(output, options)
        );

        assertSame(sharedFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertFalse(output.isOpen());
        assertEquals(1, output.closeCalls());
    }

    /// Verifies one shared trailer-write and target-close failure remains the finalization failure.
    @Test
    void avoidsSelfSuppressionDuringWriterFinalization() throws IOException {
        IOException sharedFailure = new IOException("shared output failure");
        SameFailureOutputStream output = new SameFailureOutputStream(sharedFailure);
        TrackingEditStorage storage = new TrackingEditStorage();
        TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(output, storage);

        IOException thrown = assertThrows(IOException.class, writer::close);

        assertSame(sharedFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(1, output.closeCalls());
        assertFalse(storage.isOpen());
    }

    /// Verifies failed target and storage cleanup is aggregated once and succeeds on a later close call.
    @Test
    void retriesIndependentWriterCleanupFailures() throws IOException {
        IOException outputFailure = new IOException("output close failed");
        IOException storageFailure = new IOException("storage close failed");
        RetryCloseOutputStream output = new RetryCloseOutputStream(outputFailure);
        TrackingEditStorage storage = new TrackingEditStorage(storageFailure, 1);
        TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(output, storage);

        IOException thrown = assertThrows(IOException.class, writer::close);

        assertSame(outputFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(storageFailure, thrown.getSuppressed()[0]);
        assertTrue(output.isOpen());
        assertTrue(storage.isOpen());
        assertEquals(1, output.closeCalls());
        assertEquals(1, storage.closeCalls());

        writer.close();
        writer.close();

        assertFalse(output.isOpen());
        assertFalse(storage.isOpen());
        assertEquals(2, output.closeCalls());
        assertEquals(2, storage.closeCalls());
        assertEquals(1024, output.bytes().length);
    }

    /// Verifies ambiguous storage selection fails before ownership of either explicit resource transfers.
    @Test
    void rejectsAmbiguousStorageWithoutTakingOwnership() throws IOException {
        TrackingOutputStream output = new TrackingOutputStream(null);
        TrackingEditStorage storage = new TrackingEditStorage();
        TarArchiveOptions.Create options = optionsWithStorageFactory(ArkivoEditStorageFactory.memory());
        Path path = temporaryDirectory.resolve("ambiguous.tar");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TarArkivoStreamingWriter.open(output, storage, options)
        );
        IllegalArgumentException pathFailure = assertThrows(
                IllegalArgumentException.class,
                () -> TarArkivoStreamingWriter.create(path, storage, options)
        );

        assertEquals("TAR body storage must be provided either as an argument or an option", failure.getMessage());
        assertEquals(failure.getMessage(), pathFailure.getMessage());
        assertFalse(Files.exists(path));
        assertTrue(output.isOpen());
        assertTrue(storage.isOpen());
        output.close();
        storage.close();
    }

    /// Returns creation options selecting the supplied operation-owned storage factory.
    private static TarArchiveOptions.Create optionsWithStorageFactory(ArkivoEditStorageFactory factory) {
        return TarArchiveOptions.CREATE_DEFAULTS.withCommon(
                ArchiveCreateOptions.DEFAULT.withEditStorageFactory(factory)
        );
    }

    /// Writes one UTF-8 regular file through a streaming writer.
    private static void writeFile(TarArkivoStreamingWriter writer, String path, String value) throws IOException {
        var entry = writer.beginFile(path);
        try (OutputStream body = entry.openOutputStream()) {
            body.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    /// Verifies a generated archive contains exactly one expected UTF-8 regular file.
    private static void assertArchive(byte[] archive, String path, String value) throws IOException {
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            assertTrue(reader.next());
            assertEquals(path, reader.readAttributes(TarArkivoEntryAttributes.class).path());
            try (var body = reader.openInputStream()) {
                assertArrayEquals(value.getBytes(StandardCharsets.UTF_8), body.readAllBytes());
            }
            assertFalse(reader.next());
        }
    }

    /// Wraps memory-backed edit storage while recording its ownership lifecycle.
    @NotNullByDefault
    private static final class TrackingEditStorage implements ArkivoEditStorage {
        /// The memory-backed storage implementation.
        private final ArkivoEditStorage delegate = ArkivoEditStorage.memory();

        /// Whether this storage remains open.
        private boolean open = true;

        /// Number of close calls received while open.
        private int closeCalls;

        /// Optional failure reported by the configured number of initial close calls.
        private final @Nullable IOException closeFailure;

        /// Number of remaining close calls that must fail.
        private int closeFailuresRemaining;

        /// Creates independently closeable storage.
        private TrackingEditStorage() {
            this(null, 0);
        }

        /// Creates storage with the requested deterministic close failures.
        private TrackingEditStorage(@Nullable IOException closeFailure, int closeFailuresRemaining) {
            this.closeFailure = closeFailure;
            this.closeFailuresRemaining = closeFailuresRemaining;
        }

        /// Creates delegated body storage while this wrapper is open.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) throws IOException {
            if (!open) {
                throw new IOException("storage closed");
            }
            return delegate.createContent(path, expectedSize);
        }

        /// Closes the delegated storage once.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            closeCalls++;
            IOException failure = closeFailure;
            if (failure != null && closeFailuresRemaining > 0) {
                closeFailuresRemaining--;
                throw failure;
            }
            delegate.close();
            open = false;
        }

        /// Returns whether this storage remains open.
        private boolean isOpen() {
            return open;
        }

        /// Returns the number of effective close calls.
        private int closeCalls() {
            return closeCalls;
        }
    }

    /// Captures output while failing the first close attempt without closing physically.
    @NotNullByDefault
    private static final class RetryCloseOutputStream extends OutputStream {
        /// Captured output bytes.
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();

        /// Failure reported by the first close call.
        private final IOException closeFailure;

        /// Whether this stream remains open.
        private boolean open = true;

        /// Number of close calls received while open.
        private int closeCalls;

        /// Creates an output stream with one scheduled close failure.
        private RetryCloseOutputStream(IOException closeFailure) {
            this.closeFailure = closeFailure;
        }

        /// Writes one byte while open.
        @Override
        public void write(int value) throws IOException {
            requireOpen();
            delegate.write(value);
        }

        /// Writes one byte range while open.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            requireOpen();
            delegate.write(bytes, offset, length);
        }

        /// Fails the first close attempt and closes on the second.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            closeCalls++;
            if (closeCalls == 1) {
                throw closeFailure;
            }
            open = false;
        }

        /// Returns a snapshot of captured output bytes.
        private byte[] bytes() {
            return delegate.toByteArray();
        }

        /// Returns whether this stream remains open.
        private boolean isOpen() {
            return open;
        }

        /// Returns the number of close calls received while open.
        private int closeCalls() {
            return closeCalls;
        }

        /// Requires this stream to remain open.
        private void requireOpen() throws IOException {
            if (!open) {
                throw new IOException("output closed");
            }
        }
    }

    /// Reports one shared checked failure from every output operation.
    @NotNullByDefault
    private static final class SameFailureOutputStream extends OutputStream {
        /// Failure shared by writes and close.
        private final IOException failure;

        /// Number of close calls.
        private int closeCalls;

        /// Creates an output stream reporting the supplied failure.
        private SameFailureOutputStream(IOException failure) {
            this.failure = failure;
        }

        /// Reports the shared failure.
        @Override
        public void write(int value) throws IOException {
            throw failure;
        }

        /// Reports the shared failure.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            throw failure;
        }

        /// Records the close attempt and reports the shared failure.
        @Override
        public void close() throws IOException {
            closeCalls++;
            throw failure;
        }

        /// Returns the number of close calls.
        private int closeCalls() {
            return closeCalls;
        }
    }

    /// Captures writer output and optionally reports a configured close failure after closing.
    @NotNullByDefault
    private static final class TrackingOutputStream extends OutputStream {
        /// Captured output bytes.
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();

        /// Optional failure reported after the first physical close.
        private final @Nullable IOException closeFailure;

        /// Whether this stream remains open.
        private boolean open = true;

        /// Number of close calls received while open.
        private int closeCalls;

        /// Creates a tracking stream with an optional close failure.
        private TrackingOutputStream(@Nullable IOException closeFailure) {
            this.closeFailure = closeFailure;
        }

        /// Writes one byte while open.
        @Override
        public void write(int value) throws IOException {
            requireOpen();
            delegate.write(value);
        }

        /// Writes one byte range while open.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            requireOpen();
            delegate.write(bytes, offset, length);
        }

        /// Closes this stream and reports the configured failure once.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            closeCalls++;
            open = false;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        /// Returns a snapshot of captured output bytes.
        private byte[] bytes() {
            return delegate.toByteArray();
        }

        /// Returns whether this stream remains open.
        private boolean isOpen() {
            return open;
        }

        /// Returns the number of effective close calls.
        private int closeCalls() {
            return closeCalls;
        }

        /// Requires this stream to remain open.
        private void requireOpen() throws IOException {
            if (!open) {
                throw new IOException("output closed");
            }
        }
    }
}
