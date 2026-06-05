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
import java.nio.file.attribute.FileStoreAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/// Implements a forward-only ZIP archive file system for streaming writes.
@NotNullByDefault
public final class StreamingZipArkivoFileSystemImpl extends ZipArkivoFileSystem {
    /// The ZIP local file header signature.
    private static final int ZIP_LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50;

    /// The ZIP central directory file header signature.
    private static final int ZIP_CENTRAL_DIRECTORY_HEADER_SIGNATURE = 0x02014b50;

    /// The ZIP data descriptor signature.
    private static final int ZIP_DATA_DESCRIPTOR_SIGNATURE = 0x08074b50;

    /// The ZIP end of central directory signature.
    private static final int ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50;

    /// The ZIP version needed to extract entries written by this file system.
    private static final int ZIP_VERSION_NEEDED = 20;

    /// The ZIP general purpose flag indicating a data descriptor follows entry data.
    private static final int ZIP_DATA_DESCRIPTOR_FLAG = 1 << 3;

    /// The ZIP general purpose flag indicating UTF-8 entry names.
    private static final int ZIP_UTF8_FLAG = 1 << 11;

    /// The ZIP stored method identifier.
    private static final int ZIP_STORED_METHOD = 0;

    /// The ZIP deflated method identifier.
    private static final int ZIP_DEFLATED_METHOD = 8;

    /// The DOS date for 1980-01-01.
    private static final int ZIP_DOS_DATE_1980_01_01 = 0x21;

    /// The maximum value stored in an unsigned 16-bit ZIP field.
    private static final int ZIP_UINT16_MAX = 0xffff;

    /// The maximum value stored in an unsigned 32-bit ZIP field.
    private static final long ZIP_UINT32_MAX = 0xffff_ffffL;

    /// The provider that created this ZIP file system.
    private final ZipArkivoFileSystemProvider provider;

    /// The archive path backing this file system.
    private final Path archivePath;

