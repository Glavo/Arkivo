// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoStorageAccessSet;
import org.glavo.arkivo.internal.ArkivoPathMatchers;
import org.glavo.arkivo.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.zip.ZipArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileStoreAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/// Implements a forward-only ZIP archive file system for streaming writes.
@NotNullByDefault
public final class StreamingZipArkivoFileSystemImpl extends ZipArkivoFileSystem {
    /// The provider that created this ZIP file system.
    private final ZipArkivoFileSystemProvider provider;

    /// The archive path backing this file system.
    private final Path archivePath;

    /// The ZIP output stream that receives entries in storage order.
    private final ZipOutputStream output;

    /// The root path for this ZIP file system.
    private final ZipArkivoPath rootPath;

    /// The entry names already written to the stream.
    private final HashSet<String> writtenEntries = new HashSet<>();

    /// The currently open entry output stream, or `null` when no entry is active.
    private @Nullable EntryOutputStream currentEntryOutput;

    /// Whether this file system is open.
    private boolean open = true;

    /// Creates a streaming ZIP archive file system.
    public StreamingZipArkivoFileSystemImpl(
            ZipArkivoFileSystemProvider provider,
            Path archivePath,
            ZipArkivoFileSystemConfig config
    ) throws IOException {
        super(ArkivoStorageAccessSet.STREAM_WRITE, config.threadSafety());
        this.provider = Objects.requireNonNull(provider, "provider");
        this.archivePath = Objects.requireNonNull(archivePath, "archivePath");
        this.output = new ZipOutputStream(Files.newOutputStream(
                archivePath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        ));
        this.rootPath = ZipArkivoPath.root(this);
    }

    /// Returns the archive URI used by ZIP path URI conversion.
    public URI archiveUri() {
        return archivePath.toUri().normalize();
    }

