// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.internal.ArkivoPathMatchers;
import org.glavo.arkivo.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.zip.ZipArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.glavo.arkivo.zip.internal.ZipConstants.CENTRAL_DIRECTORY_HEADER_SIGNATURE;
import static org.glavo.arkivo.zip.internal.ZipConstants.DATA_DESCRIPTOR_FLAG;
import static org.glavo.arkivo.zip.internal.ZipConstants.DATA_DESCRIPTOR_SIGNATURE;
import static org.glavo.arkivo.zip.internal.ZipConstants.DEFLATED_METHOD;
import static org.glavo.arkivo.zip.internal.ZipConstants.DOS_DATE_1980_01_01;
import static org.glavo.arkivo.zip.internal.ZipConstants.END_OF_CENTRAL_DIRECTORY_SIGNATURE;
import static org.glavo.arkivo.zip.internal.ZipConstants.LOCAL_FILE_HEADER_SIGNATURE;
import static org.glavo.arkivo.zip.internal.ZipConstants.STORED_METHOD;
import static org.glavo.arkivo.zip.internal.ZipConstants.UTF8_FLAG;
import static org.glavo.arkivo.zip.internal.ZipConstants.VERSION_NEEDED;
import static org.glavo.arkivo.zip.internal.ZipLittleEndian.requireUInt16;
import static org.glavo.arkivo.zip.internal.ZipLittleEndian.requireUInt32;
import static org.glavo.arkivo.zip.internal.ZipLittleEndian.writeInt;
import static org.glavo.arkivo.zip.internal.ZipLittleEndian.writeShort;

/// Implements a forward-only ZIP archive file system for streaming writes.
@NotNullByDefault
public final class StreamingZipArkivoFileSystemImpl extends ZipArkivoFileSystem {
    /// The provider that created this ZIP file system.
    private final ZipArkivoFileSystemProvider provider;

    /// The archive path backing this file system, or `null` when writing to an output stream.
    private final @Nullable Path archivePath;

    /// The output stream that receives ZIP bytes.
    private final CountingOutputStream output;

    /// The optional state lock.
    private final @Nullable ReentrantLock lock;

    /// The root path for this ZIP file system.
    private final ZipArkivoPath rootPath;

    /// The entry names already written to the stream.
    private final HashSet<String> writtenEntries = new HashSet<>();

