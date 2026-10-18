// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoCommitOutput;
import org.glavo.arkivo.archive.ArkivoEditStorageFactory;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.glavo.arkivo.internal.StreamChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;

/// Provides shared transfer and lifecycle operations for indexed archive content.
@NotNullByDefault
public final class StoredContentSupport {
    /// The bounded transfer buffer size.
    private static final int TRANSFER_BUFFER_SIZE = 64 * 1024;

    /// Creates stored-content support operations.
    private StoredContentSupport() {
    }

    /// Returns configured edit storage or temporary-file storage in the default system temporary directory.
    ///
    /// @param configured the storage factory, or `null` to use temporary files
    /// @return new operation-owned configured storage, or new default temporary-file storage
    /// @throws IOException if configured storage cannot be opened
    public static ArkivoEditStorage selectStorage(@Nullable ArkivoEditStorageFactory configured) throws IOException {
        return configured != null
                ? configured.open()
                : ArkivoEditStorage.temporaryFiles(defaultStorageDirectory());
    }

    /// Closes storage allocated during a failed open and suppresses its cleanup failure.
    ///
    /// @param editStorage the storage whose owned resources are released
    /// @param failure the primary failure that receives any cleanup failure
    public static void closeAfterOpenFailure(ArkivoEditStorage editStorage, Throwable failure) {
        Objects.requireNonNull(editStorage, "editStorage");
        Objects.requireNonNull(failure, "failure");
        try {
            editStorage.close();
        } catch (IOException | RuntimeException | Error cleanupFailure) {
            addSuppressed(failure, cleanupFailure);
        }
    }

    /// Stores one input body in pool-owned content.
    ///
    /// Failed transfers request content release; the pool retains any incomplete cleanup for a later retry.
    ///
    /// @param editStorage the pool that owns the allocated body and its channels
    /// @param path the archive-local entry path
    /// @param expectedSize the expected non-negative byte count, or {@link ArkivoEditStorage#UNKNOWN_SIZE}
    /// @param input the borrowed input stream copied from its current position through end of input
    /// @return the populated content handle owned by the pool
    /// @throws IOException if content allocation, transfer, or channel cleanup fails
    public static ArkivoStoredContent storeInput(
            StoredContentPool editStorage,
            String path,
            long expectedSize,
            InputStream input
    ) throws IOException {
        Objects.requireNonNull(editStorage, "editStorage");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(input, "input");
        ArkivoStoredContent content = editStorage.createContent(path, expectedSize);
        try {
            SeekableByteChannel output = content.openChannel(Set.of(
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            ));
            @Nullable Throwable failure = null;
            try {
                copyInput(input, output);
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
                throw exception;
            } finally {
                closeAfterTransfer(output, failure);
            }
            return content;
        } catch (IOException | RuntimeException | Error exception) {
            try {
                content.close();
            } catch (IOException | RuntimeException | Error cleanupFailure) {
                addSuppressed(exception, cleanupFailure);
            }
            throw exception;
        }
    }

