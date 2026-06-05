// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoFileSystemEntryStream;
import org.glavo.arkivo.internal.ArkivoPathMatchers;
import org.glavo.arkivo.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.zip.ZipArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileStore;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import static org.glavo.arkivo.zip.internal.ZipConstants.CENTRAL_DIRECTORY_HEADER_SIGNATURE;
import static org.glavo.arkivo.zip.internal.ZipConstants.DATA_DESCRIPTOR_FLAG;
import static org.glavo.arkivo.zip.internal.ZipConstants.DATA_DESCRIPTOR_SIGNATURE;
import static org.glavo.arkivo.zip.internal.ZipConstants.DEFLATED_METHOD;
import static org.glavo.arkivo.zip.internal.ZipConstants.ENCRYPTED_FLAG;
import static org.glavo.arkivo.zip.internal.ZipConstants.END_OF_CENTRAL_DIRECTORY_SIGNATURE;
import static org.glavo.arkivo.zip.internal.ZipConstants.LOCAL_FILE_HEADER_SIGNATURE;
import static org.glavo.arkivo.zip.internal.ZipConstants.STORED_METHOD;
import static org.glavo.arkivo.zip.internal.ZipConstants.UTF8_FLAG;
import static org.glavo.arkivo.zip.internal.ZipLittleEndian.readInt;
import static org.glavo.arkivo.zip.internal.ZipLittleEndian.readIntOrEnd;
import static org.glavo.arkivo.zip.internal.ZipLittleEndian.readUnsignedShort;

/// Implements a forward-only ZIP archive file system for streaming reads.
@NotNullByDefault
public final class StreamingZipArkivoReadFileSystemImpl extends ZipArkivoFileSystem {
    /// The pushback buffer size used to return bytes read past the end of a deflated stream.
    private static final int PUSHBACK_BUFFER_SIZE = 8192;

    /// The provider that created this ZIP file system.
    private final ZipArkivoFileSystemProvider provider;

    /// The input stream that provides ZIP bytes.
    private final PushbackInputStream input;

    /// The parsed ZIP file system configuration.
    private final ZipArkivoFileSystemConfig config;

    /// The optional state lock.
    private final @Nullable ReentrantLock lock;

    /// The root path for this ZIP file system.
    private final ZipArkivoPath rootPath;

    /// Whether an entry stream has already been opened.
    private boolean entryStreamOpened;

    /// The current parsed entry exposed to streaming reader adapters, or `null` when no entry is active.
    private @Nullable LocalEntry currentStreamingEntry;

    /// Whether this file system is open.
    private boolean open = true;

    /// Creates a streaming ZIP archive file system.
    public StreamingZipArkivoReadFileSystemImpl(
            ZipArkivoFileSystemProvider provider,
            ReadableByteChannel source,
            ZipArkivoFileSystemConfig config
    ) {
        this(provider, Channels.newInputStream(Objects.requireNonNull(source, "source")), config);
    }

    /// Creates a streaming ZIP archive file system from an input stream.
    public StreamingZipArkivoReadFileSystemImpl(
            ZipArkivoFileSystemProvider provider,
            InputStream source,
            ZipArkivoFileSystemConfig config
    ) {
        super(config.threadSafety());
        this.provider = Objects.requireNonNull(provider, "provider");
        this.input = new PushbackInputStream(Objects.requireNonNull(source, "source"), PUSHBACK_BUFFER_SIZE);
        this.config = Objects.requireNonNull(config, "config");
        this.lock = ZipLocks.create(config.threadSafety());
        this.rootPath = ZipArkivoPath.root(this);
    }

    /// Returns the provider that created this ZIP file system.
    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    /// Closes this ZIP file system and the input stream.
    @Override
    public void close() throws IOException {
        lock();
        try {
            if (!open) {
                return;
            }
            open = false;
            input.close();
        } finally {
            unlock();
        }
    }

    /// Returns whether this ZIP file system is open.
    @Override
    public boolean isOpen() {
        lock();
        try {
            return open;
        } finally {
            unlock();
        }
    }

