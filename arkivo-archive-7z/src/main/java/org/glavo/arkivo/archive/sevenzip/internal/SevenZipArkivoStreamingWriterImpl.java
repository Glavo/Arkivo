// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoEntryAttributeView;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.sevenzip.internal.SevenZipArkivoFileSystemProvider;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoStreamingWriter;
import org.glavo.arkivo.archive.sevenzip.SevenZipCompression;
import org.glavo.arkivo.archive.sevenzip.SevenZipCoderGraph;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilter;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilterChain;
import org.glavo.arkivo.archive.sevenzip.SevenZipPackedStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/// Implements the public forward-only 7z streaming writer API.
@NotNullByDefault
public final class SevenZipArkivoStreamingWriterImpl extends SevenZipArkivoStreamingWriter {
    /// The epoch time exposed for pending timestamp properties that are absent.
    private static final FileTime EPOCH = FileTime.fromMillis(0L);

    /// The default Unix symbolic-link mode stored in 7z Windows attributes.
    private static final int DEFAULT_SYMBOLIC_LINK_WINDOWS_ATTRIBUTES = 0120777 << 16;

    /// Identifies the requested type of a pending entry.
    private enum EntryType {
        /// A regular file entry.
        FILE,

        /// A directory entry.
        DIRECTORY,

        /// A symbolic link entry.
        SYMBOLIC_LINK
    }

    /// The writable 7z file system used to encode entries.
    private final SevenZipArkivoFileSystemImpl fileSystem;

    /// The optional state lock.
    private final @Nullable ReentrantLock lock;

    /// The pending entry that has not been committed, or `null` when no entry is pending.
    private @Nullable PendingEntry pendingEntry;

    /// The currently open file body, or `null` when no body is open.
    private @Nullable EntryBodyOutputStream currentBody;

