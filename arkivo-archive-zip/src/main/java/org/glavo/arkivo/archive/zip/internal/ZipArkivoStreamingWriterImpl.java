// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributeView;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemProvider;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingWriter;
import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.glavo.arkivo.archive.zip.ZipMethod;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/// Implements the public forward-only ZIP streaming writer API.
@NotNullByDefault
public final class ZipArkivoStreamingWriterImpl extends ZipArkivoStreamingWriter {
    /// The marker used when ZIP external attributes have not been configured.
    private static final long UNKNOWN_EXTERNAL_ATTRIBUTES = -1L;

    /// Identifies the type requested for a pending streaming entry.
    private enum EntryType {
        /// A regular file entry.
        FILE,

        /// A directory entry.
        DIRECTORY,

        /// A symbolic link entry.
        SYMBOLIC_LINK
    }

    /// The internal streaming ZIP file system used by the current writer implementation.
    private final StreamingZipArkivoFileSystemImpl fileSystem;

    /// The parsed ZIP streaming writer configuration.
    private final ZipArkivoFileSystemConfig config;

    /// The optional state lock.
    private final @Nullable ReentrantLock lock;

    /// The pending entry that has not yet been committed, or `null` when no pending entry exists.
    private @Nullable ZipStreamingEntry pendingEntry;

