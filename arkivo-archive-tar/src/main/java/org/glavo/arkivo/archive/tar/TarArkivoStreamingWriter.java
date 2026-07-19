// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoEditStorageFactory;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.archive.tar.internal.TarCompressionStreams;
import org.glavo.arkivo.archive.tar.internal.TarArkivoStreamingWriterImpl;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/// Writes TAR entries to a forward-only stream.
///
/// Regular file bodies are staged until their final size can be written into the TAR header. Default factories use
/// temporary-file storage under the system temporary directory; overloads accepting `ArkivoEditStorage` let callers
/// select another policy. The writer owns and closes its output and body storage.
///
/// Only one entry may be pending. Closing its `ArkivoStreamingWriter.Entry` commits it without opening a caller-writable
/// body; opening a regular-file body transfers completion to that body, and another entry cannot begin until it closes
/// successfully. Metadata views are configurable only while the entry is pending. Closing the writer commits any
/// pending entry, writes the TAR end marker, finishes the optional compression wrapper, and closes owned resources.
/// Once close begins, entry operations remain closed after a failure; another `close()` call retries incomplete cleanup.
/// Body writes may block on the selected staging storage. Entry completion and writer close may block while copying a
/// staged body to the archive output and while the output or compression wrapper flushes.
///
/// Output ownership transfers after argument and option validation; encoder setup failure then closes the output.
/// Explicit or option-provided body storage transfers only when a writer is returned successfully.
@NotNullByDefault
public abstract sealed class TarArkivoStreamingWriter extends ArkivoStreamingWriter
        permits TarArkivoStreamingWriterImpl {
    /// Creates a streaming TAR writer base instance.
    protected TarArkivoStreamingWriter() {
    }

    /// Creates a streaming TAR writer that writes to an archive path.
    ///
    /// @param path the output path to create or truncate immediately
    /// @return a writer that finalizes the uncompressed TAR stream and closes the path output on close
    /// @throws IOException if the path cannot be opened for create-or-replace output
    public static TarArkivoStreamingWriter create(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return open(Files.newByteChannel(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        ));
    }

    /// Creates a streaming TAR writer at an archive path with options.
    ///
    /// @param path the output path to create or truncate immediately
    /// @param options the outer compression and body-staging policy
    /// @return a writer that finalizes the TAR and outer compression stream on close
    /// @throws IOException if the path or compression encoder cannot be initialized
    public static TarArkivoStreamingWriter create(
            Path path,
            TarArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        @Nullable CompressionCodec<?> compressionCodec = compressionCodec(options.compression());
        WritableByteChannel archiveOutput = TarCompressionStreams.openArchiveOutput(
                Files.newByteChannel(
                        path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                ),
                compressionCodec
        );
        return openConfiguredWriter(archiveOutput, options.common().editStorageFactory());
    }

    /// Creates a streaming TAR writer that writes to an archive path and owns the given body storage.
    ///
    /// @param path the output path to create or truncate immediately
    /// @param bodyStorage the staging storage whose ownership transfers when the writer is returned
    /// @return a writer that closes the path output and `bodyStorage` after finalization
    /// @throws IOException if the path cannot be opened for create-or-replace output
    public static TarArkivoStreamingWriter create(Path path, ArkivoEditStorage bodyStorage) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(bodyStorage, "bodyStorage");
        return open(Files.newByteChannel(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        ), bodyStorage);
    }

    /// Creates a streaming TAR writer at an archive path with owned body storage and options.
    ///
    /// @param path the output path to create or truncate after configuration validation
    /// @param bodyStorage the staging storage whose ownership transfers when the writer is returned
    /// @param options the outer compression policy; its common options must not specify another edit storage
    /// @return a writer that closes the path output and `bodyStorage` after finalization
    /// @throws IllegalArgumentException if both `bodyStorage` and `options` select body storage
    /// @throws IOException if the path or compression encoder cannot be initialized
    public static TarArkivoStreamingWriter create(
            Path path,
            ArkivoEditStorage bodyStorage,
            TarArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(bodyStorage, "bodyStorage");
        Objects.requireNonNull(options, "options");
        if (options.common().editStorageFactory() != null) {
            throw new IllegalArgumentException(
                    "TAR body storage must be provided either as an argument or an option"
            );
        }
        @Nullable CompressionCodec<?> compressionCodec = compressionCodec(options.compression());
        return new TarArkivoStreamingWriterImpl(
                StreamChannelAdapters.outputStream(TarCompressionStreams.openArchiveOutput(
                        Files.newByteChannel(
                                path,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE
                        ),
                        compressionCodec
                )),
                bodyStorage
        );
    }

    /// Opens a streaming TAR writer over an output stream.
    ///
    /// @param output the stream at whose current position TAR bytes are written; ownership transfers to the writer
    /// @return an uncompressed writer that closes `output` after finalization
    public static TarArkivoStreamingWriter open(OutputStream output) {
        Objects.requireNonNull(output, "output");
        return open(StreamChannelAdapters.writableChannel(output));
    }

    /// Opens a streaming TAR writer over an output stream with options.
    ///
    /// @param output the stream at whose current position compressed or uncompressed TAR bytes are written
    /// @param options the outer compression and body-staging policy
    /// @return a writer that owns and closes `output` and any option-provided body storage
    /// @throws IOException if compression encoder initialization fails; after validation, failure closes `output`
    public static TarArkivoStreamingWriter open(
            OutputStream output,
            TarArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        return open(StreamChannelAdapters.writableChannel(output), options);
    }

    /// Opens a streaming TAR writer over an output stream and owns the given body storage.
    ///
    /// @param output the stream at whose current position uncompressed TAR bytes are written
    /// @param bodyStorage the staging storage whose ownership transfers with the returned writer
    /// @return a writer that closes both `output` and `bodyStorage` after finalization
    public static TarArkivoStreamingWriter open(OutputStream output, ArkivoEditStorage bodyStorage) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(bodyStorage, "bodyStorage");
        return open(StreamChannelAdapters.writableChannel(output), bodyStorage);
    }

    /// Opens a streaming TAR writer over an output stream with owned body storage and options.
    ///
    /// @param output the stream at whose current position compressed or uncompressed TAR bytes are written
    /// @param bodyStorage the staging storage whose ownership transfers when the writer is returned
    /// @param options the outer compression policy; its common options must not specify another edit storage
    /// @return a writer that closes `output` and `bodyStorage` after finalization
    /// @throws IllegalArgumentException if both `bodyStorage` and `options` select body storage
    /// @throws IOException if compression encoder initialization fails; the validated output is closed on failure
    public static TarArkivoStreamingWriter open(
            OutputStream output,
            ArkivoEditStorage bodyStorage,
            TarArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(bodyStorage, "bodyStorage");
        Objects.requireNonNull(options, "options");
        return open(StreamChannelAdapters.writableChannel(output), bodyStorage, options);
    }

    /// Opens a streaming TAR writer over a writable channel.
    ///
    /// @param output the channel at whose current sequential position uncompressed TAR bytes are written
    /// @return a writer that owns and closes `output` after finalization
    public static TarArkivoStreamingWriter open(WritableByteChannel output) {
        Objects.requireNonNull(output, "output");
        return new TarArkivoStreamingWriterImpl(StreamChannelAdapters.outputStream(output));
    }

    /// Opens a streaming TAR writer over a writable channel with options.
    ///
    /// @param output the channel at whose current position compressed or uncompressed TAR bytes are written
    /// @param options the outer compression and body-staging policy
    /// @return a writer that owns and closes `output` and any option-provided body storage
    /// @throws IOException if compression encoder initialization fails; after validation, failure closes `output`
    public static TarArkivoStreamingWriter open(
            WritableByteChannel output,
            TarArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        @Nullable CompressionCodec<?> compressionCodec = compressionCodec(options.compression());
        WritableByteChannel archiveOutput = TarCompressionStreams.openArchiveOutput(output, compressionCodec);
        return openConfiguredWriter(archiveOutput, options.common().editStorageFactory());
    }

    /// Opens a streaming TAR writer over a writable channel and owns the given body storage.
    ///
    /// @param output the channel at whose current sequential position uncompressed TAR bytes are written
    /// @param bodyStorage the staging storage whose ownership transfers with the returned writer
    /// @return a writer that closes both `output` and `bodyStorage` after finalization
    public static TarArkivoStreamingWriter open(WritableByteChannel output, ArkivoEditStorage bodyStorage) {
        Objects.requireNonNull(output, "output");
        return new TarArkivoStreamingWriterImpl(
                StreamChannelAdapters.outputStream(output),
                Objects.requireNonNull(bodyStorage, "bodyStorage")
        );
    }

    /// Opens a streaming TAR writer over a writable channel with owned body storage and options.
    ///
    /// @param output the channel at whose current position compressed or uncompressed TAR bytes are written
    /// @param bodyStorage the staging storage whose ownership transfers when the writer is returned
    /// @param options the outer compression policy; its common options must not specify another edit storage
    /// @return a writer that closes `output` and `bodyStorage` after finalization
    /// @throws IllegalArgumentException if both `bodyStorage` and `options` select body storage
    /// @throws IOException if compression encoder initialization fails; the validated output is closed on failure
    public static TarArkivoStreamingWriter open(
            WritableByteChannel output,
            ArkivoEditStorage bodyStorage,
            TarArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(bodyStorage, "bodyStorage");
        Objects.requireNonNull(options, "options");
        if (options.common().editStorageFactory() != null) {
            throw new IllegalArgumentException(
                    "TAR body storage must be provided either as an argument or an option"
            );
        }
        @Nullable CompressionCodec<?> compressionCodec = compressionCodec(options.compression());
        return new TarArkivoStreamingWriterImpl(
                StreamChannelAdapters.outputStream(TarCompressionStreams.openArchiveOutput(
                        output,
                        compressionCodec
                )),
                bodyStorage
        );
    }

    /// Creates a writer and opens operation-owned body storage selected by the configured factory.
    private static TarArkivoStreamingWriter openConfiguredWriter(
            WritableByteChannel archiveOutput,
            @Nullable ArkivoEditStorageFactory storageFactory
    ) throws IOException {
        if (storageFactory == null) {
            return new TarArkivoStreamingWriterImpl(StreamChannelAdapters.outputStream(archiveOutput));
        }

        ArkivoEditStorage bodyStorage;
        try {
            bodyStorage = storageFactory.open();
        } catch (IOException | RuntimeException | Error exception) {
            closeAfterConstructionFailure(archiveOutput, exception);
            throw exception;
        }
        try {
            return new TarArkivoStreamingWriterImpl(
                    StreamChannelAdapters.outputStream(archiveOutput),
                    bodyStorage
            );
        } catch (RuntimeException | Error exception) {
            closeAfterConstructionFailure(bodyStorage, exception);
            closeAfterConstructionFailure(archiveOutput, exception);
            throw exception;
        }
    }

    /// Returns the codec carried by a creation policy, or `null` for a plain TAR stream.
    private static @Nullable CompressionCodec<?> compressionCodec(TarCompression.Create compression) {
        Objects.requireNonNull(compression, "compression");
        return compression instanceof TarCompression.Codec configured ? configured.codec() : null;
    }

    /// Closes a resource allocated for a writer that could not be constructed.
    private static void closeAfterConstructionFailure(AutoCloseable resource, Throwable failure) {
        try {
            resource.close();
        } catch (Exception | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /// Begins a pending hard link entry for the given logical archive path and target archive path.
    ///
    /// The target is normalized as an archive-local relative path and is recorded without resolving it or requiring the
    /// target entry to precede this entry.
    ///
    /// @param path the relative archive-local path of the new hard-link entry
    /// @param target the relative archive-local path stored as the hard-link target
    /// @return a handle for configuring and committing the pending metadata-only entry
    /// @throws IllegalArgumentException if either path is empty, absolute, or contains a parent traversal
    /// @throws IllegalStateException if another entry remains pending
    /// @throws IOException if the writer is closed or format-specific output cannot begin
    public final ArkivoStreamingWriter.Entry beginHardLink(String path, String target) throws IOException {
        String checkedTarget = Objects.requireNonNull(target, "target");
        return beginCustomEntry(path, checkedPath -> beginHardLinkEntry(checkedPath, checkedTarget));
    }

    /// Begins a format-specific pending hard link entry.
    ///
    /// @param path the validated logical archive path for the new entry
    /// @param target the archive-local target path text to store
    /// @throws IOException if the hard-link entry cannot be initialized
    protected abstract void beginHardLinkEntry(String path, String target) throws IOException;
}
