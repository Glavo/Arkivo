// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.zip.ZipArkivoEntryAttributeView;
import org.glavo.arkivo.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.zip.ZipArkivoFileSystemProvider;
import org.glavo.arkivo.zip.ZipArkivoStreamingWriter;
import org.glavo.arkivo.zip.ZipEncryption;
import org.glavo.arkivo.zip.ZipMethod;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/// Implements the public forward-only ZIP streaming writer API.
@NotNullByDefault
public final class ZipArkivoStreamingWriterImpl extends ZipArkivoStreamingWriter {
    /// The marker used when ZIP external attributes have not been configured.
    private static final long UNKNOWN_EXTERNAL_ATTRIBUTES = -1L;

    /// The internal streaming ZIP file system used by the current writer implementation.
    private final StreamingZipArkivoFileSystemImpl fileSystem;

    /// The optional state lock.
    private final @Nullable ReentrantLock lock;

    /// The pending entry that has not yet been committed, or `null` when no pending entry exists.
    private @Nullable ZipStreamingEntry pendingEntry;

    /// Creates a ZIP streaming writer.
    private ZipArkivoStreamingWriterImpl(StreamingZipArkivoFileSystemImpl fileSystem, ZipArkivoFileSystemConfig config) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
        this.lock = ZipLocks.create(Objects.requireNonNull(config, "config").threadSafety());
    }

    /// Creates a streaming ZIP writer that writes to an archive path.
    public static ZipArkivoStreamingWriterImpl create(Path path, ZipArkivoFileSystemConfig config) throws IOException {
        return new ZipArkivoStreamingWriterImpl(new StreamingZipArkivoFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                path,
                config
        ), config);
    }

    /// Opens a streaming ZIP writer over a writable channel.
    public static ZipArkivoStreamingWriterImpl open(WritableByteChannel output, ZipArkivoFileSystemConfig config) {
        return open(Channels.newOutputStream(Objects.requireNonNull(output, "output")), config);
    }

    /// Opens a streaming ZIP writer over an output stream.
    public static ZipArkivoStreamingWriterImpl open(OutputStream output, ZipArkivoFileSystemConfig config) {
        return new ZipArkivoStreamingWriterImpl(new StreamingZipArkivoFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                Objects.requireNonNull(output, "output"),
                config
        ), config);
    }

    /// Begins a pending ZIP entry for the given logical archive path.
    @Override
    public void beginEntry(String path) {
        lock();
        try {
            if (pendingEntry != null) {
                throw new IllegalStateException("A ZIP streaming entry is already pending");
            }
            pendingEntry = new ZipStreamingEntry(entryPathText(path));
        } finally {
            unlock();
        }
    }

    /// Returns an attribute view used to configure the current pending entry before it is committed.
    @Override
    public <V extends FileAttributeView> @Nullable V attributeView(Class<V> type) {
        Objects.requireNonNull(type, "type");
        lock();
        try {
            ZipStreamingEntry entry = requirePendingEntry();
            if (type == BasicFileAttributeView.class || type == ZipArkivoEntryAttributeView.class) {
                return type.cast(entry.attributes);
            }
            return null;
        } finally {
            unlock();
        }
    }

    /// Creates the current pending entry as a directory and commits its metadata.
    @Override
    public void createDirectory() throws IOException {
        lock();
        try {
            ZipStreamingEntry entry = requirePendingEntry();
            entry.ensurePending();
            entry.attributes.requireSupportedDirectory();
            fileSystem.createDirectory(fileSystem.getPath("/" + entry.entryPath));
            entry.submitted = true;
            pendingEntry = null;
        } finally {
            unlock();
        }
    }

    /// Commits the current pending entry without opening a body channel.
    @Override
    public void endEntry() throws IOException {
        lock();
        try {
            ZipStreamingEntry entry = requirePendingEntry();
            entry.ensurePending();
            entry.attributes.requireSupportedFile();
            try (OutputStream ignored = fileSystem.newOutputStream(fileSystem.getPath("/" + entry.entryPath))) {
                // Closing the entry output stream writes an empty ZIP entry.
            }
            entry.submitted = true;
            pendingEntry = null;
        } finally {
            unlock();
        }
    }

    /// Opens a writable channel for the current pending entry and commits its metadata.
    @Override
    public WritableByteChannel openChannel() throws IOException {
        return Channels.newChannel(openOutputStream());
    }

    /// Opens an output stream for the current pending entry and commits its metadata.
    @Override
    public OutputStream openOutputStream() throws IOException {
        lock();
        try {
            ZipStreamingEntry entry = requirePendingEntry();
            entry.ensurePending();
            entry.attributes.requireSupportedFile();
            OutputStream output = fileSystem.newOutputStream(fileSystem.getPath("/" + entry.entryPath));
            entry.submitted = true;
            pendingEntry = null;
            return output;
        } finally {
            unlock();
        }
    }

    /// Closes this streaming writer and finishes the ZIP stream.
    @Override
    public void close() throws IOException {
        lock();
        try {
            fileSystem.close();
        } finally {
            unlock();
        }
    }

    /// Returns the current pending entry.
    private ZipStreamingEntry requirePendingEntry() {
        ZipStreamingEntry entry = pendingEntry;
        if (entry == null) {
            throw new IllegalStateException("No ZIP streaming entry is pending");
        }
        return entry;
    }

    /// Acquires the state lock when it is present.
    private void lock() {
        ZipLocks.lock(lock);
    }

    /// Releases the state lock when it is present.
    private void unlock() {
        ZipLocks.unlock(lock);
    }

    /// Converts a logical entry path to ZIP path text.
    private static String entryPathText(String path) {
        Objects.requireNonNull(path, "path");
        String normalizedSeparators = path.replace('\\', '/');
        if (normalizedSeparators.startsWith("/")) {
            throw new IllegalArgumentException("ZIP streaming entry paths must be relative");
        }
        if (normalizedSeparators.length() >= 2 && normalizedSeparators.charAt(1) == ':') {
            throw new IllegalArgumentException("ZIP streaming entry paths must not contain drive roots");
        }

        StringBuilder builder = new StringBuilder();
        int start = 0;
        while (start <= normalizedSeparators.length()) {
            int end = normalizedSeparators.indexOf('/', start);
            if (end < 0) {
                end = normalizedSeparators.length();
            }

            String segment = normalizedSeparators.substring(start, end);
            if (segment.isEmpty() || segment.equals(".")) {
                start = end + 1;
                continue;
            }
            if (segment.equals("..")) {
                throw new IllegalArgumentException("ZIP streaming entry paths must not contain ..");
            }
            if (!builder.isEmpty()) {
                builder.append('/');
            }
            builder.append(segment);
            start = end + 1;
        }
        if (builder.isEmpty()) {
            throw new IllegalArgumentException("ZIP streaming entry path must not be empty");
        }
        return builder.toString();
    }

    /// Implements one pending ZIP streaming entry.
    @NotNullByDefault
    private final class ZipStreamingEntry {
        /// The normalized ZIP entry path text.
        private final String entryPath;

        /// The mutable pending ZIP attribute view.
        private final PendingZipEntryAttributeView attributes = new PendingZipEntryAttributeView(this);

        /// Whether this entry has already been committed.
        private boolean submitted;

        /// Creates a pending ZIP streaming entry.
        private ZipStreamingEntry(String entryPath) {
            this.entryPath = Objects.requireNonNull(entryPath, "entryPath");
        }

        /// Requires this entry to still be pending.
        private void ensurePending() {
            if (submitted || pendingEntry != this) {
                throw new IllegalStateException("ZIP streaming entry has already been committed");
            }
        }
    }

    /// Stores writable ZIP entry metadata before a streaming entry is committed.
    @NotNullByDefault
    private final class PendingZipEntryAttributeView implements ZipArkivoEntryAttributeView {
        /// The entry configured by this view.
        private final ZipStreamingEntry entry;

        /// The requested last modified time, or `null` when not configured.
        private @Nullable FileTime lastModifiedTime;

        /// The requested last access time, or `null` when not configured.
        private @Nullable FileTime lastAccessTime;

        /// The requested creation time, or `null` when not configured.
        private @Nullable FileTime creationTime;

        /// The requested ZIP compression method.
        private ZipMethod method = ZipMethod.deflated();

        /// The requested ZIP encryption method.
        private ZipEncryption encryption = ZipEncryption.none();

        /// The expected uncompressed size, or `UNKNOWN_SIZE` when not configured.
        private long uncompressedSize = ZipArkivoEntryAttributes.UNKNOWN_SIZE;

        /// The expected CRC-32 value, or `UNKNOWN_CRC32` when not configured.
        private long crc32 = ZipArkivoEntryAttributes.UNKNOWN_CRC32;

        /// The requested ZIP internal file attributes.
        private int internalAttributes;

        /// The requested ZIP external file attributes, or `UNKNOWN_EXTERNAL_ATTRIBUTES` when not configured.
        private long externalAttributes = UNKNOWN_EXTERNAL_ATTRIBUTES;

        /// The requested raw local file header extra data bytes.
        private byte @Unmodifiable [] localExtraData = new byte[0];

        /// The requested raw central directory extra data bytes.
        private byte @Unmodifiable [] centralDirectoryExtraData = new byte[0];

        /// The requested decoded ZIP entry comment, or `null` when not configured.
        private @Nullable String comment;

        /// The requested raw ZIP entry comment bytes, or `null` when not configured.
        private byte @Nullable @Unmodifiable [] rawComment;

        /// Creates a pending ZIP entry attribute view.
        private PendingZipEntryAttributeView(ZipStreamingEntry entry) {
            this.entry = Objects.requireNonNull(entry, "entry");
        }

        /// Reads the pending ZIP-specific entry attributes.
        @Override
        public ZipArkivoEntryAttributes readAttributes() {
            lock();
            try {
                return new PendingZipEntryAttributes(entry.entryPath, this);
            } finally {
                unlock();
            }
        }

        /// Sets entry timestamps.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) throws IOException {
            lock();
            try {
                entry.ensurePending();
                this.lastModifiedTime = lastModifiedTime;
                this.lastAccessTime = lastAccessTime;
                this.creationTime = createTime;
            } finally {
                unlock();
            }
        }

        /// Sets the ZIP compression method requested for the entry.
        @Override
        public void setMethod(ZipMethod method) throws IOException {
            lock();
            try {
                entry.ensurePending();
                this.method = Objects.requireNonNull(method, "method");
            } finally {
                unlock();
            }
        }

        /// Sets the ZIP encryption method requested for the entry.
        @Override
        public void setEncryption(ZipEncryption encryption) throws IOException {
            lock();
            try {
                entry.ensurePending();
                this.encryption = Objects.requireNonNull(encryption, "encryption");
            } finally {
                unlock();
            }
        }

        /// Sets the expected uncompressed size and CRC-32 value for entries that require them before writing.
        @Override
        public void setUncompressedSizeAndCrc32(long uncompressedSize, long crc32) throws IOException {
            if (uncompressedSize < 0) {
                throw new IllegalArgumentException("uncompressedSize must not be negative");
            }
            if (crc32 < 0 || crc32 > 0xffff_ffffL) {
                throw new IllegalArgumentException("crc32 must be an unsigned 32-bit value");
            }
            lock();
            try {
                entry.ensurePending();
                this.uncompressedSize = uncompressedSize;
                this.crc32 = crc32;
            } finally {
                unlock();
            }
        }

        /// Sets the ZIP internal file attributes.
        @Override
        public void setInternalAttributes(int internalAttributes) throws IOException {
            lock();
            try {
                entry.ensurePending();
                this.internalAttributes = internalAttributes;
            } finally {
                unlock();
            }
        }

        /// Sets the ZIP external file attributes.
        @Override
        public void setExternalAttributes(long externalAttributes) throws IOException {
            if (externalAttributes < 0 || externalAttributes > 0xffff_ffffL) {
                throw new IllegalArgumentException("externalAttributes must be an unsigned 32-bit value");
            }
            lock();
            try {
                entry.ensurePending();
                this.externalAttributes = externalAttributes;
            } finally {
                unlock();
            }
        }

        /// Sets the raw local file header extra data bytes.
        @Override
        public void setLocalExtraData(byte[] extraData) throws IOException {
            lock();
            try {
                entry.ensurePending();
                this.localExtraData = Objects.requireNonNull(extraData, "extraData").clone();
            } finally {
                unlock();
            }
        }

        /// Sets the raw central directory extra data bytes.
        @Override
        public void setCentralDirectoryExtraData(byte[] extraData) throws IOException {
            lock();
            try {
                entry.ensurePending();
                this.centralDirectoryExtraData = Objects.requireNonNull(extraData, "extraData").clone();
            } finally {
                unlock();
            }
        }

        /// Sets the decoded ZIP entry comment.
        @Override
        public void setComment(@Nullable String comment) throws IOException {
            lock();
            try {
                entry.ensurePending();
                this.comment = comment;
                this.rawComment = null;
            } finally {
                unlock();
            }
        }

        /// Sets the raw ZIP entry comment bytes.
        @Override
        public void setRawComment(byte @Nullable [] rawComment) throws IOException {
            lock();
            try {
                entry.ensurePending();
                this.rawComment = rawComment != null ? rawComment.clone() : null;
                this.comment = null;
            } finally {
                unlock();
            }
        }

        /// Requires the configured metadata to be supported for the current streaming directory writer.
        private void requireSupportedDirectory() {
            requireCommonSupportedMetadata();
            if (!method.equals(ZipMethod.deflated()) && !method.equals(ZipMethod.stored())) {
                throw new UnsupportedOperationException("Unsupported ZIP directory method: " + method);
            }
        }

        /// Requires the configured metadata to be supported for the current streaming file writer.
        private void requireSupportedFile() {
            requireCommonSupportedMetadata();
            if (!method.equals(ZipMethod.deflated())) {
                throw new UnsupportedOperationException("ZIP streaming writer currently supports only deflated file entries");
            }
            if (uncompressedSize != ZipArkivoEntryAttributes.UNKNOWN_SIZE
                    || crc32 != ZipArkivoEntryAttributes.UNKNOWN_CRC32) {
                throw new UnsupportedOperationException("ZIP streaming writer currently computes file size and CRC-32 while writing");
            }
        }

        /// Requires the configured metadata to be supported by the current streaming ZIP implementation.
        private void requireCommonSupportedMetadata() {
            if (!encryption.equals(ZipEncryption.none())) {
                throw new UnsupportedOperationException("ZIP streaming writer does not support encrypted entries yet");
            }
            if (lastModifiedTime != null || lastAccessTime != null || creationTime != null) {
                throw new UnsupportedOperationException("ZIP streaming writer does not support custom entry timestamps yet");
            }
            if (internalAttributes != 0 || externalAttributes != UNKNOWN_EXTERNAL_ATTRIBUTES) {
                throw new UnsupportedOperationException("ZIP streaming writer does not support custom entry attributes yet");
            }
            if (localExtraData.length != 0 || centralDirectoryExtraData.length != 0) {
                throw new UnsupportedOperationException("ZIP streaming writer does not support custom extra data yet");
            }
            if (comment != null || rawComment != null) {
                throw new UnsupportedOperationException("ZIP streaming writer does not support entry comments yet");
            }
        }
    }

    /// Exposes a snapshot of pending ZIP entry metadata.
    @NotNullByDefault
    private static final class PendingZipEntryAttributes implements ZipArkivoEntryAttributes {
        /// The decoded entry path text.
        private final String path;

        /// The raw encoded entry path bytes.
        private final byte @Unmodifiable [] rawPath;

        /// The requested last modified time, or `null` when not configured.
        private final @Nullable FileTime lastModifiedTime;

        /// The requested last access time, or `null` when not configured.
        private final @Nullable FileTime lastAccessTime;

        /// The requested creation time, or `null` when not configured.
        private final @Nullable FileTime creationTime;

        /// The requested ZIP compression method.
        private final ZipMethod method;

        /// The requested ZIP encryption method.
        private final ZipEncryption encryption;

        /// The expected uncompressed size, or `UNKNOWN_SIZE` when not configured.
        private final long uncompressedSize;

        /// The expected CRC-32 value, or `UNKNOWN_CRC32` when not configured.
        private final long crc32;

        /// The requested ZIP internal file attributes.
        private final int internalAttributes;

        /// The requested ZIP external file attributes, or `UNKNOWN_EXTERNAL_ATTRIBUTES` when not configured.
        private final long externalAttributes;

        /// The requested raw local file header extra data bytes.
        private final byte @Unmodifiable [] localExtraData;

        /// The requested raw central directory extra data bytes.
        private final byte @Unmodifiable [] centralDirectoryExtraData;

        /// The requested decoded ZIP entry comment, or `null` when not configured.
        private final @Nullable String comment;

        /// The requested raw ZIP entry comment bytes, or `null` when not configured.
        private final byte @Nullable @Unmodifiable [] rawComment;

        /// Creates a pending ZIP entry attributes snapshot.
        private PendingZipEntryAttributes(String path, PendingZipEntryAttributeView view) {
            this.path = Objects.requireNonNull(path, "path");
            this.rawPath = path.getBytes(StandardCharsets.UTF_8);
            this.lastModifiedTime = view.lastModifiedTime;
            this.lastAccessTime = view.lastAccessTime;
            this.creationTime = view.creationTime;
            this.method = view.method;
            this.encryption = view.encryption;
            this.uncompressedSize = view.uncompressedSize;
            this.crc32 = view.crc32;
            this.internalAttributes = view.internalAttributes;
            this.externalAttributes = view.externalAttributes;
            this.localExtraData = view.localExtraData.clone();
            this.centralDirectoryExtraData = view.centralDirectoryExtraData.clone();
            this.comment = view.comment;
            this.rawComment = view.rawComment != null ? view.rawComment.clone() : null;
        }

        /// Returns the raw encoded ZIP entry path bytes.
        @Override
        public byte @Unmodifiable [] rawPath() {
            return rawPath.clone();
        }

        /// Returns the decoded ZIP entry path text.
        @Override
        public String path() {
            return path;
        }

        /// Returns the compressed size stored in the ZIP metadata, or `UNKNOWN_SIZE` when it is not known.
        @Override
        public long compressedSize() {
            return UNKNOWN_SIZE;
        }

        /// Returns the CRC-32 value stored in the ZIP metadata, or `UNKNOWN_CRC32` when it is not known.
        @Override
        public long crc32() {
            return crc32;
        }

        /// Returns the general purpose bit flags stored for the ZIP entry.
        @Override
        public int generalPurposeFlags() {
            return 0;
        }

        /// Returns the ZIP version made by field.
        @Override
        public int versionMadeBy() {
            return ZipConstants.VERSION_NEEDED;
        }

        /// Returns the ZIP version needed to extract field.
        @Override
        public int versionNeededToExtract() {
            return ZipConstants.VERSION_NEEDED;
        }

        /// Returns the ZIP internal file attributes.
        @Override
        public int internalAttributes() {
            return internalAttributes;
        }

        /// Returns the ZIP external file attributes.
        @Override
        public long externalAttributes() {
            return externalAttributes != UNKNOWN_EXTERNAL_ATTRIBUTES ? externalAttributes : 0L;
        }

        /// Returns the ZIP compression method.
        @Override
        public ZipMethod method() {
            return method;
        }

        /// Returns the ZIP encryption method.
        @Override
        public ZipEncryption encryption() {
            return encryption;
        }

        /// Returns the raw local file header extra data bytes.
        @Override
        public byte @Unmodifiable [] localExtraData() {
            return localExtraData.clone();
        }

        /// Returns the raw central directory extra data bytes.
        @Override
        public byte @Unmodifiable [] centralDirectoryExtraData() {
            return centralDirectoryExtraData.clone();
        }

        /// Returns the raw ZIP entry comment bytes, or `null` when no comment is present.
        @Override
        public byte @Nullable @Unmodifiable [] rawComment() {
            return rawComment != null ? rawComment.clone() : null;
        }

        /// Returns the decoded ZIP entry comment, or `null` when no comment is present.
        @Override
        public @Nullable String comment() {
            return comment;
        }

        /// Returns the last modification time.
        @Override
        public FileTime lastModifiedTime() {
            FileTime time = lastModifiedTime;
            return time != null ? time : FileTime.fromMillis(0L);
        }

        /// Returns the last access time.
        @Override
        public FileTime lastAccessTime() {
            FileTime time = lastAccessTime;
            return time != null ? time : lastModifiedTime();
        }

        /// Returns the creation time.
        @Override
        public FileTime creationTime() {
            FileTime time = creationTime;
            return time != null ? time : lastModifiedTime();
        }

        /// Returns whether this pending entry is a regular file.
        @Override
        public boolean isRegularFile() {
            return true;
        }

        /// Returns whether this pending entry is a directory.
        @Override
        public boolean isDirectory() {
            return false;
        }

        /// Returns whether this pending entry is a symbolic link.
        @Override
        public boolean isSymbolicLink() {
            return false;
        }

        /// Returns whether this pending entry is another file type.
        @Override
        public boolean isOther() {
            return false;
        }

        /// Returns the expected uncompressed entry size.
        @Override
        public long size() {
            return uncompressedSize;
        }

        /// Returns no stable file key for a pending streaming entry.
        @Override
        public @Nullable Object fileKey() {
            return null;
        }
    }
}