    /// The central directory entries to write when the file system closes.
    private final ArrayList<CentralEntry> centralEntries = new ArrayList<>();

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
        super(config.threadSafety());
        this.provider = Objects.requireNonNull(provider, "provider");
        this.archivePath = Objects.requireNonNull(archivePath, "archivePath");
        this.lock = ZipLocks.create(config.threadSafety());
        this.output = new CountingOutputStream(Files.newOutputStream(
                archivePath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        ));
        this.rootPath = ZipArkivoPath.root(this);
    }

    /// Creates a streaming ZIP archive file system over an output stream.
    public StreamingZipArkivoFileSystemImpl(
            ZipArkivoFileSystemProvider provider,
            OutputStream output,
            ZipArkivoFileSystemConfig config
    ) {
        super(config.threadSafety());
        this.provider = Objects.requireNonNull(provider, "provider");
        this.archivePath = null;
        this.lock = ZipLocks.create(config.threadSafety());
        this.output = new CountingOutputStream(Objects.requireNonNull(output, "output"));
        this.rootPath = ZipArkivoPath.root(this);
    }

    /// Returns the archive URI used by ZIP path URI conversion.
    public URI archiveUri() {
        Path path = archivePath;
        if (path == null) {
            throw new UnsupportedOperationException("Streaming ZIP output paths backed by output streams cannot be converted to URIs");
        }
        return path.toUri().normalize();
    }

    /// Returns the provider that created this ZIP file system.
    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    /// Closes this ZIP file system and finishes the output archive.
    @Override
    public void close() throws IOException {
        lock();
        try {
            if (!open) {
                return;
            }
            open = false;
            EntryOutputStream entryOutput = currentEntryOutput;
            if (entryOutput != null) {
                entryOutput.close();
            }
            writeCentralDirectory();
            output.close();
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
        return List.of(StreamingZipFileStore.WRITABLE);
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
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        lock();
        try {
            checkOpen();
            requireSupportedOutputOptions(options);
            String entryName = regularEntryName(path);
            requireNoActiveEntry();
            requireNewEntry(entryName);

            byte[] rawName = rawEntryName(entryName);
            long localHeaderOffset = output.position();
            writeLocalHeader(
                    rawName,
                    DATA_DESCRIPTOR_FLAG | UTF8_FLAG,
                    DEFLATED_METHOD,
                    0,
                    0,
                    0
            );
            EntryOutputStream entryOutput = new EntryOutputStream(entryName, rawName, localHeaderOffset, output.position());
            currentEntryOutput = entryOutput;
            return entryOutput;
        } finally {
            unlock();
        }
    }

    /// Creates a directory entry.
    public void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        lock();
        try {
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
            byte[] rawName = rawEntryName(entryName);
            long localHeaderOffset = output.position();
            writeLocalHeader(rawName, UTF8_FLAG, STORED_METHOD, 0, 0, 0);
            centralEntries.add(new CentralEntry(
                    entryName,
                    rawName,
                    UTF8_FLAG,
                    STORED_METHOD,
                    0,
                    0,
                    0,
                    localHeaderOffset,
                    true
            ));
        } finally {
            unlock();
        }
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

    /// Writes the local file header for an entry.
    private void writeLocalHeader(
            byte[] rawName,
            int flags,
            int method,
            long crc32,
            long compressedSize,
            long uncompressedSize
    ) throws IOException {
        writeInt(output, LOCAL_FILE_HEADER_SIGNATURE);
        writeShort(output, VERSION_NEEDED);
        writeShort(output, flags);
        writeShort(output, method);
        writeShort(output, 0);
        writeShort(output, DOS_DATE_1980_01_01);
        writeInt(output, crc32);
        writeInt(output, compressedSize);
        writeInt(output, uncompressedSize);
        writeShort(output, rawName.length);
        writeShort(output, 0);
        output.write(rawName);
    }

    /// Writes the data descriptor for a streamed entry.
    private void writeDataDescriptor(long crc32, long compressedSize, long uncompressedSize) throws IOException {
        writeInt(output, DATA_DESCRIPTOR_SIGNATURE);
        writeInt(output, crc32);
        writeInt(output, compressedSize);
        writeInt(output, uncompressedSize);
    }

    /// Writes the central directory and end record.
    private void writeCentralDirectory() throws IOException {
        long centralDirectoryOffset = output.position();
        for (CentralEntry entry : centralEntries) {
            writeCentralDirectoryEntry(entry);
        }
        long centralDirectorySize = output.position() - centralDirectoryOffset;
        requireUInt16(centralEntries.size(), "central directory entry count");
        requireUInt32(centralDirectorySize, "central directory size");
        requireUInt32(centralDirectoryOffset, "central directory offset");

        writeInt(output, END_OF_CENTRAL_DIRECTORY_SIGNATURE);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, centralEntries.size());
        writeShort(output, centralEntries.size());
        writeInt(output, centralDirectorySize);
        writeInt(output, centralDirectoryOffset);
        writeShort(output, 0);
    }

    /// Writes one central directory entry.
    private void writeCentralDirectoryEntry(CentralEntry entry) throws IOException {
        requireUInt32(entry.compressedSize, "compressed size");
        requireUInt32(entry.uncompressedSize, "uncompressed size");
        requireUInt32(entry.localHeaderOffset, "local header offset");

        writeInt(output, CENTRAL_DIRECTORY_HEADER_SIGNATURE);
        writeShort(output, VERSION_NEEDED);
        writeShort(output, VERSION_NEEDED);
        writeShort(output, entry.flags);
        writeShort(output, entry.method);
        writeShort(output, 0);
        writeShort(output, DOS_DATE_1980_01_01);
        writeInt(output, entry.crc32);
        writeInt(output, entry.compressedSize);
        writeInt(output, entry.uncompressedSize);
        writeShort(output, entry.rawName.length);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, 0);
        writeInt(output, entry.directory ? 0x10 : 0);
        writeInt(output, entry.localHeaderOffset);
        output.write(entry.rawName);
    }

    /// Returns UTF-8 encoded entry name bytes.
    private static byte[] rawEntryName(String entryName) {
        byte[] rawName = entryName.getBytes(StandardCharsets.UTF_8);
        requireUInt16(rawName.length, "entry name length");
        return rawName;
    }

    /// Acquires the state lock when it is present.
    private void lock() {
        ZipLocks.lock(lock);
    }

    /// Releases the state lock when it is present.
    private void unlock() {
        ZipLocks.unlock(lock);
    }

    /// Writes bytes to the current ZIP entry.
    private final class EntryOutputStream extends OutputStream {
        /// The entry name.
        private final String entryName;

        /// The raw entry name bytes.
        private final byte[] rawName;

        /// The local header offset.
        private final long localHeaderOffset;

        /// The compressed data offset.
        private final long compressedDataOffset;

        /// The CRC-32 of uncompressed entry data.
        private final CRC32 crc32 = new CRC32();

        /// The raw deflater used for ZIP deflated data.
        private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);

        /// The deflated output stream.
        private final DeflaterOutputStream deflatedOutput = new DeflaterOutputStream(output, deflater);

        /// The uncompressed entry size.
        private long uncompressedSize;

        /// Whether this entry output stream is open.
        private boolean entryOpen = true;

        /// Creates an entry output stream.
        private EntryOutputStream(String entryName, byte[] rawName, long localHeaderOffset, long compressedDataOffset) {
            this.entryName = entryName;
            this.rawName = rawName;
            this.localHeaderOffset = localHeaderOffset;
            this.compressedDataOffset = compressedDataOffset;
        }

        /// Writes one byte to the current ZIP entry.
        @Override
        public void write(int value) throws IOException {
            lock();
            try {
                ensureEntryOpen();
                crc32.update(value);
                uncompressedSize++;
                deflatedOutput.write(value);
            } finally {
                unlock();
            }
        }

        /// Writes bytes to the current ZIP entry.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            lock();
            try {
                ensureEntryOpen();
                crc32.update(bytes, offset, length);
                uncompressedSize += length;
                deflatedOutput.write(bytes, offset, length);
            } finally {
                unlock();
            }
        }

        /// Closes the current ZIP entry.
        @Override
        public void close() throws IOException {
            lock();
            try {
                if (!entryOpen) {
                    return;
                }
                entryOpen = false;
                try {
                    deflatedOutput.finish();
                    long compressedSize = output.position() - compressedDataOffset;
                    long crcValue = crc32.getValue();
                    writeDataDescriptor(crcValue, compressedSize, uncompressedSize);
                    centralEntries.add(new CentralEntry(
                            entryName,
                            rawName,
                            DATA_DESCRIPTOR_FLAG | UTF8_FLAG,
                            DEFLATED_METHOD,
                            crcValue,
                            compressedSize,
                            uncompressedSize,
                            localHeaderOffset,
                            false
                    ));
                } finally {
                    deflater.end();
                }
                currentEntryOutput = null;
            } finally {
                unlock();
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

    /// Counts bytes written to an output stream.
    @NotNullByDefault
    private static final class CountingOutputStream extends OutputStream {
        /// The wrapped output stream.
        private final OutputStream output;

        /// The number of bytes written.
        private long position;

        /// Creates a counting output stream.
        private CountingOutputStream(OutputStream output) {
            this.output = Objects.requireNonNull(output, "output");
        }

        /// Returns the number of bytes written.
        private long position() {
            return position;
        }

        /// Writes one byte.
        @Override
        public void write(int value) throws IOException {
            output.write(value);
            position++;
        }

        /// Writes bytes.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            output.write(bytes, offset, length);
            position += length;
        }

        /// Flushes the wrapped output stream.
        @Override
        public void flush() throws IOException {
            output.flush();
        }

        /// Closes the wrapped output stream.
        @Override
        public void close() throws IOException {
            output.close();
        }
    }

    /// Stores central directory metadata for one entry.
    @NotNullByDefault
    private static final class CentralEntry {
        /// The decoded entry name.
        private final String entryName;

        /// The raw entry name bytes.
        private final byte[] rawName;

        /// The general purpose bit flags.
        private final int flags;

        /// The ZIP compression method.
        private final int method;

        /// The CRC-32 value.
        private final long crc32;

        /// The compressed entry size.
        private final long compressedSize;

        /// The uncompressed entry size.
        private final long uncompressedSize;

        /// The local header offset.
        private final long localHeaderOffset;

        /// Whether this entry is a directory.
        private final boolean directory;

        /// Creates central directory metadata.
        private CentralEntry(
                String entryName,
                byte[] rawName,
                int flags,
                int method,
                long crc32,
                long compressedSize,
                long uncompressedSize,
                long localHeaderOffset,
                boolean directory
        ) {
            this.entryName = Objects.requireNonNull(entryName, "entryName");
            this.rawName = Objects.requireNonNull(rawName, "rawName");
            this.flags = flags;
            this.method = method;
            this.crc32 = crc32;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.localHeaderOffset = localHeaderOffset;
            this.directory = directory;
        }
    }

}