    /// Creates a ZIP streaming writer.
    private ZipArkivoStreamingWriterImpl(StreamingZipArkivoFileSystemImpl fileSystem, ZipArkivoFileSystemConfig config) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
        this.config = Objects.requireNonNull(config, "config");
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
        Objects.requireNonNull(output, "output");
        try {
            return new ZipArkivoStreamingWriterImpl(new StreamingZipArkivoFileSystemImpl(
                    ZipArkivoFileSystemProvider.instance(),
                    output,
                    config
            ), config);
        } catch (RuntimeException | Error exception) {
            closeAfterOpenFailure(output, exception);
            throw exception;
        }
    }

    /// Opens a streaming ZIP writer over an output stream.
    public static ZipArkivoStreamingWriterImpl open(OutputStream output, ZipArkivoFileSystemConfig config) {
        Objects.requireNonNull(output, "output");
        return open(StreamChannelAdapters.writableChannel(output), config);
    }

    /// Closes an owned output after setup fails without hiding the primary failure.
    private static void closeAfterOpenFailure(Closeable output, Throwable failure) {
        try {
            output.close();
        } catch (IOException | RuntimeException | Error exception) {
            if (failure != exception) {
                failure.addSuppressed(exception);
            }
        }
    }

    /// Opens a streaming ZIP writer over a transactional volume target.
    public static ZipArkivoStreamingWriterImpl open(
            ArkivoVolumeTarget target,
            long splitSize,
            ZipArkivoFileSystemConfig config
    ) throws IOException {
        return new ZipArkivoStreamingWriterImpl(new StreamingZipArkivoFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                Objects.requireNonNull(target, "target"),
                splitSize,
                config
        ), config);
    }

    /// Begins a pending regular file ZIP entry for the given logical archive path.
    @Override
    protected void beginFileEntry(String path) {
        beginEntry(path, EntryType.FILE, null);
    }

    /// Begins a pending directory ZIP entry for the given logical archive path.
    @Override
    protected void beginDirectoryEntry(String path) {
        beginEntry(path, EntryType.DIRECTORY, null);
    }

    /// Begins a pending symbolic link ZIP entry for the given logical archive path and target path text.
    @Override
    protected void beginSymbolicLinkEntry(String path, String target) {
        beginEntry(path, EntryType.SYMBOLIC_LINK, linkTargetText(target));
    }

    /// Begins a pending ZIP entry for the given logical archive path and type.
    private void beginEntry(String path, EntryType type, @Nullable String linkTarget) {
        lock();
        try {
            if (pendingEntry != null) {
                throw new IllegalStateException("A ZIP streaming entry is already pending");
            }
            pendingEntry = new ZipStreamingEntry(entryPathText(path), type, linkTarget);
        } finally {
            unlock();
        }
    }

    /// Returns an attribute view used to configure the current pending entry before it is committed.
    @Override
    protected <V extends FileAttributeView> @Nullable V currentAttributeView(Class<V> type) {
        Objects.requireNonNull(type, "type");
        lock();
        try {
            ZipStreamingEntry entry = requirePendingEntry();
            if (type == BasicFileAttributeView.class || type == ZipArkivoEntryAttributeView.class) {
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

    /// Commits the current pending entry without opening a body channel.
    @Override
    protected void finishCurrentEntry() throws IOException {
        lock();
        try {
            ZipStreamingEntry entry = requirePendingEntry();
            entry.ensurePending();
            switch (entry.type) {
                case FILE -> {
                    entry.attributes.requireSupportedFile();
                    try (OutputStream ignored = fileSystem.newOutputStream(
                            fileSystem.getPath("/" + entry.entryPath),
                            entry.attributes.metadata(entry, true)
                    )) {
                        // Closing the entry output stream writes an empty ZIP entry.
                    }
                }
                case DIRECTORY -> {
                    entry.attributes.requireSupportedDirectory();
                    fileSystem.createDirectory(
                            fileSystem.getPath("/" + entry.entryPath),
                            entry.attributes.metadata(entry, false)
                    );
                }
                case SYMBOLIC_LINK -> {
                    entry.attributes.requireSupportedSymbolicLink();
                    fileSystem.writeStoredEntry(
                            fileSystem.getPath("/" + entry.entryPath),
                            Objects.requireNonNull(entry.linkTarget, "linkTarget").getBytes(StandardCharsets.UTF_8),
                            entry.attributes.metadata(entry, false)
                    );
                }
            }
            entry.submitted = true;
            pendingEntry = null;
        } finally {
            unlock();
        }
    }

    /// Opens a writable channel for the current pending entry and commits its metadata.
    @Override
    protected WritableByteChannel openCurrentChannel() throws IOException {
        return StreamChannelAdapters.writableChannel(openBodyStream());
    }

    /// Opens an output stream for the current pending entry and commits its metadata.
    private OutputStream openBodyStream() throws IOException {
        lock();
        try {
            ZipStreamingEntry entry = requirePendingEntry();
            entry.ensurePending();
            if (entry.type != EntryType.FILE) {
                throw new IllegalStateException("Only ZIP file entries can open a body channel");
            }
            entry.attributes.requireSupportedFile();
            OutputStream output = fileSystem.newOutputStream(
                    fileSystem.getPath("/" + entry.entryPath),
                    entry.attributes.metadata(entry, false)
            );
            entry.submitted = true;
            pendingEntry = null;
            return output;
        } finally {
            unlock();
        }
    }

    /// Closes this streaming writer and finishes the ZIP stream.
    @Override
    protected void closeWriter() throws IOException {
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

    /// Returns a normalized symbolic link target path text.
    private static String linkTargetText(String target) {
        Objects.requireNonNull(target, "target");
        if (target.isEmpty()) {
            throw new IllegalArgumentException("ZIP streaming symbolic link target must not be empty");
        }
        return target.replace('\\', '/');
    }

    /// Implements one pending ZIP streaming entry.
    @NotNullByDefault
    private final class ZipStreamingEntry {
        /// The normalized ZIP entry path text.
        private final String entryPath;

        /// The requested entry type.
        private final EntryType type;

        /// The normalized symbolic link target path text, or `null` when this entry is not a symbolic link.
        private final @Nullable String linkTarget;

        /// The mutable pending ZIP attribute view.
        private final PendingZipEntryAttributeView attributes = new PendingZipEntryAttributeView(this);

        /// The mutable pending POSIX attribute view.
        private final PendingPosixEntryAttributeView posixAttributes = new PendingPosixEntryAttributeView(this);

        /// Whether this entry has already been committed.
        private boolean submitted;

        /// Creates a pending ZIP streaming entry.
        private ZipStreamingEntry(String entryPath, EntryType type, @Nullable String linkTarget) {
            this.entryPath = Objects.requireNonNull(entryPath, "entryPath");
            this.type = Objects.requireNonNull(type, "type");
            this.linkTarget = linkTarget;
        }

        /// Requires this entry to still be pending.
        private void ensurePending() {
            if (submitted || pendingEntry != this) {
                throw new IllegalStateException("ZIP streaming entry has already been committed");
            }
        }
    }

    /// Stores writable POSIX entry metadata before a streaming entry is committed.
    @NotNullByDefault
    private final class PendingPosixEntryAttributeView implements PosixFileAttributeView {
        /// The entry configured by this view.
        private final ZipStreamingEntry entry;

        /// The requested POSIX permissions, or `null` when defaults are used.
        private @Nullable Set<PosixFilePermission> permissions;

        /// Creates a pending POSIX entry attribute view.
        private PendingPosixEntryAttributeView(ZipStreamingEntry entry) {
            this.entry = Objects.requireNonNull(entry, "entry");
        }

        /// Returns the attribute view name.
        @Override
        public String name() {
            return "posix";
        }

        /// Reads the pending POSIX entry attributes.
        @Override
        public PosixFileAttributes readAttributes() {
            return new PendingPosixEntryAttributes(entry, permissions);
        }

        /// Returns the synthesized owner.
        @Override
        public UserPrincipal getOwner() {
            return ZipPosixSupport.DEFAULT_OWNER;
        }

        /// Sets entry timestamps.
        @Override
        public void setTimes(
                @Nullable FileTime lastModifiedTime,
                @Nullable FileTime lastAccessTime,
                @Nullable FileTime createTime
        ) throws IOException {
            entry.attributes.setTimes(lastModifiedTime, lastAccessTime, createTime);
        }

        /// Accepts the synthesized file owner.
        @Override
        public void setOwner(UserPrincipal owner) throws UserPrincipalNotFoundException {
            Objects.requireNonNull(owner, "owner");
            entry.ensurePending();
            ZipPosixSupport.requireDefaultOwner(owner);
        }

        /// Accepts the synthesized file group.
        @Override
        public void setGroup(GroupPrincipal group) throws UserPrincipalNotFoundException {
            Objects.requireNonNull(group, "group");
            entry.ensurePending();
            ZipPosixSupport.requireDefaultGroup(group);
        }

        /// Sets POSIX permissions.
        @Override
        public void setPermissions(Set<PosixFilePermission> permissions) {
            Objects.requireNonNull(permissions, "permissions");
            entry.ensurePending();
            this.permissions = Set.copyOf(permissions);
        }
    }

    /// Exposes a snapshot of pending synthesized POSIX entry metadata.
    @NotNullByDefault
    private static final class PendingPosixEntryAttributes implements PosixFileAttributes {
        /// The pending ZIP entry.
        private final ZipStreamingEntry entry;

        /// The configured permissions, or `null` when defaults are used.
        private final @Nullable Set<PosixFilePermission> permissions;

        /// Creates a pending POSIX attributes snapshot.
        private PendingPosixEntryAttributes(ZipStreamingEntry entry, @Nullable Set<PosixFilePermission> permissions) {
            this.entry = Objects.requireNonNull(entry, "entry");
            this.permissions = permissions != null ? Set.copyOf(permissions) : null;
        }

        /// Returns the last modification time.
        @Override
        public FileTime lastModifiedTime() {
            return entry.attributes.readAttributes().lastModifiedTime();
        }

        /// Returns the last access time.
        @Override
        public FileTime lastAccessTime() {
            return entry.attributes.readAttributes().lastAccessTime();
        }

        /// Returns the creation time.
        @Override
        public FileTime creationTime() {
            return entry.attributes.readAttributes().creationTime();
        }

        /// Returns whether this pending entry is a regular file.
        @Override
        public boolean isRegularFile() {
            return entry.type == EntryType.FILE;
        }

        /// Returns whether this pending entry is a directory.
        @Override
        public boolean isDirectory() {
            return entry.type == EntryType.DIRECTORY;
        }

        /// Returns whether this pending entry is a symbolic link.
        @Override
        public boolean isSymbolicLink() {
            return entry.type == EntryType.SYMBOLIC_LINK;
        }

        /// Returns whether this pending entry is another file type.
        @Override
        public boolean isOther() {
            return false;
        }

        /// Returns the expected uncompressed entry size.
        @Override
        public long size() {
            return entry.attributes.readAttributes().size();
        }

        /// Returns no stable file key for a pending streaming entry.
        @Override
        public @Nullable Object fileKey() {
            return null;
        }

        /// Returns the synthesized owner.
        @Override
        public UserPrincipal owner() {
            return ZipPosixSupport.DEFAULT_OWNER;
        }

        /// Returns the synthesized group.
        @Override
        public GroupPrincipal group() {
            return ZipPosixSupport.DEFAULT_GROUP;
        }

        /// Returns configured or synthesized permissions.
        @Override
        public @Unmodifiable Set<PosixFilePermission> permissions() {
            Set<PosixFilePermission> configuredPermissions = permissions;
            return configuredPermissions != null
                    ? configuredPermissions
                    : ZipPosixSupport.defaultPermissions(entry.type == EntryType.DIRECTORY);
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

        /// The requested ZIP compression method, or `null` when the entry type default is used.
        private @Nullable ZipMethod method;

        /// The requested ZIP encryption method.
        private ZipEncryption encryption = config.defaultEncryption();

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

        /// Sets POSIX permissions.
        @Override
        public void setPermissions(Set<PosixFilePermission> permissions) {
            entry.posixAttributes.setPermissions(permissions);
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

        /// Sets raw local file header extra data bytes containing complete ZIP extra field records.
        @Override
        public void setLocalExtraData(byte[] extraData) throws IOException {
            byte[] copy = Objects.requireNonNull(extraData, "extraData").clone();
            lock();
            try {
                entry.ensurePending();
                ZipExtraFields.validate(copy);
                this.localExtraData = copy;
            } finally {
                unlock();
            }
        }

        /// Sets raw central directory extra data bytes containing complete ZIP extra field records.
        @Override
        public void setCentralDirectoryExtraData(byte[] extraData) throws IOException {
            byte[] copy = Objects.requireNonNull(extraData, "extraData").clone();
            lock();
            try {
                entry.ensurePending();
                ZipExtraFields.validate(copy);
                this.centralDirectoryExtraData = copy;
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
            } finally {
                unlock();
            }
        }

        /// Requires the configured metadata to be supported for the current streaming directory writer.
        private void requireSupportedDirectory() {
            requireCommonSupportedMetadata();
            if (!method().equals(ZipMethod.stored())) {
                throw new UnsupportedOperationException("ZIP directory entries must use the stored method");
            }
        }

        /// Requires the configured metadata to be supported for the current streaming symbolic link writer.
        private void requireSupportedSymbolicLink() {
            requireCommonSupportedMetadata();
            if (!method().equals(ZipMethod.stored())) {
                throw new UnsupportedOperationException("ZIP symbolic link entries must use the stored method");
            }
        }

        /// Requires the configured metadata to be supported for the current streaming file writer.
        private void requireSupportedFile() {
            requireCommonSupportedMetadata();
        }

        /// Requires the configured metadata to be supported by the current streaming ZIP implementation.
        private void requireCommonSupportedMetadata() {
            ZipEncryption effectiveEncryption = encryption();
            if (!effectiveEncryption.equals(ZipEncryption.none())
                    && !effectiveEncryption.equals(ZipEncryption.traditional())
                    && !ZipAesExtraField.isAesEncryption(effectiveEncryption)) {
                throw new UnsupportedOperationException("Unsupported ZIP encryption method: " + effectiveEncryption);
            }
        }

        /// Returns the effective ZIP compression method.
        private ZipMethod method() {
            ZipMethod configuredMethod = method;
            if (configuredMethod != null) {
                return configuredMethod;
            }
            return entry.type == EntryType.FILE ? ZipMethod.deflated() : ZipMethod.stored();
        }

        /// Returns the effective ZIP encryption method.
        private ZipEncryption encryption() {
            return entry.type == EntryType.DIRECTORY ? ZipEncryption.none() : encryption;
        }

        /// Returns write metadata for the pending entry.
        private StreamingZipArkivoFileSystemImpl.EntryMetadata metadata(ZipStreamingEntry entry, boolean emptyFile) {
            ZipMethod effectiveMethod = method();
            ZipEncryption effectiveEncryption = encryption();
            long expectedSize = uncompressedSize;
            long expectedCrc32 = crc32;
            if (emptyFile && effectiveMethod.equals(ZipMethod.stored())) {
                expectedSize = 0;
                expectedCrc32 = 0;
            }
            return new StreamingZipArkivoFileSystemImpl.EntryMetadata(
                    effectiveMethod.id(),
                    effectiveEncryption,
                    lastModifiedTime,
                    versionMadeBy(entry),
                    internalAttributes,
                    externalAttributes(entry),
                    expectedSize,
                    expectedCrc32,
                    localExtraData,
                    centralDirectoryExtraData,
                    rawComment
            );
        }

        /// Returns the ZIP version made by field to write for this entry.
        private int versionMadeBy(ZipStreamingEntry entry) {
            return needsUnixExternalAttributes(entry) ? ZipPosixSupport.UNIX_VERSION_MADE_BY : ZipConstants.VERSION_NEEDED;
        }

        /// Returns the ZIP external file attributes to write for this entry.
        private long externalAttributes(ZipStreamingEntry entry) {
            if (externalAttributes != UNKNOWN_EXTERNAL_ATTRIBUTES) {
                return externalAttributes;
            }
            Set<PosixFilePermission> permissions = entry.posixAttributes.permissions;
            if (entry.type == EntryType.SYMBOLIC_LINK) {
                return ZipPosixSupport.symbolicLinkExternalAttributes(permissions);
            }
            if (permissions != null) {
                return ZipPosixSupport.externalAttributes(permissions, entry.type == EntryType.DIRECTORY);
            }
            return entry.type == EntryType.DIRECTORY ? 0x10L : 0L;
        }

        /// Returns whether this entry needs Unix external attributes.
        private boolean needsUnixExternalAttributes(ZipStreamingEntry entry) {
            return entry.type == EntryType.SYMBOLIC_LINK || entry.posixAttributes.permissions != null;
        }
    }

    /// Exposes a snapshot of pending ZIP entry metadata.
    @NotNullByDefault
    private static final class PendingZipEntryAttributes implements ZipArkivoEntryAttributes {
        /// The decoded entry path text.
        private final String path;

        /// The raw encoded entry path bytes.
        private final byte @Unmodifiable [] rawPath;

        /// The pending entry type.
        private final EntryType type;

        /// The requested last modified time, or `null` when not configured.
        private final @Nullable FileTime lastModifiedTime;

        /// The requested last access time, or `null` when not configured.
        private final @Nullable FileTime lastAccessTime;

        /// The requested creation time, or `null` when not configured.
        private final @Nullable FileTime creationTime;

        /// The effective ZIP compression method.
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

        /// The requested POSIX permissions, or `null` when defaults are used.
        private final @Nullable Set<PosixFilePermission> permissions;

        /// The requested raw local file header extra data bytes.
        private final byte @Unmodifiable [] localExtraData;

        /// The requested raw central directory extra data bytes.
        private final byte @Unmodifiable [] centralDirectoryExtraData;

        /// The requested raw ZIP entry comment bytes, or `null` when not configured.
        private final byte @Nullable @Unmodifiable [] rawComment;

        /// Creates a pending ZIP entry attributes snapshot.
        private PendingZipEntryAttributes(String path, PendingZipEntryAttributeView view) {
            this.path = Objects.requireNonNull(path, "path");
            this.rawPath = path.getBytes(StandardCharsets.UTF_8);
            this.type = view.entry.type;
            this.lastModifiedTime = view.lastModifiedTime;
            this.lastAccessTime = view.lastAccessTime;
            this.creationTime = view.creationTime;
            this.method = view.method();
            this.encryption = view.encryption();
            this.uncompressedSize = view.uncompressedSize;
            this.crc32 = view.crc32;
            this.internalAttributes = view.internalAttributes;
            this.externalAttributes = view.externalAttributes;
            this.permissions = view.entry.posixAttributes.permissions != null
                    ? Set.copyOf(view.entry.posixAttributes.permissions)
                    : null;
            this.localExtraData = view.localExtraData.clone();
            this.centralDirectoryExtraData = view.centralDirectoryExtraData.clone();
            this.rawComment = view.rawComment != null ? view.rawComment.clone() : null;
        }

        /// Returns a copy of the raw encoded ZIP entry path bytes.
        @Override
        public byte[] rawPath() {
            return rawPath.clone();
        }

        /// Returns the decoded ZIP entry path text.
        @Override
        public String path() {
            return path;
        }

        /// Returns the decoded ZIP entry comment text, or `null` when no comment is present.
        @Override
        public @Nullable String comment() {
            byte[] comment = rawComment;
            return comment != null ? new String(comment, StandardCharsets.UTF_8) : null;
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
            int flags = ZipConstants.UTF8_FLAG;
            if (method.id() == ZipMethod.LZMA_ID) {
                flags |= ZipConstants.LZMA_EOS_MARKER_FLAG;
            }
            if (!encryption.equals(ZipEncryption.none())) {
                flags |= ZipConstants.ENCRYPTED_FLAG;
            }
            return flags;
        }

        /// Returns the ZIP version made by field.
        @Override
        public int versionMadeBy() {
            return permissions != null ? ZipPosixSupport.UNIX_VERSION_MADE_BY : ZipConstants.VERSION_NEEDED;
        }

        /// Returns the ZIP version needed to extract field.
        @Override
        public int versionNeededToExtract() {
            if (method.id() == ZipMethod.LZMA_ID) {
                return ZipConstants.LZMA_VERSION_NEEDED;
            }
            if (method.id() == ZipMethod.DEFLATE64_ID) {
                return ZipConstants.DEFLATE64_VERSION_NEEDED;
            }
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
            Set<PosixFilePermission> configuredPermissions = permissions;
            if (externalAttributes != UNKNOWN_EXTERNAL_ATTRIBUTES) {
                return externalAttributes;
            }
            if (configuredPermissions != null) {
                return ZipPosixSupport.externalAttributes(configuredPermissions, type == EntryType.DIRECTORY);
            }
            return type == EntryType.DIRECTORY ? 0x10L : 0L;
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

        /// Returns a copy of the raw local file header extra data bytes.
        @Override
        public byte[] localExtraData() {
            return localExtraData.clone();
        }

        /// Returns a copy of the raw central directory extra data bytes.
        @Override
        public byte[] centralDirectoryExtraData() {
            return centralDirectoryExtraData.clone();
        }

        /// Returns a copy of the raw ZIP entry comment bytes, or `null` when no comment is present.
        @Override
        public byte @Nullable [] rawComment() {
            return rawComment != null ? rawComment.clone() : null;
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
            return type == EntryType.FILE;
        }

        /// Returns whether this pending entry is a directory.
        @Override
        public boolean isDirectory() {
            return type == EntryType.DIRECTORY;
        }

        /// Returns whether this pending entry is a symbolic link.
        @Override
        public boolean isSymbolicLink() {
            return type == EntryType.SYMBOLIC_LINK;
        }

        /// Returns whether this pending entry is another file type.
        @Override
        public boolean isOther() {
            return false;
        }

        /// Returns the expected uncompressed entry size.
        @Override
        public long size() {
            return type == EntryType.FILE ? uncompressedSize : 0L;
        }

        /// Returns no stable file key for a pending streaming entry.
        @Override
        public @Nullable Object fileKey() {
            return null;
        }

        /// Returns the synthesized owner.
        @Override
        public UserPrincipal owner() {
            return ZipPosixSupport.DEFAULT_OWNER;
        }

        /// Returns the synthesized group.
        @Override
        public GroupPrincipal group() {
            return ZipPosixSupport.DEFAULT_GROUP;
        }

        /// Returns configured or synthesized POSIX permissions.
        @Override
        public @Unmodifiable Set<PosixFilePermission> permissions() {
            Set<PosixFilePermission> configuredPermissions = permissions;
            return configuredPermissions != null
                    ? configuredPermissions
                    : ZipPosixSupport.defaultPermissions(type == EntryType.DIRECTORY);
        }
    }
}