    /// The output stream that receives ZIP bytes.
    private final CountingOutputStream output;

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
        super(ArkivoStorageAccessSet.STREAM_WRITE, config.threadSafety());
        this.provider = Objects.requireNonNull(provider, "provider");
        this.archivePath = Objects.requireNonNull(archivePath, "archivePath");
        this.output = new CountingOutputStream(Files.newOutputStream(
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
        writeCentralDirectory();
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

        byte[] rawName = rawEntryName(entryName);
        long localHeaderOffset = output.position();
        writeLocalHeader(
                rawName,
                ZIP_DATA_DESCRIPTOR_FLAG | ZIP_UTF8_FLAG,
                ZIP_DEFLATED_METHOD,
                0,
                0,
                0
        );
        EntryOutputStream entryOutput = new EntryOutputStream(entryName, rawName, localHeaderOffset, output.position());
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
        byte[] rawName = rawEntryName(entryName);
        long localHeaderOffset = output.position();
        writeLocalHeader(rawName, ZIP_UTF8_FLAG, ZIP_STORED_METHOD, 0, 0, 0);
        centralEntries.add(new CentralEntry(
                entryName,
                rawName,
                ZIP_UTF8_FLAG,
                ZIP_STORED_METHOD,
                0,
                0,
                0,
                localHeaderOffset,
                true
        ));
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
        writeInt(ZIP_LOCAL_FILE_HEADER_SIGNATURE);
        writeShort(ZIP_VERSION_NEEDED);
        writeShort(flags);
        writeShort(method);
        writeShort(0);
        writeShort(ZIP_DOS_DATE_1980_01_01);
        writeInt(crc32);
        writeInt(compressedSize);
        writeInt(uncompressedSize);
        writeShort(rawName.length);
        writeShort(0);
        output.write(rawName);
    }

    /// Writes the data descriptor for a streamed entry.
    private void writeDataDescriptor(long crc32, long compressedSize, long uncompressedSize) throws IOException {
        writeInt(ZIP_DATA_DESCRIPTOR_SIGNATURE);
        writeInt(crc32);
        writeInt(compressedSize);
        writeInt(uncompressedSize);
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

        writeInt(ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE);
        writeShort(0);
        writeShort(0);
        writeShort(centralEntries.size());
        writeShort(centralEntries.size());
        writeInt(centralDirectorySize);
        writeInt(centralDirectoryOffset);
        writeShort(0);
    }

    /// Writes one central directory entry.
    private void writeCentralDirectoryEntry(CentralEntry entry) throws IOException {
        requireUInt32(entry.compressedSize, "compressed size");
        requireUInt32(entry.uncompressedSize, "uncompressed size");
        requireUInt32(entry.localHeaderOffset, "local header offset");

        writeInt(ZIP_CENTRAL_DIRECTORY_HEADER_SIGNATURE);
        writeShort(ZIP_VERSION_NEEDED);
        writeShort(ZIP_VERSION_NEEDED);
        writeShort(entry.flags);
        writeShort(entry.method);
        writeShort(0);
        writeShort(ZIP_DOS_DATE_1980_01_01);
        writeInt(entry.crc32);
        writeInt(entry.compressedSize);
        writeInt(entry.uncompressedSize);
        writeShort(entry.rawName.length);
        writeShort(0);
        writeShort(0);
        writeShort(0);
        writeShort(0);
        writeInt(entry.directory ? 0x10 : 0);
        writeInt(entry.localHeaderOffset);
        output.write(entry.rawName);
    }

    /// Returns UTF-8 encoded entry name bytes.
    private static byte[] rawEntryName(String entryName) {
        byte[] rawName = entryName.getBytes(StandardCharsets.UTF_8);
        requireUInt16(rawName.length, "entry name length");
        return rawName;
    }

    /// Writes a little-endian unsigned 16-bit value.
    private void writeShort(long value) throws IOException {
        requireUInt16(value, "short value");
        output.write((int) (value & 0xff));
        output.write((int) ((value >>> 8) & 0xff));
    }

    /// Writes a little-endian unsigned 32-bit value.
    private void writeInt(long value) throws IOException {
        requireUInt32(value, "int value");
        output.write((int) (value & 0xff));
        output.write((int) ((value >>> 8) & 0xff));
        output.write((int) ((value >>> 16) & 0xff));
        output.write((int) ((value >>> 24) & 0xff));
    }

    /// Requires a value to fit in an unsigned 16-bit ZIP field.
    private static void requireUInt16(long value, String name) {
        if (value < 0 || value > ZIP_UINT16_MAX) {
            throw new IllegalArgumentException(name + " is out of ZIP range");
        }
    }

    /// Requires a value to fit in an unsigned 32-bit ZIP field.
    private static void requireUInt32(long value, String name) {
        if (value < 0 || value > ZIP_UINT32_MAX) {
            throw new IllegalArgumentException(name + " is out of ZIP32 range");
        }
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
            synchronized (StreamingZipArkivoFileSystemImpl.this) {
                ensureEntryOpen();
                crc32.update(value);
                uncompressedSize++;
                deflatedOutput.write(value);
            }
        }

        /// Writes bytes to the current ZIP entry.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            synchronized (StreamingZipArkivoFileSystemImpl.this) {
                ensureEntryOpen();
                crc32.update(bytes, offset, length);
                uncompressedSize += length;
                deflatedOutput.write(bytes, offset, length);
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
                try {
                    deflatedOutput.finish();
                    long compressedSize = output.position() - compressedDataOffset;
                    long crcValue = crc32.getValue();
                    writeDataDescriptor(crcValue, compressedSize, uncompressedSize);
                    centralEntries.add(new CentralEntry(
                            entryName,
                            rawName,
                            ZIP_DATA_DESCRIPTOR_FLAG | ZIP_UTF8_FLAG,
                            ZIP_DEFLATED_METHOD,
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