    /// Creates a streaming writer over an initialized writable file system.
    private SevenZipArkivoStreamingWriterImpl(
            SevenZipArkivoFileSystemImpl fileSystem,
            SevenZipArkivoFileSystemConfig config
    ) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
        this.lock = config.threadSafety() == ArkivoFileSystemThreadSafety.NONE ? null : new ReentrantLock();
    }

    /// Creates a path-backed streaming writer.
    public static SevenZipArkivoStreamingWriterImpl create(
            Path path,
            SevenZipArkivoFileSystemConfig config
    ) throws IOException {
        return new SevenZipArkivoStreamingWriterImpl(
                new SevenZipArkivoFileSystemImpl(
                        SevenZipArkivoFileSystemProvider.instance(),
                        Objects.requireNonNull(path, "path"),
                        null,
                        config
                ),
                config
        );
    }

    /// Opens a streaming writer over an owned output stream.
    public static SevenZipArkivoStreamingWriterImpl open(
            OutputStream output,
            SevenZipArkivoFileSystemConfig config
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        return open(StreamChannelAdapters.writableChannel(output), config);
    }

    /// Opens a streaming writer over an owned writable channel.
    public static SevenZipArkivoStreamingWriterImpl open(
            WritableByteChannel output,
            SevenZipArkivoFileSystemConfig config
    ) throws IOException {
        Objects.requireNonNull(output, "output");
        SevenZipSingleVolumeTarget target = new SevenZipSingleVolumeTarget(output);
        try {
            return new SevenZipArkivoStreamingWriterImpl(
                    new SevenZipArkivoFileSystemImpl(
                            SevenZipArkivoFileSystemProvider.instance(),
                            target,
                            Long.MAX_VALUE,
                            config
                    ),
                    config
            );
        } catch (IOException | RuntimeException | Error exception) {
            try {
                output.close();
            } catch (IOException | RuntimeException | Error closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Opens a split streaming writer over a transactional volume target.
    public static SevenZipArkivoStreamingWriterImpl open(
            ArkivoVolumeTarget target,
            long splitSize,
            SevenZipArkivoFileSystemConfig config
    ) throws IOException {
        return new SevenZipArkivoStreamingWriterImpl(
                new SevenZipArkivoFileSystemImpl(
                        SevenZipArkivoFileSystemProvider.instance(),
                        Objects.requireNonNull(target, "target"),
                        splitSize,
                        config
                ),
                config
        );
    }

    /// Begins a pending regular file entry.
    @Override
    protected void beginFileEntry(String path) {
        beginEntry(path, EntryType.FILE, null);
    }

    /// Begins a pending directory entry.
    @Override
    protected void beginDirectoryEntry(String path) {
        beginEntry(path, EntryType.DIRECTORY, null);
    }

    /// Begins a pending symbolic link entry.
    @Override
    protected void beginSymbolicLinkEntry(String path, String target) {
        beginEntry(path, EntryType.SYMBOLIC_LINK, linkTargetText(target));
    }

    /// Begins a pending entry of the requested type.
    private void beginEntry(String path, EntryType type, @Nullable String linkTarget) {
        lock();
        try {
            ensureOpen();
            if (pendingEntry != null) {
                throw new IllegalStateException("A 7z streaming entry is already pending");
            }
            if (currentBody != null) {
                throw new IllegalStateException("A 7z streaming entry body is still open");
            }
            pendingEntry = new PendingEntry(entryPathText(path), type, linkTarget);
        } finally {
            unlock();
        }
    }

    /// Returns a mutable attribute view for the current pending entry.
    @Override
    protected <V extends FileAttributeView> @Nullable V currentAttributeView(Class<V> type) {
        Objects.requireNonNull(type, "type");
        lock();
        try {
            ensureOpen();
            PendingEntry entry = requirePendingEntry();
            if (type == BasicFileAttributeView.class || type == SevenZipArkivoEntryAttributeView.class) {
                return type.cast(entry.attributes);
            }
            if (type == PosixFileAttributeView.class) {
                return type.cast(entry.posixAttributes);
            }
            return null;
        } finally {
            unlock();
        }
    }

    /// Commits the current pending entry without leaving a body stream open.
    @Override
    protected void finishCurrentEntry() throws IOException {
        lock();
        try {
            ensureOpen();
            PendingEntry entry = requirePendingEntry();
            entry.ensurePending();
            Path path = fileSystem.getPath("/" + entry.path);
            SevenZipEntryWriteMetadata metadata = entry.attributes.metadata();
            switch (entry.type) {
                case FILE -> {
                    try (OutputStream ignored = fileSystem.newOutputStream(path, metadata)) {
                        // Closing the entry output stream emits an empty file.
                    }
                }
                case DIRECTORY -> fileSystem.createDirectory(path, metadata);
                case SYMBOLIC_LINK -> fileSystem.createSymbolicLink(
                        path,
                        fileSystem.getPath(Objects.requireNonNull(entry.linkTarget, "linkTarget")),
                        metadata
                );
            }
            entry.committed = true;
            pendingEntry = null;
        } finally {
            unlock();
        }
    }

    /// Opens a writable channel for the current pending file entry.
    @Override
    protected WritableByteChannel openCurrentChannel() throws IOException {
        return StreamChannelAdapters.writableChannel(openBodyStream());
    }

    /// Opens an output stream that commits the current pending file entry when closed.
    private OutputStream openBodyStream() throws IOException {
        lock();
        try {
            ensureOpen();
            PendingEntry entry = requirePendingEntry();
            entry.ensurePending();
            if (entry.type != EntryType.FILE) {
                throw new IllegalStateException("Only 7z file entries can open a body stream");
            }
            OutputStream output = fileSystem.newOutputStream(
                    fileSystem.getPath("/" + entry.path),
                    entry.attributes.metadata()
            );
            entry.committed = true;
            pendingEntry = null;
            EntryBodyOutputStream body = new EntryBodyOutputStream(output);
            currentBody = body;
            return body;
        } finally {
            unlock();
        }
    }

    /// Closes any active entry, finalizes the archive, publishes staged output, and closes owned output.
    @Override
    protected void closeWriter() throws IOException {
        lock();
        try {
            @Nullable Throwable failure = null;
            EntryBodyOutputStream body = currentBody;
            if (body != null) {
                try {
                    body.close();
                } catch (IOException | RuntimeException | Error exception) {
                    failure = exception;
                }
            }

            if (failure == null && pendingEntry != null && fileSystem.isOpen()) {
                try {
                    finishCurrentEntry();
                } catch (IOException | RuntimeException | Error exception) {
                    failure = exception;
                }
            }

            try {
                fileSystem.close();
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
            }

            throwFailure(failure);
        } finally {
            unlock();
        }
    }

    /// Requires the writer to remain open.
    private void ensureOpen() {
        if (!fileSystem.isOpen()) {
            throw new IllegalStateException("7z streaming writer is closed");
        }
    }

    /// Returns the current pending entry.
    private PendingEntry requirePendingEntry() {
        PendingEntry entry = pendingEntry;
        if (entry == null) {
            throw new IllegalStateException("No 7z streaming entry is pending");
        }
        return entry;
    }

    /// Acquires the state lock when synchronization is enabled.
    private void lock() {
        if (lock != null) {
            lock.lock();
        }
    }

    /// Releases the state lock when synchronization is enabled.
    private void unlock() {
        if (lock != null) {
            lock.unlock();
        }
    }

    /// Converts a logical entry path to normalized relative 7z path text.
    private static String entryPathText(String path) {
        Objects.requireNonNull(path, "path");
        String normalizedSeparators = path.replace('\\', '/');
        if (normalizedSeparators.startsWith("/")) {
            throw new IllegalArgumentException("7z streaming entry paths must be relative");
        }
        if (normalizedSeparators.length() >= 2 && normalizedSeparators.charAt(1) == ':') {
            throw new IllegalArgumentException("7z streaming entry paths must not contain drive roots");
        }

        StringBuilder builder = new StringBuilder();
        int start = 0;
        while (start <= normalizedSeparators.length()) {
            int end = normalizedSeparators.indexOf('/', start);
            if (end < 0) {
                end = normalizedSeparators.length();
            }
            String segment = normalizedSeparators.substring(start, end);
            if (!segment.isEmpty() && !".".equals(segment)) {
                if ("..".equals(segment)) {
                    throw new IllegalArgumentException("7z streaming entry paths must not contain ..");
                }
                if (!builder.isEmpty()) {
                    builder.append('/');
                }
                builder.append(segment);
            }
            start = end + 1;
        }
        if (builder.isEmpty()) {
            throw new IllegalArgumentException("7z streaming entry path must not be empty");
        }
        return builder.toString();
    }

    /// Normalizes symbolic link target text without resolving it.
    private static String linkTargetText(String target) {
        Objects.requireNonNull(target, "target");
        if (target.isEmpty()) {
            throw new IllegalArgumentException("7z streaming symbolic link target must not be empty");
        }
        return target.replace('\\', '/');
    }

    /// Adds a secondary failure to an existing primary failure.
    private static Throwable appendFailure(@Nullable Throwable failure, Throwable exception) {
        if (failure == null) {
            return exception;
        }
        if (failure != exception) {
            failure.addSuppressed(exception);
        }
        return failure;
    }

    /// Throws an accumulated close failure with its original category.
    private static void throwFailure(@Nullable Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error exception) {
            throw exception;
        }
    }

    /// Stores one configurable pending 7z entry.
    @NotNullByDefault
    private final class PendingEntry {
        /// The normalized relative entry path.
        private final String path;

        /// The requested entry type.
        private final EntryType type;

        /// The normalized symbolic link target, or `null` for non-links.
        private final @Nullable String linkTarget;

        /// The mutable 7z metadata view.
        private final PendingSevenZipEntryAttributeView attributes;

        /// The mutable POSIX metadata view.
        private final PendingPosixEntryAttributeView posixAttributes;

        /// Whether this entry has been committed.
        private boolean committed;

        /// Creates a pending entry.
        private PendingEntry(String path, EntryType type, @Nullable String linkTarget) {
            this.path = Objects.requireNonNull(path, "path");
            this.type = Objects.requireNonNull(type, "type");
            this.linkTarget = linkTarget;
            this.attributes = new PendingSevenZipEntryAttributeView(this);
            this.posixAttributes = new PendingPosixEntryAttributeView(this);
        }

        /// Requires this entry to remain configurable.
        private void ensurePending() {
            if (committed || pendingEntry != this) {
                throw new IllegalStateException("7z streaming entry has already been committed");
            }
        }
    }

    /// Stores writable 7z metadata before an entry is committed.
    @NotNullByDefault
    private final class PendingSevenZipEntryAttributeView implements SevenZipArkivoEntryAttributeView {
        /// The entry configured by this view.
        private final PendingEntry entry;

        /// The requested last modification time, or `null` when absent.
        private @Nullable FileTime lastModifiedTime;

        /// The requested last access time, or `null` when absent.
        private @Nullable FileTime lastAccessTime;

        /// The requested creation time, or `null` when absent.
        private @Nullable FileTime creationTime;

        /// The requested raw Windows attributes.
        private int windowsAttributes;

        /// The entry-specific compression, or `null` to use the writer default.
        private @Nullable SevenZipCompression compression;

        /// Whether this entry overrides the writer's default filter chain.
        private boolean filtersConfigured;

        /// The entry-specific filter chain, or null when inherited.
        private @Nullable SevenZipFilterChain filters;

        /// Creates a pending 7z metadata view.
        private PendingSevenZipEntryAttributeView(PendingEntry entry) {
            this.entry = Objects.requireNonNull(entry, "entry");
            this.windowsAttributes = entry.type == EntryType.SYMBOLIC_LINK
                    ? DEFAULT_SYMBOLIC_LINK_WINDOWS_ATTRIBUTES
                    : SevenZipArkivoEntryAttributes.UNKNOWN_WINDOWS_ATTRIBUTES;
        }

        /// Reads a snapshot of the pending entry metadata.
        @Override
        public SevenZipArkivoEntryAttributes readAttributes() {
            lock();
            try {
                return new PendingSevenZipEntryAttributes(entry, this);
            } finally {
                unlock();
            }
        }

        /// Sets the pending entry timestamps for non-null arguments.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) {
            lock();
            try {
                entry.ensurePending();
                if (lastModifiedTime != null) {
                    this.lastModifiedTime = lastModifiedTime;
                }
                if (lastAccessTime != null) {
                    this.lastAccessTime = lastAccessTime;
                }
                if (createTime != null) {
                    this.creationTime = createTime;
                }
            } finally {
                unlock();
            }
        }

        /// Sets or clears the raw Windows attributes property.
        @Override
        public void setWindowsAttributes(int windowsAttributes) {
            lock();
            try {
                entry.ensurePending();
                this.windowsAttributes = windowsAttributes;
            } finally {
                unlock();
            }
        }

        /// Sets the entry-specific compression override.
        @Override
        public void setCompression(SevenZipCompression compression) {
            Objects.requireNonNull(compression, "compression");
            lock();
            try {
                entry.ensurePending();
                this.compression = compression;
            } finally {
                unlock();
            }
        }

        /// Sets the entry-specific filter override.
        @Override
        public void setFilter(SevenZipFilter filter) {
            Objects.requireNonNull(filter, "filter");
            setFilters(SevenZipFilterChain.of(filter));
        }

        /// Sets the entry-specific filter-chain override.
        @Override
        public void setFilters(SevenZipFilterChain filters) {
            Objects.requireNonNull(filters, "filters");
            lock();
            try {
                entry.ensurePending();
                this.filters = filters;
                this.filtersConfigured = true;
            } finally {
                unlock();
            }
        }

        /// Disables the writer's default filters for this entry.
        @Override
        public void clearFilter() {
            setFilters(SevenZipFilterChain.EMPTY);
        }

        /// Sets POSIX permissions while preserving low Windows attribute bits.
        private void setPermissions(Set<PosixFilePermission> permissions) {
            Objects.requireNonNull(permissions, "permissions");
            lock();
            try {
                entry.ensurePending();
                int unixAttributes = switch (entry.type) {
                    case FILE -> SevenZipPosixSupport.regularFileWindowsAttributes(permissions);
                    case DIRECTORY -> SevenZipPosixSupport.directoryWindowsAttributes(permissions);
                    case SYMBOLIC_LINK -> SevenZipPosixSupport.symbolicLinkWindowsAttributes(permissions);
                };
                int windowsBits = windowsAttributes == SevenZipArkivoEntryAttributes.UNKNOWN_WINDOWS_ATTRIBUTES
                        ? 0
                        : windowsAttributes & 0xffff;
                windowsAttributes = windowsBits | unixAttributes;
            } finally {
                unlock();
            }
        }

        /// Returns immutable entry metadata for the encoder.
        private SevenZipEntryWriteMetadata metadata() {
            return new SevenZipEntryWriteMetadata(
                    lastModifiedTime,
                    lastAccessTime,
                    creationTime,
                    windowsAttributes,
                    compression,
                    filtersConfigured,
                    filters
            );
        }
    }

    /// Stores writable synthesized POSIX metadata before an entry is committed.
    @NotNullByDefault
    private final class PendingPosixEntryAttributeView implements PosixFileAttributeView {
        /// The entry configured by this view.
        private final PendingEntry entry;

        /// Creates a pending POSIX metadata view.
        private PendingPosixEntryAttributeView(PendingEntry entry) {
            this.entry = Objects.requireNonNull(entry, "entry");
        }

        /// Returns the POSIX view name.
        @Override
        public String name() {
            return "posix";
        }

        /// Reads a snapshot of pending POSIX metadata.
        @Override
        public PosixFileAttributes readAttributes() {
            lock();
            try {
                return new PendingSevenZipEntryAttributes(entry, entry.attributes);
            } finally {
                unlock();
            }
        }

        /// Returns the synthesized owner.
        @Override
        public UserPrincipal getOwner() {
            return SevenZipPosixSupport.owner();
        }

        /// Sets pending entry timestamps.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) {
            entry.attributes.setTimes(lastModifiedTime, lastAccessTime, createTime);
        }

        /// Accepts only the synthesized owner principal.
        @Override
        public void setOwner(UserPrincipal owner) throws UserPrincipalNotFoundException {
            Objects.requireNonNull(owner, "owner");
            lock();
            try {
                entry.ensurePending();
                SevenZipPrincipalSupport.requireDefaultOwner(owner);
            } finally {
                unlock();
            }
        }

        /// Accepts only the synthesized group principal.
        @Override
        public void setGroup(GroupPrincipal group) throws UserPrincipalNotFoundException {
            Objects.requireNonNull(group, "group");
            lock();
            try {
                entry.ensurePending();
                SevenZipPrincipalSupport.requireDefaultGroup(group);
            } finally {
                unlock();
            }
        }

        /// Sets POSIX permissions.
        @Override
        public void setPermissions(Set<PosixFilePermission> permissions) {
            entry.attributes.setPermissions(permissions);
        }
    }

    /// Exposes an immutable snapshot of pending entry attributes.
    @NotNullByDefault
    private static final class PendingSevenZipEntryAttributes
            implements SevenZipArkivoEntryAttributes, PosixFileAttributes {
        /// The relative entry path.
        private final String path;

        /// The entry type.
        private final EntryType type;

        /// The last modification time, or `null` when absent.
        private final @Nullable FileTime lastModifiedTime;

        /// The last access time, or `null` when absent.
        private final @Nullable FileTime lastAccessTime;

        /// The creation time, or `null` when absent.
        private final @Nullable FileTime creationTime;

        /// The raw Windows attributes.
        private final int windowsAttributes;

        /// Creates an immutable pending metadata snapshot.
        private PendingSevenZipEntryAttributes(PendingEntry entry, PendingSevenZipEntryAttributeView attributes) {
            this.path = entry.path;
            this.type = entry.type;
            this.lastModifiedTime = attributes.lastModifiedTime;
            this.lastAccessTime = attributes.lastAccessTime;
            this.creationTime = attributes.creationTime;
            this.windowsAttributes = attributes.windowsAttributes;
        }

        /// Returns the relative entry path.
        @Override
        public String path() {
            return path;
        }

        /// Returns no coder graph before the pending entry has been encoded.
        @Override
        public @Nullable SevenZipCoderGraph coderGraph() {
            return null;
        }

        /// Returns false before this entry has been encoded into a folder.
        @Override
        public boolean solid() {
            return false;
        }

        /// Returns the no-substream sentinel before this entry has been encoded.
        @Override
        public int substreamIndex() {
            return NO_SUBSTREAM_INDEX;
        }

        /// Returns zero before this entry has been encoded.
        @Override
        public int substreamCount() {
            return 0;
        }
        /// Returns the no-data sentinel before this entry has been encoded.
        @Override
        public long dataOffset() {
            return NO_DATA_OFFSET;
        }

        /// Returns zero before this entry has been encoded.
        @Override
        public long decodedOffset() {
            return 0L;
        }

        /// Returns zero before this entry has been encoded.
        @Override
        public long packedSize() {
            return 0L;
        }

        /// Returns the unknown CRC-32 sentinel before this entry has been encoded.
        @Override
        public long packedCrc32() {
            return UNKNOWN_CRC32;
        }

        /// Returns no packed streams before this entry has been encoded.
        @Override
        public @Unmodifiable List<SevenZipPackedStream> packedStreams() {
            return List.of();
        }

        /// Returns the unknown CRC-32 sentinel before this entry has been encoded.
        @Override
        public long crc32() {
            return UNKNOWN_CRC32;
        }
        /// Returns the raw Windows attributes.
        @Override
        public int windowsAttributes() {
            return windowsAttributes;
        }

        /// Returns the Unix mode encoded in the Windows attributes.
        @Override
        public int unixMode() {
            return SevenZipPosixSupport.unixMode(windowsAttributes);
        }

        /// Returns the last modification time or the epoch when absent.
        @Override
        public FileTime lastModifiedTime() {
            return lastModifiedTime != null ? lastModifiedTime : EPOCH;
        }

        /// Returns the last access time or the epoch when absent.
        @Override
        public FileTime lastAccessTime() {
            return lastAccessTime != null ? lastAccessTime : EPOCH;
        }

        /// Returns the creation time or the epoch when absent.
        @Override
        public FileTime creationTime() {
            return creationTime != null ? creationTime : EPOCH;
        }

        /// Returns whether this is a regular file.
        @Override
        public boolean isRegularFile() {
            return type == EntryType.FILE;
        }

        /// Returns whether this is a directory.
        @Override
        public boolean isDirectory() {
            return type == EntryType.DIRECTORY;
        }

        /// Returns whether this is a symbolic link.
        @Override
        public boolean isSymbolicLink() {
            return type == EntryType.SYMBOLIC_LINK;
        }

        /// Returns false because no other entry type is supported.
        @Override
        public boolean isOther() {
            return false;
        }

        /// Returns zero because pending entry body size is not known yet.
        @Override
        public long size() {
            return 0L;
        }

        /// Returns no file key.
        @Override
        public @Nullable Object fileKey() {
            return null;
        }

        /// Returns the synthesized owner.
        @Override
        public UserPrincipal owner() {
            return SevenZipPosixSupport.owner();
        }

        /// Returns the synthesized group.
        @Override
        public GroupPrincipal group() {
            return SevenZipPosixSupport.group();
        }

        /// Returns POSIX permissions decoded from the current attributes or writable defaults.
        @Override
        public @Unmodifiable Set<PosixFilePermission> permissions() {
            return SevenZipPosixSupport.permissions(type == EntryType.DIRECTORY, false, windowsAttributes);
        }
    }

    /// Delegates one active entry body and records its successful close.
    @NotNullByDefault
    private final class EntryBodyOutputStream extends OutputStream {
        /// The file-system entry output stream.
        private final OutputStream output;

        /// Whether this body has closed successfully.
        private boolean closed;

        /// Creates a body stream over the file-system entry output.
        private EntryBodyOutputStream(OutputStream output) {
            this.output = Objects.requireNonNull(output, "output");
        }

        /// Writes one entry byte.
        @Override
        public void write(int value) throws IOException {
            lock();
            try {
                ensureBodyOpen();
                output.write(value);
            } finally {
                unlock();
            }
        }

        /// Writes entry bytes.
        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            lock();
            try {
                ensureBodyOpen();
                output.write(buffer, offset, length);
            } finally {
                unlock();
            }
        }

        /// Flushes the entry output.
        @Override
        public void flush() throws IOException {
            lock();
            try {
                ensureBodyOpen();
                output.flush();
            } finally {
                unlock();
            }
        }

        /// Closes and commits the entry body.
        @Override
        public void close() throws IOException {
            lock();
            try {
                if (closed) {
                    return;
                }
                output.close();
                closed = true;
                if (currentBody == this) {
                    currentBody = null;
                }
            } finally {
                unlock();
            }
        }

        /// Requires this body to remain open.
        private void ensureBodyOpen() throws ClosedChannelException {
            if (closed) {
                throw new ClosedChannelException();
            }
        }
    }
}
