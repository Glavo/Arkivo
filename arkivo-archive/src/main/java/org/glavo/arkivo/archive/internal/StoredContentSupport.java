// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.IdentityHashMap;
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

    /// Returns an identity-based mutable set for tracking owned content handles.
    ///
    /// @return a new empty mutable identity set
    public static Set<ArkivoStoredContent> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /// Returns configured edit storage or temporary-file storage in the default system temporary directory.
    ///
    /// @param options the options containing an optional edit-storage strategy
    /// @return the configured storage, or a new default temporary-file storage
    @SuppressWarnings("resource")
    public static ArkivoEditStorage selectStorage(ArchiveOptions options) {
        Objects.requireNonNull(options, "options");
        @Nullable ArkivoEditStorage configured = options.get(ArchiveEnvironmentOptions.EDIT_STORAGE);
        return configured != null
                ? configured
                : ArkivoEditStorage.temporaryFiles(defaultStorageDirectory());
    }

    /// Closes content and storage allocated during a failed open and suppresses every cleanup failure.
    ///
    /// @param editStorage the storage strategy to close after its owned contents
    /// @param ownedContents the identity set of content handles to close
    /// @param failure the primary failure that receives cleanup failures as suppressed exceptions
    public static void closeAfterOpenFailure(
            ArkivoEditStorage editStorage,
            Set<ArkivoStoredContent> ownedContents,
            Throwable failure
    ) {
        Objects.requireNonNull(editStorage, "editStorage");
        Objects.requireNonNull(ownedContents, "ownedContents");
        Objects.requireNonNull(failure, "failure");
        for (ArkivoStoredContent content : ownedContents) {
            try {
                content.close();
            } catch (IOException | RuntimeException | Error cleanupFailure) {
                addSuppressed(failure, cleanupFailure);
            }
        }
        try {
            editStorage.close();
        } catch (IOException | RuntimeException | Error cleanupFailure) {
            addSuppressed(failure, cleanupFailure);
        }
    }

    /// Stores one input body and transfers ownership of the resulting content to the given identity set.
    ///
    /// @param editStorage the storage strategy used to allocate the staged body
    /// @param ownedContents the identity set that assumes ownership after a successful transfer
    /// @param path the archive-local entry path
    /// @param expectedSize the expected non-negative byte count, or {@link ArkivoEditStorage#UNKNOWN_SIZE}
    /// @param input the borrowed input stream copied from its current position through end of input
    /// @return the populated content handle now owned by {@code ownedContents}
    /// @throws IOException if content allocation, transfer, or channel cleanup fails
    public static ArkivoStoredContent storeInput(
            ArkivoEditStorage editStorage,
            Set<ArkivoStoredContent> ownedContents,
            String path,
            long expectedSize,
            InputStream input
    ) throws IOException {
        Objects.requireNonNull(editStorage, "editStorage");
        Objects.requireNonNull(ownedContents, "ownedContents");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(input, "input");
        ArkivoStoredContent content = editStorage.createContent(path, expectedSize);
        try {
            try (SeekableByteChannel output = content.openChannel(Set.of(
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            ))) {
                copyInput(input, output);
            }
            ownedContents.add(content);
            return content;
        } catch (IOException | RuntimeException | Error exception) {
            try {
                content.close();
            } catch (IOException | RuntimeException | Error cleanupFailure) {
                ownedContents.add(content);
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
    /// @throws IOException if either content handle cannot be opened, transferred, or closed
    public static void copyContent(
            @Nullable ArkivoStoredContent source,
            ArkivoStoredContent destination
    ) throws IOException {
        Objects.requireNonNull(destination, "destination");
        try (SeekableByteChannel output = destination.openChannel(Set.of(
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        ))) {
            if (source == null) {
                return;
            }
            try (SeekableByteChannel input = source.openChannel(Set.of(StandardOpenOption.READ))) {
                copyInput(Channels.newInputStream(input), output);
            }
        }
    }

    /// Opens an input stream over content, or an empty stream when no body is present.
    ///
    /// @param content the stored content to open, or {@code null} for an empty body
    /// @return a new caller-owned input stream
    /// @throws IOException if the content cannot be opened for reading
    public static InputStream openInputStream(@Nullable ArkivoStoredContent content) throws IOException {
        return content != null
                ? Channels.newInputStream(content.openChannel(Set.of(StandardOpenOption.READ)))
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