    /// Returns the provider that created this ZIP file system.
    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    /// Closes this ZIP file system and finishes the output archive.
    @Override
    public synchronized void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        EntryOutputStream entryOutput = currentEntryOutput;
        if (entryOutput != null) {
            entryOutput.close();
        }
        output.close();
    }

    /// Returns whether this ZIP file system is open.
    @Override
    public synchronized boolean isOpen() {
        return open;
    }

    /// Returns whether this ZIP file system rejects write operations.
    @Override
    public boolean isReadOnly() {
        return false;
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
        return List.of(StreamingZipFileStore.INSTANCE);
    }

    /// Returns the supported file attribute view names.
    @Override
    public @Unmodifiable Set<String> supportedFileAttributeViews() {
        return Set.of("basic", "zip");
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

    /// Opens an output stream for the next ZIP entry.
    public synchronized OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        checkOpen();
        requireSupportedOutputOptions(options);
        String entryName = regularEntryName(path);
        requireNoActiveEntry();
        requireNewEntry(entryName);

        output.putNextEntry(new ZipEntry(entryName));
        EntryOutputStream entryOutput = new EntryOutputStream();
        currentEntryOutput = entryOutput;
        return entryOutput;
    }

    /// Creates a directory entry.
    public synchronized void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        checkOpen();
        Objects.requireNonNull(attributes, "attributes");
        if (attributes.length != 0) {
            throw new UnsupportedOperationException("ZIP streaming directory attributes are not supported");
        }

        String entryName = directoryEntryName(directory);
        if (entryName.isEmpty()) {
            return;
        }
        requireNoActiveEntry();
        requireNewEntry(entryName);
        output.putNextEntry(new ZipEntry(entryName));
        output.closeEntry();
    }

    /// Returns the number of bytes stored before the ZIP archive body.
    @Override
    public long preambleSize() {
        throw new UnsupportedOperationException("Streaming ZIP output does not expose a preamble");
    }

    /// Opens a read-only channel over the bytes stored before the ZIP archive body.
    @Override
    public SeekableByteChannel openPreambleChannel() {
        throw new UnsupportedOperationException("Streaming ZIP output does not expose a preamble");
    }

    /// Requires this file system to be open.
    private void checkOpen() {
        if (!open) {
            throw new ClosedFileSystemException();
        }
    }

    /// Requires no entry output stream to be active.
    private void requireNoActiveEntry() throws IOException {
        if (currentEntryOutput != null) {
            throw new IOException("A ZIP entry output stream is already open");
        }
    }

    /// Requires the entry name to be new.
    private void requireNewEntry(String entryName) throws IOException {
        if (!writtenEntries.add(entryName)) {
            throw new java.nio.file.FileAlreadyExistsException(entryName);
        }
    }

    /// Returns a regular file entry name for the given path.
    private String regularEntryName(Path path) {
        String entryName = entryName(path);
        if (entryName.isEmpty()) {
            throw new IllegalArgumentException("Cannot open the ZIP root as an entry output stream");
        }
        if (entryName.endsWith("/")) {
            throw new IllegalArgumentException("Regular ZIP entry names must not end with /");
        }
        return entryName;
    }

    /// Returns a directory entry name for the given path.
    private String directoryEntryName(Path path) {
        String entryName = entryName(path);
        return entryName.isEmpty() || entryName.endsWith("/") ? entryName : entryName + "/";
    }

    /// Returns a ZIP entry name for the given path.
    private String entryName(Path path) {
        if (path.getFileSystem() != this) {
            throw new java.nio.file.ProviderMismatchException();
        }

        String text = path.toAbsolutePath().normalize().toString();
        return "/".equals(text) ? "" : text.substring(1);
    }

    /// Requires output options to be compatible with forward-only ZIP writes.
    private static void requireSupportedOutputOptions(OpenOption... options) {
        for (OpenOption option : options) {
            Objects.requireNonNull(option, "option");
            if (option == StandardOpenOption.WRITE
                    || option == StandardOpenOption.CREATE
                    || option == StandardOpenOption.CREATE_NEW
                    || option == StandardOpenOption.TRUNCATE_EXISTING) {
                continue;
            }
            throw new UnsupportedOperationException("Unsupported ZIP streaming output option: " + option);
        }
    }

    /// Writes bytes to the current ZIP entry.
    private final class EntryOutputStream extends OutputStream {
        /// Whether this entry output stream is open.
        private boolean entryOpen = true;

        /// Creates an entry output stream.
        private EntryOutputStream() {
        }

        /// Writes one byte to the current ZIP entry.
        @Override
        public void write(int value) throws IOException {
            synchronized (StreamingZipArkivoFileSystemImpl.this) {
                ensureEntryOpen();
                output.write(value);
            }
        }

        /// Writes bytes to the current ZIP entry.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            synchronized (StreamingZipArkivoFileSystemImpl.this) {
                ensureEntryOpen();
                output.write(bytes, offset, length);
            }
        }

        /// Closes the current ZIP entry.
        @Override
        public void close() throws IOException {
            synchronized (StreamingZipArkivoFileSystemImpl.this) {
                if (!entryOpen) {
                    return;
                }
                entryOpen = false;
                output.closeEntry();
                currentEntryOutput = null;
            }
        }

        /// Requires this entry stream to be open.
        private void ensureEntryOpen() throws IOException {
            if (!entryOpen || currentEntryOutput != this) {
                throw new IOException("ZIP entry output stream is closed");
            }
            checkOpen();
        }
    }

    /// Describes the synthetic file store for streaming ZIP output.
    @NotNullByDefault
    private static final class StreamingZipFileStore extends FileStore {
        /// The shared streaming ZIP file store.
        private static final StreamingZipFileStore INSTANCE = new StreamingZipFileStore();

        /// Creates a streaming ZIP file store.
        private StreamingZipFileStore() {
        }

        /// Returns the file store name.
        @Override
        public String name() {
            return "zip-stream";
        }

        /// Returns the file store type.
        @Override
        public String type() {
            return "zip";
        }

        /// Returns whether this file store is read-only.
        @Override
        public boolean isReadOnly() {
            return false;
        }

        /// Returns an unknown total space value.
        @Override
        public long getTotalSpace() {
            return 0;
        }

        /// Returns an unknown usable space value.
        @Override
        public long getUsableSpace() {
            return 0;
        }

        /// Returns an unknown unallocated space value.
        @Override
        public long getUnallocatedSpace() {
            return 0;
        }

        /// Returns whether this file store supports the given attribute view.
        @Override
        public boolean supportsFileAttributeView(Class<? extends java.nio.file.attribute.FileAttributeView> type) {
            return false;
        }

        /// Returns whether this file store supports the given attribute view.
        @Override
        public boolean supportsFileAttributeView(String name) {
            return false;
        }

        /// Returns no file store attribute view.
        @Override
        public <V extends FileStoreAttributeView> @Nullable V getFileStoreAttributeView(Class<V> type) {
            Objects.requireNonNull(type, "type");
            return null;
        }

        /// Returns no file store attribute values.
        @Override
        public Object getAttribute(String attribute) {
            Objects.requireNonNull(attribute, "attribute");
            throw new UnsupportedOperationException("Streaming ZIP file store attributes are not supported");
        }
    }
}