    /// Returns whether this ZIP file system rejects write operations.
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /// Returns the ZIP entry path separator.
    @Override
    public String getSeparator() {
        return "/";
    }

    /// Returns the root directory path.
    @Override
    public @Unmodifiable Iterable<Path> getRootDirectories() {
        return List.of(rootPath);
    }

    /// Returns the file stores exposed by this ZIP file system.
    @Override
    public @Unmodifiable Iterable<FileStore> getFileStores() {
        return List.of(StreamingZipFileStore.READ_ONLY);
    }

    /// Returns the supported file attribute view names.
    @Override
    public @Unmodifiable Set<String> supportedFileAttributeViews() {
        return Set.of("basic", "zip", "owner", "posix");
    }

    /// Returns a path inside this ZIP file system.
    @Override
    public Path getPath(String first, String... more) {
        checkOpen();
        return ZipArkivoPath.of(this, first, more);
    }

    /// Returns a path matcher for paths in this ZIP file system.
    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        checkOpen();
        return ArkivoPathMatchers.create(syntaxAndPattern, '/');
    }

    /// Always rejects watch services because ZIP watch services are not supported.
    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException("ZIP watch services are not supported");
    }

    /// Always rejects user principal lookups because ZIP does not expose principals.
    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException("ZIP user principals are not supported");
    }

    /// Opens the single forward-only stream over ZIP entry paths in storage order.
    @Override
    public ArkivoFileSystemEntryStream openEntryStream() {
        lock();
        try {
            checkOpen();
            if (entryStreamOpened) {
                throw new IllegalStateException("Streaming ZIP input entry stream has already been opened");
            }
            entryStreamOpened = true;
            return new StreamingEntryStream();
        } finally {
            unlock();
        }
    }

    /// Returns the current ZIP entry attributes, or `null` when no entry is active.
    public @Nullable ZipArkivoEntryAttributes currentEntryAttributes() {
        lock();
        try {
            LocalEntry entry = currentStreamingEntry;
            return entry != null ? entry.attributes() : null;
        } finally {
            unlock();
        }
    }

    /// Returns the number of bytes stored before the ZIP archive body.
    @Override
    public long preambleSize() {
        throw new UnsupportedOperationException("Streaming ZIP input does not expose a preamble");
    }

    /// Opens a read-only channel over the bytes stored before the ZIP archive body.
    @Override
    public SeekableByteChannel openPreambleChannel() {
        throw new UnsupportedOperationException("Streaming ZIP input does not expose a preamble");
    }

    /// Requires this file system to be open.
    private void checkOpen() {
        if (!open) {
            throw new ClosedFileSystemException();
        }
    }

    /// Acquires the state lock when it is present.
    private void lock() {
        ZipLocks.lock(lock);
    }

    /// Releases the state lock when it is present.
    private void unlock() {
        ZipLocks.unlock(lock);
    }

    /// Reads exactly `length` bytes.
    private byte[] readBytes(int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(bytes, offset, length - offset);
            if (read < 0) {
                throw new EOFException("Unexpected end of ZIP stream");
            }
            offset += read;
        }
        return bytes;
    }

    /// Reads and validates a ZIP data descriptor.
    private void readDataDescriptor() throws IOException {
        int first = readInt(input);
        if (first != DATA_DESCRIPTOR_SIGNATURE) {
            // The first value was CRC-32; the descriptor signature is optional.
            readInt(input);
            readInt(input);
            return;
        }
        readInt(input);
        readInt(input);
        readInt(input);
    }

    /// Decodes an entry path.
    private String decodePath(byte[] rawName, int flags) {
        if ((flags & UTF8_FLAG) != 0) {
            return new String(rawName, StandardCharsets.UTF_8);
        }
        try {
            return new ZipEntryNameDecoder(config.entryNameEncoding()).decodePath(rawName, flags, new byte[0]);
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new IllegalArgumentException("Cannot decode ZIP entry name", exception);
        }
    }

    /// Implements a forward-only ZIP entry stream.
    @NotNullByDefault
    private final class StreamingEntryStream implements ArkivoFileSystemEntryStream {
        /// The current local entry, or `null` when no entry is active.
        private @Nullable LocalEntry currentEntry;

        /// The current entry input stream, or `null` when no entry input stream is active.
        private @Nullable CurrentEntryInputStream currentInput;

        /// Whether this stream is open.
        private boolean streamOpen = true;

        /// Creates a streaming entry stream.
        private StreamingEntryStream() {
        }

        /// Returns the next path or `null` when traversal is complete.
        @Override
        public @Nullable Path next() throws IOException {
            lock();
            try {
                ensureStreamOpen();
                closeCurrentEntry();

                int signature = readIntOrEnd(input);
                if (signature < 0
                        || signature == CENTRAL_DIRECTORY_HEADER_SIGNATURE
                        || signature == END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                    currentEntry = null;
                    currentStreamingEntry = null;
                    return null;
                }
                if (signature != LOCAL_FILE_HEADER_SIGNATURE) {
                    throw new IOException("Unexpected ZIP stream record signature: " + Integer.toHexString(signature));
                }

                int versionNeededToExtract = readUnsignedShort(input);
                int flags = readUnsignedShort(input);
                int method = readUnsignedShort(input);
                readUnsignedShort(input);
                readUnsignedShort(input);
                boolean hasDataDescriptor = (flags & DATA_DESCRIPTOR_FLAG) != 0;
                long headerCrc32 = Integer.toUnsignedLong(readInt(input));
                long headerCompressedSize = Integer.toUnsignedLong(readInt(input));
                long headerUncompressedSize = Integer.toUnsignedLong(readInt(input));
                long crc32 = hasDataDescriptor ? ZipArkivoEntryAttributes.UNKNOWN_CRC32 : headerCrc32;
                long compressedSize = hasDataDescriptor ? ZipArkivoEntryAttributes.UNKNOWN_SIZE : headerCompressedSize;
                long uncompressedSize = hasDataDescriptor ? ZipArkivoEntryAttributes.UNKNOWN_SIZE : headerUncompressedSize;
                int nameLength = readUnsignedShort(input);
                int extraLength = readUnsignedShort(input);
                byte[] rawName = readBytes(nameLength);
                byte[] extraData = readBytes(extraLength);

                String path = decodePath(rawName, flags);
                boolean directory = path.endsWith("/");
                LocalEntry entry = new LocalEntry(
                        path,
                        rawName,
                        flags,
                        method,
                        versionNeededToExtract,
                        crc32,
                        compressedSize,
                        uncompressedSize,
                        extraData,
                        directory
                );
                currentEntry = entry;
                currentStreamingEntry = entry;
                return getPath("/" + path);
            } finally {
                unlock();
            }
        }

        /// Opens a readable channel for the current entry.
        @Override
        public ReadableByteChannel openChannel() throws IOException {
            lock();
            try {
                ensureStreamOpen();
                LocalEntry entry = currentEntry;
                if (entry == null) {
                    throw new IOException("No current ZIP entry");
                }
                if (entry.directory) {
                    throw new IOException("ZIP entry is a directory: " + entry.path);
                }
                if ((entry.flags & ENCRYPTED_FLAG) != 0) {
                    throw new IOException("Encrypted ZIP entries are not supported yet: " + entry.path);
                }
                if (currentInput != null) {
                    throw new IOException("Current ZIP entry input stream is already open");
                }

                CurrentEntryInputStream entryInput = new CurrentEntryInputStream(this, entryInputStream(entry));
                currentInput = entryInput;
                return Channels.newChannel(entryInput);
            } finally {
                unlock();
            }
        }

        /// Closes this entry stream.
        @Override
        public void close() throws IOException {
            lock();
            try {
                if (!streamOpen) {
                    return;
                }
                streamOpen = false;
                closeCurrentEntry();
            } finally {
                unlock();
            }
        }

        /// Opens an input stream for an entry.
        private InputStream entryInputStream(LocalEntry entry) throws IOException {
            boolean hasDataDescriptor = (entry.flags & DATA_DESCRIPTOR_FLAG) != 0;
            if (entry.method == STORED_METHOD) {
                if (hasDataDescriptor) {
                    throw new IOException("Stored ZIP entries with data descriptors are not supported yet");
                }
                return new BoundedInputStream(input, entry.compressedSize);
            }
            if (entry.method == DEFLATED_METHOD) {
                Inflater inflater = new Inflater(true);
                InputStream compressed = hasDataDescriptor
                        ? input
                        : new BoundedInputStream(input, entry.compressedSize);
                return new EntryInflaterInputStream(compressed, inflater, hasDataDescriptor ? input : null);
            }
            throw new IOException("Unsupported ZIP compression method: " + entry.method);
        }

        /// Closes or drains the current entry.
        private void closeCurrentEntry() throws IOException {
            CurrentEntryInputStream entryInput = currentInput;
            if (entryInput != null) {
                entryInput.close();
                return;
            }

            LocalEntry entry = currentEntry;
            if (entry != null && !entry.directory) {
                try (InputStream ignored = entryInputStream(entry)) {
                    ignored.transferTo(OutputStream.nullOutputStream());
                }
            }
            currentEntry = null;
            currentStreamingEntry = null;
        }

        /// Marks the current input stream as closed.
        private void closeCurrentInput(CurrentEntryInputStream inputStream) {
            lock();
            try {
                if (currentInput == inputStream) {
                    currentInput = null;
                    currentEntry = null;
                    currentStreamingEntry = null;
                }
            } finally {
                unlock();
            }
        }

        /// Requires this entry stream to be open.
        private void ensureStreamOpen() throws IOException {
            checkOpen();
            if (!streamOpen) {
                throw new IOException("Entry stream is closed");
            }
        }

        /// Acquires the owner file system state lock when it is present.
        private void lock() {
            StreamingZipArkivoReadFileSystemImpl.this.lock();
        }

        /// Releases the owner file system state lock when it is present.
        private void unlock() {
            StreamingZipArkivoReadFileSystemImpl.this.unlock();
        }
    }

    /// Reads bytes from the current ZIP entry.
    @NotNullByDefault
    private static final class CurrentEntryInputStream extends InputStream {
        /// The owner entry stream.
        private final StreamingEntryStream owner;

        /// The delegate entry input stream.
        private final InputStream input;

        /// Whether this stream is open.
        private boolean inputOpen = true;

        /// Creates a current entry input stream.
        private CurrentEntryInputStream(StreamingEntryStream owner, InputStream input) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.input = Objects.requireNonNull(input, "input");
        }

        /// Reads one byte from the entry.
        @Override
        public int read() throws IOException {
            byte[] buffer = new byte[1];
            int read = read(buffer, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(buffer[0]);
        }

        /// Reads bytes from the entry.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            owner.lock();
            try {
                if (!inputOpen) {
                    throw new IOException("ZIP entry input stream is closed");
                }
                if (length == 0) {
                    return 0;
                }
                return input.read(bytes, offset, length);
            } finally {
                owner.unlock();
            }
        }

        /// Closes this entry input stream after draining unread bytes.
        @Override
        public void close() throws IOException {
            owner.lock();
            try {
                if (!inputOpen) {
                    return;
                }
                inputOpen = false;
                try {
                    input.transferTo(OutputStream.nullOutputStream());
                    input.close();
                } finally {
                    owner.closeCurrentInput(this);
                }
            } finally {
                owner.unlock();
            }
        }
    }

    /// Inflates raw ZIP deflate data and pushes back bytes read past the deflate stream.
    @NotNullByDefault
    private final class EntryInflaterInputStream extends InflaterInputStream {
        /// The inflater used by this stream.
        private final Inflater inflater;

        /// The pushback input used when this entry has a data descriptor, or `null` otherwise.
        private final @Nullable PushbackInputStream pushbackInput;

        /// Whether end-of-entry cleanup has run.
        private boolean finishedEntry;

        /// Creates an entry inflater stream.
        private EntryInflaterInputStream(InputStream input, Inflater inflater, @Nullable PushbackInputStream pushbackInput) {
            super(input, inflater);
            this.inflater = inflater;
            this.pushbackInput = pushbackInput;
        }

        /// Reads bytes from the inflated entry.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (finishedEntry) {
                return -1;
            }
            int read = super.read(bytes, offset, length);
            if (read < 0) {
                finishEntry();
            }
            return read;
        }

        /// Closes this inflater stream.
        @Override
        public void close() throws IOException {
            try {
                byte[] discard = new byte[8192];
                while (read(discard) >= 0) {
                    // Drain the deflate stream so the following descriptor can be parsed.
                }
            } finally {
                finishEntry();
            }
        }

        /// Finishes the current entry and releases the inflater.
        private void finishEntry() throws IOException {
            if (finishedEntry) {
                return;
            }
            finishedEntry = true;
            try {
                PushbackInputStream pushback = pushbackInput;
                if (pushback != null) {
                    int remaining = inflater.getRemaining();
                    if (remaining > 0) {
                        pushback.unread(buf, len - remaining, remaining);
                    }
                    readDataDescriptor();
                }
            } finally {
                inflater.end();
            }
        }
    }

    /// Reads a bounded number of bytes from an input stream without closing it.
    @NotNullByDefault
    private static final class BoundedInputStream extends InputStream {
        /// The source input stream.
        private final InputStream input;

        /// The remaining bytes.
        private long remaining;

        /// Creates a bounded input stream.
        private BoundedInputStream(InputStream input, long size) {
            this.input = Objects.requireNonNull(input, "input");
            this.remaining = size;
        }

        /// Reads one byte.
        @Override
        public int read() throws IOException {
            byte[] buffer = new byte[1];
            int read = read(buffer, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(buffer[0]);
        }

        /// Reads bytes.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (remaining == 0) {
                return -1;
            }
            int requested = (int) Math.min(length, remaining);
            int read = input.read(bytes, offset, requested);
            if (read < 0) {
                throw new EOFException("Unexpected end of ZIP entry data");
            }
            remaining -= read;
            return read;
        }

        /// Drains remaining bytes.
        @Override
        public void close() throws IOException {
            byte[] discard = new byte[8192];
            while (read(discard) >= 0) {
                // Drain remaining bounded data.
            }
        }
    }

    /// Stores one local file header entry.
    @NotNullByDefault
    private static final class LocalEntry {
        /// The decoded entry path.
        private final String path;

        /// The raw entry path bytes.
        private final byte[] rawPath;

        /// The general purpose bit flags.
        private final int flags;

        /// The compression method.
        private final int method;

        /// The ZIP version needed to extract field.
        private final int versionNeededToExtract;

        /// The CRC-32 value from the local header.
        private final long crc32;

        /// The compressed size from the local header.
        private final long compressedSize;

        /// The uncompressed size from the local header.
        private final long uncompressedSize;

        /// The raw local file header extra data bytes.
        private final byte[] extraData;

        /// Whether this entry is a directory.
        private final boolean directory;

        /// Creates a local entry.
        private LocalEntry(
                String path,
                byte[] rawPath,
                int flags,
                int method,
                int versionNeededToExtract,
                long crc32,
                long compressedSize,
                long uncompressedSize,
                byte[] extraData,
                boolean directory
        ) {
            this.path = Objects.requireNonNull(path, "path");
            this.rawPath = Objects.requireNonNull(rawPath, "rawPath");
            this.flags = flags;
            this.method = method;
            this.versionNeededToExtract = versionNeededToExtract;
            this.crc32 = crc32;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.extraData = Objects.requireNonNull(extraData, "extraData");
            this.directory = directory;
        }

        /// Returns ZIP entry attributes for this local entry.
        private ZipArkivoEntryAttributes attributes() {
            return new StreamingZipEntryAttributes(
                    path,
                    rawPath,
                    flags,
                    method,
                    versionNeededToExtract,
                    crc32,
                    compressedSize,
                    uncompressedSize,
                    extraData,
                    directory
            );
        }
    }

}