    /// Copies all input bytes to a writable channel using bounded memory.
    ///
    /// @param input the borrowed input stream read through end of input
    /// @param output the borrowed writable channel that receives every byte
    /// @throws IOException if either endpoint fails or a nonempty channel write makes no progress
    public static void copyInput(InputStream input, WritableByteChannel output) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        byte[] buffer = new byte[TRANSFER_BUFFER_SIZE];
        ByteBuffer bytes = ByteBuffer.wrap(buffer);
        while (true) {
            int count = input.read(buffer);
            if (count < 0) {
                return;
            }
            if (count == 0) {
                int value = input.read();
                if (value < 0) {
                    return;
                }
                buffer[0] = (byte) value;
                count = 1;
            }
            bytes.clear();
            bytes.limit(count);
            while (bytes.hasRemaining()) {
                if (output.write(bytes) == 0) {
                    throw new IOException("Stored content channel made no write progress");
                }
            }
        }
    }

    /// Copies optional source content into destination content, truncating the destination first.
    ///
    /// @param source the content copied from its beginning, or {@code null} to leave an empty destination
    /// @param destination the content truncated and populated by this operation
    /// @throws IOException if either content handle cannot be opened, transferred, or closed,
    ///                     or a nonempty channel read or write makes no progress
    public static void copyContent(
            @Nullable ArkivoStoredContent source,
            ArkivoStoredContent destination
    ) throws IOException {
        Objects.requireNonNull(destination, "destination");
        SeekableByteChannel output = destination.openChannel(Set.of(
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        ));
        @Nullable Throwable failure = null;
        try {
            if (source == null) {
                return;
            }
            SeekableByteChannel input = source.openChannel(Set.of(StandardOpenOption.READ));
            try {
                copyInput(StreamChannelAdapters.inputStream(input), output);
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
                throw exception;
            } finally {
                closeAfterTransfer(input, failure);
            }
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            closeAfterTransfer(output, failure);
        }
    }

    /// Copies assembled content to a commit output and closes both transfer channels.
    ///
    /// This method neither commits nor rolls back the output. A transfer failure retains its identity;
    /// distinct cleanup failures are suppressed onto it. Both channel closures are attempted, but this method
    /// does not retain channels or retry a failed close.
    ///
    /// @param content the borrowed assembled content, read from its beginning through end of input
    /// @param output the borrowed commit output, whose channel is opened for writing and truncated
    /// @throws IOException if opening, copying, or closing a transfer channel fails,
    ///                     or a nonempty channel read or write makes no progress
    public static void copyToCommitOutput(ArkivoStoredContent content, ArkivoCommitOutput output) throws IOException {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(output, "output");
        SeekableByteChannel source = content.openChannel(Set.of(StandardOpenOption.READ));
        @Nullable Throwable failure = null;
        try {
            SeekableByteChannel destination = output.openChannel(Set.of(
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
            try {
                copyInput(StreamChannelAdapters.inputStream(source), destination);
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
                throw exception;
            } finally {
                closeAfterTransfer(destination, failure);
            }
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            closeAfterTransfer(source, failure);
        }
    }

    /// Closes a transfer channel without suppressing a primary failure onto itself.
    ///
    /// @param channel the channel to close; its owner remains responsible for retrying failed cleanup
    /// @param failure the pending transfer failure, or `null` if the transfer completed normally
    /// @throws IOException if closure fails without a pending transfer failure
    public static void closeAfterTransfer(SeekableByteChannel channel, @Nullable Throwable failure) throws IOException {
        try {
            channel.close();
        } catch (IOException | RuntimeException | Error exception) {
            if (failure == null) {
                throw exception;
            }
            addSuppressed(failure, exception);
        }
    }

    /// Opens an input stream over content, or an empty stream when no body is present.
    /// Nonempty reads fail with `IOException` if the content channel makes no progress.
    ///
    /// @param content the stored content to open, or {@code null} for an empty body
    /// @return a new caller-owned input stream
    /// @throws IOException if the content cannot be opened for reading
    public static InputStream openInputStream(@Nullable ArkivoStoredContent content) throws IOException {
        return content != null
                ? StreamChannelAdapters.inputStream(content.openChannel(Set.of(StandardOpenOption.READ)))
                : InputStream.nullInputStream();
    }

    /// Opens a read-only seekable channel over content, or an empty channel when no body is present.
    ///
    /// @param content the stored content to open, or {@code null} for an empty body
    /// @return a new caller-owned read-only seekable channel
    /// @throws IOException if the content cannot be opened for reading
    public static SeekableByteChannel openReadChannel(@Nullable ArkivoStoredContent content) throws IOException {
        return content != null
                ? content.openChannel(Set.of(StandardOpenOption.READ))
                : new ReadOnlyByteArrayChannel(new byte[0]);
    }

    /// Returns the default directory for temporary indexed content.
    private static Path defaultStorageDirectory() {
        return Path.of(System.getProperty("java.io.tmpdir", ".")).toAbsolutePath().normalize();
    }

    /// Adds a cleanup failure unless it is already the primary failure.
    private static void addSuppressed(Throwable failure, Throwable cleanupFailure) {
        if (failure != cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
