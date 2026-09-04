// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies CPIO path factories and staged-body resource cleanup across retryable failures.
@NotNullByDefault
final class CPIOStorageLifecycleTest {
    /// Temporary directory used for path-backed CPIO archives.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies the CPIO-specific pending-entry view exposes its stable NIO attribute-view name.
    @Test
    void exposesStableEntryAttributeViewName() throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(archive)) {
            var entry = writer.beginFile("empty.txt");
            CPIOArkivoEntryAttributeView view = Objects.requireNonNull(
                    entry.attributeView(CPIOArkivoEntryAttributeView.class)
            );
            assertEquals("cpio", view.name());
            entry.close();
        }
    }

    /// Verifies default and configured path factories publish readable archives and own selected storage.
    @Test
    void createsPathBackedArchivesAndOwnsStorage() throws IOException {
        Path defaultArchive = temporaryDirectory.resolve("default.cpio");
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.create(defaultArchive)) {
            writer.beginFile("empty.txt").close();
        }
        assertArrayEquals(new byte[0], readOnlyBody(defaultArchive));

        byte[] expected = "staged body".getBytes(StandardCharsets.UTF_8);
        Path configuredArchive = temporaryDirectory.resolve("configured.cpio");
        TrackingEditStorage storage = new TrackingEditStorage(0, 0);
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.create(
                configuredArchive,
                options(storage)
        )) {
            try (OutputStream body = writer.beginFile("value.txt").openOutputStream()) {
                body.write(expected);
            }
        }

        assertArrayEquals(expected, readOnlyBody(configuredArchive));
        assertEquals(1, storage.createdContentCount());
        assertEquals(1, storage.contentCloseCount());
        assertEquals(1, storage.closeCount());
    }

    /// Verifies path setup closes its opened target while endpoint overloads retain caller ownership on failure.
    @Test
    void preservesSetupFailureIdentityAndOwnership() throws IOException {
        IOException pathFailure = new IOException("path storage setup failure");
        CPIOArchiveOptions.Create pathOptions = options(() -> {
            throw pathFailure;
        });
        Path path = temporaryDirectory.resolve("failed.cpio");
        Files.write(path, new byte[]{1, 2, 3});

        assertSame(
                pathFailure,
                assertThrows(IOException.class, () -> CPIOArkivoStreamingWriter.create(path, pathOptions))
        );
        assertEquals(0L, Files.size(path));
        Files.delete(path);
        assertFalse(Files.exists(path));

        IOException endpointFailure = new IOException("endpoint storage setup failure");
        CPIOArchiveOptions.Create endpointOptions = options(() -> {
            throw endpointFailure;
        });
        RetryCloseOutputStream target = new RetryCloseOutputStream(0);
        assertSame(
                endpointFailure,
                assertThrows(IOException.class, () -> CPIOArkivoStreamingWriter.open(target, endpointOptions))
        );
        assertTrue(target.isOpen());
        assertEquals(0, target.closeCount());
        target.close();
        assertFalse(target.isOpen());
    }

    /// Verifies target, retained-content, and storage failures are aggregated and all cleanup can be retried.
    @Test
    void retriesAndAggregatesIndependentCleanupFailures() throws IOException {
        byte[] expected = "retryable cleanup".getBytes(StandardCharsets.UTF_8);
        TrackingEditStorage storage = new TrackingEditStorage(2, 1);
        RetryCloseOutputStream target = new RetryCloseOutputStream(1);
        CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(target, options(storage));

        try (OutputStream body = writer.beginFile("value.txt").openOutputStream()) {
            body.write(expected);
        }
        assertEquals(1, storage.contentCloseCount());

        IOException failure = assertThrows(IOException.class, writer::close);
        assertEquals("target close failure", failure.getMessage());
        assertEquals(2, failure.getSuppressed().length);
        assertEquals("content close failure", failure.getSuppressed()[0].getMessage());
        assertEquals("storage close failure", failure.getSuppressed()[1].getMessage());
        assertTrue(target.isOpen());
        assertEquals(1, target.closeCount());
        assertEquals(2, storage.contentCloseCount());
        assertEquals(1, storage.closeCount());

        assertDoesNotThrow(writer::close);
        assertDoesNotThrow(writer::close);
        assertFalse(target.isOpen());
        assertEquals(2, target.closeCount());
        assertEquals(3, storage.contentCloseCount());
        assertEquals(2, storage.closeCount());
        assertArrayEquals(expected, readOnlyBody(target.toByteArray()));
    }

    /// Verifies failed body-channel setup retains staged content for close-time cleanup and leaves the entry pending.
    @Test
    void cleansUpAfterBodyChannelSetupFailure() throws IOException {
        IOException setupFailure = new IOException("content channel setup failure");
        TrackingEditStorage storage = new TrackingEditStorage(1, 0, setupFailure);
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(target, options(storage));

        var entry = writer.beginFile("value.txt");
        assertSame(setupFailure, assertThrows(IOException.class, entry::openOutputStream));
        assertEquals(1, storage.createdContentCount());
        assertEquals(1, storage.contentCloseCount());

        writer.close();
        assertEquals(2, storage.contentCloseCount());
        assertEquals(1, storage.closeCount());
        assertArrayEquals(new byte[0], readOnlyBody(target.toByteArray()));
    }

    /// Verifies a single-byte archive write failure is latched without self-suppression and cleanup remains retryable.
    @Test
    void latchesEntryOutputFailureAndRetriesSharedCloseFailure() throws IOException {
        IOException sharedFailure = new IOException("shared archive output failure");
        ScriptedArchiveOutputStream target = ScriptedArchiveOutputStream.failSingleByte(
                sharedFailure,
                true
        );
        CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(
                target,
                CPIOArchiveOptions.CREATE_DEFAULTS.withBlockSize(1)
        );

        var entry = writer.beginFile("value.txt");
        assertSame(sharedFailure, assertThrows(IOException.class, entry::close));
        assertSame(sharedFailure, assertThrows(IOException.class, writer::close));
        assertEquals(0, sharedFailure.getSuppressed().length);
        assertTrue(target.isOpen());

        assertSame(sharedFailure, assertThrows(IOException.class, writer::close));
        assertFalse(target.isOpen());
        assertDoesNotThrow(writer::close);
    }

    /// Verifies a trailer-name write failure is preserved while all owned resources are still released.
    @Test
    void releasesResourcesAfterTrailerOutputFailure() throws IOException {
        IOException outputFailure = new IOException("trailer output failure");
        ScriptedArchiveOutputStream target = ScriptedArchiveOutputStream.failTrailerName(outputFailure);
        CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(
                target,
                CPIOArchiveOptions.CREATE_DEFAULTS.withBlockSize(1)
        );
        writer.beginFile("value.txt").close();

        assertSame(outputFailure, assertThrows(IOException.class, writer::close));
        assertFalse(target.isOpen());
        assertDoesNotThrow(writer::close);
    }

    /// Returns creation options that use one supplied edit-storage instance.
    private static CPIOArchiveOptions.Create options(ArkivoEditStorage storage) {
        Objects.requireNonNull(storage, "storage");
        return options(() -> storage);
    }

    /// Returns creation options that use one supplied edit-storage factory.
    private static CPIOArchiveOptions.Create options(
            org.glavo.arkivo.archive.ArkivoEditStorageFactory storageFactory
    ) {
        return CPIOArchiveOptions.CREATE_DEFAULTS
                .withCommon(ArchiveCreateOptions.DEFAULT.withEditStorageFactory(storageFactory))
                .withBlockSize(1);
    }

    /// Reads the body of the only logical entry in one path-backed archive.
    private static byte[] readOnlyBody(Path archive) throws IOException {
        return readOnlyBody(Files.readAllBytes(archive));
    }

    /// Reads the body of the only logical entry in one in-memory archive.
    private static byte[] readOnlyBody(byte[] archive) throws IOException {
        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            assertTrue(reader.next());
            byte[] body;
            try (InputStream input = reader.openInputStream()) {
                body = input.readAllBytes();
            }
            assertFalse(reader.next());
            return body;
        }
    }

    /// Provides edit storage with independently configurable content and storage close failures.
    @NotNullByDefault
    private static final class TrackingEditStorage implements ArkivoEditStorage {
        /// Memory-backed storage delegated to after injected failures are exhausted.
        private final ArkivoEditStorage delegate = ArkivoEditStorage.memory();

        /// Remaining failures shared by stored-content close operations.
        private int contentCloseFailuresRemaining;

        /// Remaining storage close failures.
        private int storageCloseFailuresRemaining;

        /// Failure injected into the next stored-content channel open, or `null` when opens delegate normally.
        private @Nullable IOException channelOpenFailure;

        /// Number of stored content objects created.
        private int createdContentCount;

        /// Number of stored-content close calls received.
        private int contentCloseCount;

        /// Number of storage close calls received.
        private int closeCount;

        /// Creates tracking storage with the requested failure counts.
        private TrackingEditStorage(int contentCloseFailures, int storageCloseFailures) {
            this(contentCloseFailures, storageCloseFailures, null);
        }

        /// Creates tracking storage with requested cleanup failures and an optional channel-open failure.
        private TrackingEditStorage(
                int contentCloseFailures,
                int storageCloseFailures,
                @Nullable IOException channelOpenFailure
        ) {
            if (contentCloseFailures < 0 || storageCloseFailures < 0) {
                throw new IllegalArgumentException("close failure counts must not be negative");
            }
            this.contentCloseFailuresRemaining = contentCloseFailures;
            this.storageCloseFailuresRemaining = storageCloseFailures;
            this.channelOpenFailure = channelOpenFailure;
        }

        /// Creates one tracked stored-content object.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) throws IOException {
            createdContentCount++;
            return new TrackingStoredContent(delegate.createContent(path, expectedSize));
        }

        /// Fails while configured, then closes the delegated storage.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (storageCloseFailuresRemaining > 0) {
                storageCloseFailuresRemaining--;
                throw new IOException("storage close failure");
            }
            delegate.close();
        }

        /// Returns the number of stored-content objects created.
        private int createdContentCount() {
            return createdContentCount;
        }

        /// Returns the number of stored-content close calls received.
        private int contentCloseCount() {
            return contentCloseCount;
        }

        /// Returns the number of storage close calls received.
        private int closeCount() {
            return closeCount;
        }

        /// Tracks one delegated stored-content object.
        @NotNullByDefault
        private final class TrackingStoredContent implements ArkivoStoredContent {
            /// Delegated stored content.
            private final ArkivoStoredContent content;

            /// Creates tracked stored content around the supplied delegate.
            private TrackingStoredContent(ArkivoStoredContent content) {
                this.content = Objects.requireNonNull(content, "content");
            }

            /// Opens a channel over the delegated content.
            @Override
            public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
                IOException failure = channelOpenFailure;
                if (failure != null) {
                    channelOpenFailure = null;
                    throw failure;
                }
                return content.openChannel(options);
            }

            /// Returns the delegated content size.
            @Override
            public long size() throws IOException {
                return content.size();
            }

            /// Fails while configured, then closes the delegated content.
            @Override
            public void close() throws IOException {
                contentCloseCount++;
                if (contentCloseFailuresRemaining > 0) {
                    contentCloseFailuresRemaining--;
                    throw new IOException("content close failure");
                }
                content.close();
            }
        }
    }

    /// Provides an in-memory output stream with retryable close failures.
    @NotNullByDefault
    private static final class RetryCloseOutputStream extends ByteArrayOutputStream {
        /// Remaining close failures.
        private int closeFailuresRemaining;

        /// Number of close calls received.
        private int closeCount;

        /// Whether this stream remains open.
        private boolean open = true;

        /// Creates an output stream with the requested close-failure count.
        private RetryCloseOutputStream(int closeFailures) {
            if (closeFailures < 0) {
                throw new IllegalArgumentException("closeFailures must not be negative");
            }
            this.closeFailuresRemaining = closeFailures;
        }

        /// Fails while configured, then marks this stream closed.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeFailuresRemaining > 0) {
                closeFailuresRemaining--;
                throw new IOException("target close failure");
            }
            open = false;
            super.close();
        }

        /// Returns whether this stream remains open.
        private boolean isOpen() {
            return open;
        }

        /// Returns the number of close calls received.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Provides an archive target that fails at one selected output phase.
    @NotNullByDefault
    private static final class ScriptedArchiveOutputStream extends OutputStream {
        /// Bytes identifying the CPIO trailer name.
        private static final byte @Unmodifiable [] TRAILER_NAME =
                "TRAILER!!!".getBytes(StandardCharsets.US_ASCII);

        /// Successfully accepted bytes.
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();

        /// Failure reported at the selected phase.
        private final IOException failure;

        /// Whether the first single-byte write should fail.
        private final boolean failSingleByte;

        /// Whether the first trailer-name bulk write should fail.
        private final boolean failTrailerName;

        /// Whether the first close should report the output failure too.
        private final boolean failFirstCloseWithSharedFailure;

        /// Whether the selected write failure has occurred.
        private boolean writeFailed;

        /// Whether the first close failure has occurred.
        private boolean closeFailed;

        /// Whether this target remains open.
        private boolean open = true;

        /// Creates a scripted archive target.
        private ScriptedArchiveOutputStream(
                IOException failure,
                boolean failSingleByte,
                boolean failTrailerName,
                boolean failFirstCloseWithSharedFailure
        ) {
            this.failure = Objects.requireNonNull(failure, "failure");
            this.failSingleByte = failSingleByte;
            this.failTrailerName = failTrailerName;
            this.failFirstCloseWithSharedFailure = failFirstCloseWithSharedFailure;
        }

        /// Creates a target that fails its first single-byte write.
        private static ScriptedArchiveOutputStream failSingleByte(IOException failure, boolean failFirstClose) {
            return new ScriptedArchiveOutputStream(failure, true, false, failFirstClose);
        }

        /// Creates a target that fails while writing the trailer name.
        private static ScriptedArchiveOutputStream failTrailerName(IOException failure) {
            return new ScriptedArchiveOutputStream(failure, false, true, false);
        }

        /// Writes one byte or reports the selected failure.
        @Override
        public void write(int value) throws IOException {
            requireOpen();
            if (failSingleByte && !writeFailed) {
                writeFailed = true;
                throw failure;
            }
            delegate.write(value);
        }

        /// Writes one byte range or reports the selected trailer failure.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            requireOpen();
            if (failTrailerName && !writeFailed && equalsRange(bytes, offset, length, TRAILER_NAME)) {
                writeFailed = true;
                throw failure;
            }
            delegate.write(bytes, offset, length);
        }

        /// Closes this target or reports the configured shared failure once.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            if (failFirstCloseWithSharedFailure && !closeFailed) {
                closeFailed = true;
                throw failure;
            }
            open = false;
            delegate.close();
        }

        /// Returns whether this target remains open.
        private boolean isOpen() {
            return open;
        }

        /// Requires this target to remain open.
        private void requireOpen() throws IOException {
            if (!open) {
                throw new IOException("scripted archive target is closed");
            }
        }

        /// Returns whether one range equals an expected byte sequence.
        private static boolean equalsRange(byte[] bytes, int offset, int length, byte[] expected) {
            if (length != expected.length) {
                return false;
            }
            for (int index = 0; index < length; index++) {
                if (bytes[offset + index] != expected[index]) {
                    return false;
                }
            }
            return true;
        }
    }
}
