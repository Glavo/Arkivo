// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.internal.ArkivoPathMatchers;
import org.glavo.arkivo.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.zip.ZipArkivoFileSystemProvider;
import org.glavo.arkivo.zip.ZipEncryption;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessMode;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import static org.glavo.arkivo.zip.internal.ZipConstants.ENCRYPTED_FLAG;
import static org.glavo.arkivo.zip.internal.ZipConstants.END_OF_CENTRAL_DIRECTORY_SIGNATURE;
import static org.glavo.arkivo.zip.internal.ZipConstants.LOCAL_FILE_HEADER_SIGNATURE;
import static org.glavo.arkivo.zip.internal.ZipConstants.STORED_METHOD;
import static org.glavo.arkivo.zip.internal.ZipConstants.UTF8_FLAG;
import static org.glavo.arkivo.zip.internal.ZipConstants.VERSION_NEEDED;
import static org.glavo.arkivo.zip.internal.ZipConstants.WINZIP_AES_METHOD;
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

    /// The parsed ZIP file system configuration.
    private final ZipArkivoFileSystemConfig config;

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

    /// The action to run after this file system is closed, or `null` when no action is needed.
    private final @Nullable Runnable closeAction;

    /// Creates a streaming ZIP archive file system.
    public StreamingZipArkivoFileSystemImpl(
            ZipArkivoFileSystemProvider provider,
            Path archivePath,
            ZipArkivoFileSystemConfig config
    ) throws IOException {
        this(provider, archivePath, config, null);
    }

    /// Creates a streaming ZIP archive file system.
    public StreamingZipArkivoFileSystemImpl(
            ZipArkivoFileSystemProvider provider,
            Path archivePath,
            ZipArkivoFileSystemConfig config,
            @Nullable Runnable closeAction
    ) throws IOException {
        super(config.threadSafety());
        this.provider = Objects.requireNonNull(provider, "provider");
        this.archivePath = Objects.requireNonNull(archivePath, "archivePath");
        this.config = Objects.requireNonNull(config, "config");
        this.lock = ZipLocks.create(config.threadSafety());
        this.closeAction = closeAction;
        this.output = new CountingOutputStream(Files.newOutputStream(
                archivePath,
                config.openOptions().toArray(OpenOption[]::new)
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
        this.config = Objects.requireNonNull(config, "config");
        this.lock = ZipLocks.create(config.threadSafety());
        this.closeAction = null;
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
            Throwable failure = null;
            try {
                EntryOutputStream entryOutput = currentEntryOutput;
                if (entryOutput != null) {
                    entryOutput.close();
                }
                writeCentralDirectory();
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
            } finally {
                try {
                    output.close();
                } catch (IOException | RuntimeException | Error exception) {
                    if (failure != null) {
                        failure.addSuppressed(exception);
                    } else {
                        failure = exception;
                    }
                }
            }
            Runnable action = closeAction;
            try {
                if (action != null) {
                    action.run();
                }
            } catch (RuntimeException | Error exception) {
                if (failure != null) {
                    failure.addSuppressed(exception);
                } else {
                    failure = exception;
                }
            }
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error exception) {
                throw exception;
            }
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

    /// Checks whether the given path can be accessed in streaming output mode.
    public void checkAccess(Path path, AccessMode... modes) {
        checkOpen();
        if (path.getFileSystem() != this) {
            throw new java.nio.file.ProviderMismatchException();
        }
        for (AccessMode mode : modes) {
            Objects.requireNonNull(mode, "mode");
            if (mode != AccessMode.WRITE) {
                throw new UnsupportedOperationException("Streaming ZIP output supports only write access checks");
            }
        }
    }

    /// Returns a user principal lookup service for synthesized ZIP principals.
    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        return ZipPosixSupport.userPrincipalLookupService();
    }

    /// Opens an output stream for the next ZIP entry.
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        return newOutputStream(path, EntryMetadata.deflated(config.defaultEncryption()), options);
    }

    /// Opens a forward-only writable byte channel for the next ZIP entry.
    public SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
    ) throws IOException {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(attributes, "attributes");
        if (attributes.length != 0) {
            throw new UnsupportedOperationException("ZIP streaming file attributes are not supported");
        }
        return new EntryWritableByteChannel(newOutputStream(path, options.toArray(OpenOption[]::new)));
    }

    /// Opens an output stream for the next ZIP entry with central directory attributes.
    OutputStream newOutputStream(Path path, EntryMetadata metadata, OpenOption... options) throws IOException {
        lock();
        try {
            checkOpen();
            metadata.validate();
            requireSupportedOutputOptions(options);
            String entryName = regularEntryName(path);
            requireNoActiveEntry();
            checkNewEntry(entryName);
            byte @Nullable [] password = passwordForMetadata(entryName, metadata);
            byte[] rawName = rawEntryName(entryName);
            requireNewEntry(entryName);

            long localHeaderOffset = output.position();
            int flags = metadata.generalPurposeFlags();
            writeLocalHeader(
                    rawName,
                    metadata.localHeaderExtraData(),
                    flags,
                    metadata.headerMethod(),
                    metadata.dosTime,
                    metadata.dosDate,
                    metadata.localHeaderCrc32(),
                    metadata.localHeaderCompressedSize(),
                    metadata.localHeaderUncompressedSize()
            );
            EntryOutputStream entryOutput = new EntryOutputStream(
                    entryName,
                    rawName,
                    localHeaderOffset,
                    output.position(),
                    flags,
                    metadata,
                    password
            );
            currentEntryOutput = entryOutput;
            return entryOutput;
        } finally {
            unlock();
        }
    }

    /// Creates a directory entry.
    public void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        createDirectory(directory, EntryMetadata.directory(), attributes);
    }

    /// Creates a directory entry with central directory attributes.
    void createDirectory(Path directory, EntryMetadata metadata, FileAttribute<?>... attributes) throws IOException {
        lock();
        try {
            checkOpen();
            metadata.validate();
            if (metadata.method != STORED_METHOD) {
                throw new UnsupportedOperationException("ZIP directory entries must use the stored method");
            }
            if (metadata.encrypted()) {
                throw new UnsupportedOperationException("ZIP directory entries cannot be encrypted");
            }
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
            writeLocalHeader(
                    rawName,
                    metadata.localExtraData,
                    metadata.generalPurposeFlags(),
                    STORED_METHOD,
                    metadata.dosTime,
                    metadata.dosDate,
                    0,
                    0,
                    0
            );
            centralEntries.add(new CentralEntry(
                    entryName,
                    rawName,
                    metadata.generalPurposeFlags(),
                    STORED_METHOD,
                    metadata.dosTime,
                    metadata.dosDate,
                    0,
                    0,
                    0,
                    localHeaderOffset,
                    metadata
            ));
        } finally {
            unlock();
        }
    }

    /// Writes a complete stored entry with a known byte array body.
    void writeStoredEntry(Path path, byte[] content, EntryMetadata metadata) throws IOException {
        lock();
        try {
            checkOpen();
            Objects.requireNonNull(content, "content");
            metadata.validate();
            if (metadata.method != STORED_METHOD) {
                throw new UnsupportedOperationException("In-memory ZIP entries must use the stored method");
            }
            String entryName = regularEntryName(path);
            requireNoActiveEntry();
            checkNewEntry(entryName);

            CRC32 crc32 = new CRC32();
            crc32.update(content);
            long crcValue = crc32.getValue();
            long size = content.length;
            if (metadata.expectedUncompressedSize != ZipArkivoEntryAttributes.UNKNOWN_SIZE
                    && metadata.expectedUncompressedSize != size) {
                throw new IOException("ZIP entry size does not match the configured size: " + entryName);
            }
            if (metadata.expectedCrc32 != ZipArkivoEntryAttributes.UNKNOWN_CRC32 && metadata.expectedCrc32 != crcValue) {
                throw new IOException("ZIP entry CRC-32 does not match the configured CRC-32: " + entryName);
            }

            byte @Nullable [] password = passwordForMetadata(entryName, metadata);
            byte[] rawName = rawEntryName(entryName);
            requireNewEntry(entryName);
            long localHeaderOffset = output.position();
            int flags = metadata.generalPurposeFlags();
            long compressedSize = metadata.encryptedCompressedSize(size);
            writeLocalHeader(
                    rawName,
                    metadata.localHeaderExtraData(),
                    flags,
                    metadata.headerMethod(),
                    metadata.dosTime,
                    metadata.dosDate,
                    crcValue,
                    compressedSize,
                    size
            );
            if (metadata.traditionalEncrypted()) {
                byte[] encryptionPassword = Objects.requireNonNull(password, "password");
                OutputStream encrypted = ZipTraditionalCrypto.openEncryptingStream(
                        output,
                        encryptionPassword,
                        metadata.encryptionVerificationByte(crcValue)
                );
                encrypted.write(content);
                encrypted.flush();
            } else if (metadata.aesEncrypted()) {
                byte[] encryptionPassword = Objects.requireNonNull(password, "password");
                ZipAesCrypto.EncryptingOutputStream encrypted = ZipAesCrypto.openEncryptingStream(
                        output,
                        metadata.aesExtraField(),
                        encryptionPassword
                );
                encrypted.write(content);
                encrypted.finish();
            } else {
                output.write(content);
            }
            centralEntries.add(new CentralEntry(
                    entryName,
                    rawName,
                    flags,
                    metadata.headerMethod(),
                    metadata.dosTime,
                    metadata.dosDate,
                    crcValue,
                    compressedSize,
                    size,
                    localHeaderOffset,
                    metadata
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

    /// Checks that the entry name has not already been written.
    private void checkNewEntry(String entryName) throws IOException {
        if (writtenEntries.contains(entryName)) {
            throw new java.nio.file.FileAlreadyExistsException(entryName);
        }
    }

    /// Requires the entry name to be new.
    private void requireNewEntry(String entryName) throws IOException {
        checkNewEntry(entryName);
        writtenEntries.add(entryName);
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

    /// Returns the password for an encrypted entry path.
    private byte[] passwordForEntry(String entryName) throws IOException {
        ArkivoPasswordProvider passwordProvider = config.passwordProvider();
        if (passwordProvider == null) {
            throw new IOException("ZIP encrypted entry requires a password: " + entryName);
        }
        byte[] password = passwordProvider.passwordForEntry(getPath("/" + entryName));
        if (password == null) {
            throw new IOException("ZIP encrypted entry requires a password: " + entryName);
        }
        return password;
    }

    /// Returns the password for encrypted metadata, or `null` when the entry is not encrypted.
    private byte @Nullable [] passwordForMetadata(String entryName, EntryMetadata metadata) throws IOException {
        return metadata.encrypted() ? passwordForEntry(entryName) : null;
    }

    /// Writes the local file header for an entry.
    private void writeLocalHeader(
            byte[] rawName,
            byte[] localExtraData,
            int flags,
            int method,
            int dosTime,
            int dosDate,
            long crc32,
            long compressedSize,
            long uncompressedSize
    ) throws IOException {
        writeInt(output, LOCAL_FILE_HEADER_SIGNATURE);
        writeShort(output, VERSION_NEEDED);
        writeShort(output, flags);
        writeShort(output, method);
        writeShort(output, dosTime);
        writeShort(output, dosDate);
        writeInt(output, crc32);
        writeInt(output, compressedSize);
        writeInt(output, uncompressedSize);
        writeShort(output, rawName.length);
        writeShort(output, localExtraData.length);
        output.write(rawName);
        output.write(localExtraData);
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
        requireUInt16(entry.versionMadeBy, "version made by");
        requireUInt16(entry.internalAttributes, "internal attributes");
        requireUInt32(entry.externalAttributes, "external attributes");
        requireUInt16(entry.centralDirectoryExtraData.length, "central directory extra data length");
        requireUInt16(entry.rawComment.length, "comment length");

        writeInt(output, CENTRAL_DIRECTORY_HEADER_SIGNATURE);
        writeShort(output, entry.versionMadeBy);
        writeShort(output, VERSION_NEEDED);
        writeShort(output, entry.flags);
        writeShort(output, entry.method);
        writeShort(output, entry.dosTime);
        writeShort(output, entry.dosDate);
        writeInt(output, entry.crc32);
        writeInt(output, entry.compressedSize);
        writeInt(output, entry.uncompressedSize);
        writeShort(output, entry.rawName.length);
        writeShort(output, entry.centralDirectoryExtraData.length);
        writeShort(output, entry.rawComment.length);
        writeShort(output, 0);
        writeShort(output, entry.internalAttributes);
        writeInt(output, entry.externalAttributes);
        writeInt(output, entry.localHeaderOffset);
        output.write(entry.rawName);
        output.write(entry.centralDirectoryExtraData);
        output.write(entry.rawComment);
    }

    /// Returns UTF-8 encoded entry name bytes.
    private static byte[] rawEntryName(String entryName) {
        byte[] rawName = entryName.getBytes(StandardCharsets.UTF_8);
        requireUInt16(rawName.length, "entry name length");
        return rawName;
    }

    /// Encodes a file time into a ZIP DOS time field.
    private static int dosTime(@Nullable FileTime time) {
        LocalDateTime dateTime = dosDateTime(time);
        return (dateTime.getHour() << 11) | (dateTime.getMinute() << 5) | (dateTime.getSecond() / 2);
    }

    /// Encodes a file time into a ZIP DOS date field.
    private static int dosDate(@Nullable FileTime time) {
        LocalDateTime dateTime = dosDateTime(time);
        return ((dateTime.getYear() - 1980) << 9) | (dateTime.getMonthValue() << 5) | dateTime.getDayOfMonth();
    }

    /// Converts a file time to a ZIP-representable local date time.
    private static LocalDateTime dosDateTime(@Nullable FileTime time) {
        if (time == null) {
            return LocalDateTime.of(1980, 1, 1, 0, 0, 0);
        }
        try {
            LocalDateTime value = LocalDateTime.ofInstant(time.toInstant(), ZoneId.systemDefault());
            if (value.getYear() < 1980) {
                return LocalDateTime.of(1980, 1, 1, 0, 0, 0);
            }
            if (value.getYear() > 2107) {
                return LocalDateTime.of(2107, 12, 31, 23, 59, 58);
            }
            return value.withNano(0);
        } catch (DateTimeException exception) {
            return LocalDateTime.of(1980, 1, 1, 0, 0, 0);
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

        /// The general purpose bit flags.
        private final int flags;

        /// The metadata used to write this entry.
        private final EntryMetadata metadata;

        /// The CRC-32 of uncompressed entry data.
        private final CRC32 crc32 = new CRC32();

        /// The raw deflater used for ZIP deflated data.
        private final @Nullable Deflater deflater;

        /// The stream that receives uncompressed bytes from the caller.
        private final OutputStream entryOutput;

        /// The WinZip AES output stream, or `null` when this entry does not use WinZip AES.
        private final @Nullable ZipAesCrypto.EncryptingOutputStream aesOutput;

        /// The uncompressed entry size.
        private long uncompressedSize;

        /// Whether this entry output stream is open.
        private boolean entryOpen = true;

        /// Creates an entry output stream.
        private EntryOutputStream(
                String entryName,
                byte[] rawName,
                long localHeaderOffset,
                long compressedDataOffset,
                int flags,
                EntryMetadata metadata,
                byte @Nullable [] password
        ) throws IOException {
            this.entryName = entryName;
            this.rawName = rawName;
            this.localHeaderOffset = localHeaderOffset;
            this.compressedDataOffset = compressedDataOffset;
            this.flags = flags;
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            OutputStream dataOutput = output;
            ZipAesCrypto.EncryptingOutputStream entryAesOutput = null;
            if (metadata.traditionalEncrypted()) {
                byte[] encryptionPassword = Objects.requireNonNull(password, "password");
                dataOutput = ZipTraditionalCrypto.openEncryptingStream(
                        output,
                        encryptionPassword,
                        metadata.encryptionVerificationByte()
                );
            } else if (metadata.aesEncrypted()) {
                byte[] encryptionPassword = Objects.requireNonNull(password, "password");
                entryAesOutput = ZipAesCrypto.openEncryptingStream(
                        output,
                        metadata.aesExtraField(),
                        encryptionPassword
                );
                dataOutput = entryAesOutput;
            }
            this.aesOutput = entryAesOutput;
            if (metadata.method == DEFLATED_METHOD) {
                Deflater entryDeflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
                this.deflater = entryDeflater;
                this.entryOutput = new DeflaterOutputStream(dataOutput, entryDeflater);
            } else {
                this.deflater = null;
                this.entryOutput = dataOutput;
            }
        }

        /// Writes one byte to the current ZIP entry.
        @Override
        public void write(int value) throws IOException {
            lock();
            try {
                ensureEntryOpen();
                crc32.update(value);
                uncompressedSize++;
                entryOutput.write(value);
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
                entryOutput.write(bytes, offset, length);
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
                    if (entryOutput instanceof DeflaterOutputStream deflatedOutput) {
                        deflatedOutput.finish();
                    }
                    ZipAesCrypto.EncryptingOutputStream entryAesOutput = aesOutput;
                    if (entryAesOutput != null) {
                        entryAesOutput.finish();
                    }
                    long compressedSize = output.position() - compressedDataOffset;
                    long crcValue = crc32.getValue();
                    if (metadata.expectedUncompressedSize != ZipArkivoEntryAttributes.UNKNOWN_SIZE
                            && metadata.expectedUncompressedSize != uncompressedSize) {
                        throw new IOException("ZIP entry size does not match the configured size: " + entryName);
                    }
                    if (metadata.expectedCrc32 != ZipArkivoEntryAttributes.UNKNOWN_CRC32
                            && metadata.expectedCrc32 != crcValue) {
                        throw new IOException("ZIP entry CRC-32 does not match the configured CRC-32: " + entryName);
                    }
                    if (metadata.requiresDataDescriptor()) {
                        writeDataDescriptor(crcValue, compressedSize, uncompressedSize);
                    }
                    centralEntries.add(new CentralEntry(
                            entryName,
                            rawName,
                            flags,
                            metadata.headerMethod(),
                            metadata.dosTime,
                            metadata.dosDate,
                            crcValue,
                            compressedSize,
                            uncompressedSize,
                            localHeaderOffset,
                            metadata
                    ));
                } finally {
                    Deflater entryDeflater = deflater;
                    if (entryDeflater != null) {
                        entryDeflater.end();
                    }
                    currentEntryOutput = null;
                }
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

    /// Adapts an entry output stream to a forward-only seekable byte channel.
    @NotNullByDefault
    private static final class EntryWritableByteChannel implements SeekableByteChannel {
        /// The wrapped entry output stream.
        private final OutputStream output;

        /// The current forward-only position.
        private long position;

        /// Whether this channel is open.
        private boolean open = true;

        /// Creates a writable byte channel.
        private EntryWritableByteChannel(OutputStream output) {
            this.output = Objects.requireNonNull(output, "output");
        }

        /// Reads are never supported by ZIP output entry channels.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            Objects.requireNonNull(destination, "destination");
            ensureOpen();
            throw new NonReadableChannelException();
        }

        /// Writes bytes at the current forward-only position.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            ensureOpen();
            int length = source.remaining();
            if (source.hasArray()) {
                int offset = source.arrayOffset() + source.position();
                output.write(source.array(), offset, length);
                source.position(source.limit());
            } else {
                byte[] buffer = new byte[Math.min(length, 8192)];
                while (source.hasRemaining()) {
                    int count = Math.min(source.remaining(), buffer.length);
                    source.get(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            position += length;
            return length;
        }

        /// Returns the current forward-only position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Accepts only the current position because ZIP output entry channels are forward-only.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition != position) {
                throw new UnsupportedOperationException("ZIP output entry channels are forward-only");
            }
            return this;
        }

        /// Returns the number of bytes written so far.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return position;
        }

        /// Truncation is not supported by ZIP output entry channels.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            ensureOpen();
            if (size != position) {
                throw new UnsupportedOperationException("ZIP output entry channels cannot be truncated");
            }
            return this;
        }

        /// Returns whether this channel is open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes the wrapped entry output stream.
        @Override
        public void close() throws IOException {
            if (!open) {
                return;
            }
            open = false;
            output.close();
        }

        /// Requires this channel to be open.
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
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

    /// Stores write metadata for one streaming ZIP entry.
    @NotNullByDefault
    static final class EntryMetadata {
        /// The ZIP compression method.
        private final int method;

        /// The ZIP encryption method.
        private final ZipEncryption encryption;

        /// The DOS time field.
        private final int dosTime;

        /// The DOS date field.
        private final int dosDate;

        /// The ZIP version made by field.
        private final int versionMadeBy;

        /// The ZIP internal file attributes.
        private final int internalAttributes;

        /// The ZIP external file attributes.
        private final long externalAttributes;

        /// The expected uncompressed size, or `UNKNOWN_SIZE` when not configured.
        private final long expectedUncompressedSize;

        /// The expected CRC-32 value, or `UNKNOWN_CRC32` when not configured.
        private final long expectedCrc32;

        /// The raw local file header extra data.
        private final byte[] localExtraData;

        /// The raw central directory extra data.
        private final byte[] centralDirectoryExtraData;

        /// The raw ZIP entry comment bytes.
        private final byte[] rawComment;

        /// Creates write metadata.
        EntryMetadata(
                int method,
                ZipEncryption encryption,
                @Nullable FileTime lastModifiedTime,
                int versionMadeBy,
                int internalAttributes,
                long externalAttributes,
                long expectedUncompressedSize,
                long expectedCrc32,
                byte[] localExtraData,
                byte[] centralDirectoryExtraData,
                byte @Nullable [] rawComment
        ) {
            this.method = method;
            this.encryption = Objects.requireNonNull(encryption, "encryption");
            this.dosTime = dosTime(lastModifiedTime);
            this.dosDate = dosDate(lastModifiedTime);
            this.versionMadeBy = versionMadeBy;
            this.internalAttributes = internalAttributes;
            this.externalAttributes = externalAttributes;
            this.expectedUncompressedSize = expectedUncompressedSize;
            this.expectedCrc32 = expectedCrc32;
            byte[] requestedLocalExtraData = Objects.requireNonNull(localExtraData, "localExtraData").clone();
            byte[] requestedCentralDirectoryExtraData =
                    Objects.requireNonNull(centralDirectoryExtraData, "centralDirectoryExtraData").clone();
            if (ZipAesExtraField.isAesEncryption(this.encryption)) {
                ZipAesExtraField aes = ZipAesExtraField.forEncryption(this.encryption, method);
                this.localExtraData = aes.appendTo(requestedLocalExtraData);
                this.centralDirectoryExtraData = aes.appendTo(requestedCentralDirectoryExtraData);
            } else {
                this.localExtraData = requestedLocalExtraData;
                this.centralDirectoryExtraData = requestedCentralDirectoryExtraData;
            }
            this.rawComment = rawComment != null ? rawComment.clone() : new byte[0];
        }

        /// Returns default deflated file metadata.
        private static EntryMetadata deflated(ZipEncryption encryption) {
            return new EntryMetadata(
                    DEFLATED_METHOD,
                    encryption,
                    null,
                    VERSION_NEEDED,
                    0,
                    0,
                    ZipArkivoEntryAttributes.UNKNOWN_SIZE,
                    ZipArkivoEntryAttributes.UNKNOWN_CRC32,
                    new byte[0],
                    new byte[0],
                    null
            );
        }

        /// Returns default directory metadata.
        private static EntryMetadata directory() {
            return new EntryMetadata(
                    STORED_METHOD,
                    ZipEncryption.none(),
                    null,
                    VERSION_NEEDED,
                    0,
                    0x10L,
                    0,
                    0,
                    new byte[0],
                    new byte[0],
                    null
            );
        }

        /// Returns whether this entry requires a data descriptor.
        private boolean requiresDataDescriptor() {
            return method == DEFLATED_METHOD
                    || expectedUncompressedSize == ZipArkivoEntryAttributes.UNKNOWN_SIZE
                    || expectedCrc32 == ZipArkivoEntryAttributes.UNKNOWN_CRC32;
        }

        /// Returns whether this entry is encrypted.
        private boolean encrypted() {
            return !encryption.equals(ZipEncryption.none());
        }

        /// Returns whether this entry uses traditional ZIP encryption.
        private boolean traditionalEncrypted() {
            return encryption.equals(ZipEncryption.traditional());
        }

        /// Returns whether this entry uses WinZip AES encryption.
        private boolean aesEncrypted() {
            return ZipAesExtraField.isAesEncryption(encryption);
        }

        /// Returns the WinZip AES extra field for this entry.
        private ZipAesExtraField aesExtraField() {
            return ZipAesExtraField.forEncryption(encryption, method);
        }

        /// Returns the compression method identifier to store in ZIP headers.
        private int headerMethod() {
            return aesEncrypted() ? WINZIP_AES_METHOD : method;
        }

        /// Returns the ZIP general purpose flags for this entry.
        private int generalPurposeFlags() {
            int flags = UTF8_FLAG;
            if (requiresDataDescriptor()) {
                flags |= DATA_DESCRIPTOR_FLAG;
            }
            if (encrypted()) {
                flags |= ENCRYPTED_FLAG;
            }
            return flags;
        }

        /// Returns the CRC-32 value to store in the local header.
        private long localHeaderCrc32() {
            return requiresDataDescriptor() ? 0 : expectedCrc32;
        }

        /// Returns the compressed size to store in the local header.
        private long localHeaderCompressedSize() {
            return requiresDataDescriptor() ? 0 : encryptedCompressedSize(expectedUncompressedSize);
        }

        /// Returns the uncompressed size to store in the local header.
        private long localHeaderUncompressedSize() {
            return requiresDataDescriptor() ? 0 : expectedUncompressedSize;
        }

        /// Returns the compressed size with the encryption header included when needed.
        private long encryptedCompressedSize(long compressedSize) {
            if (traditionalEncrypted()) {
                return compressedSize + ZipTraditionalCrypto.HEADER_SIZE;
            }
            if (aesEncrypted()) {
                return compressedSize + aesExtraField().overheadSize();
            }
            return compressedSize;
        }

        /// Returns the traditional ZIP verification byte when CRC-32 is not known before entry data.
        private int encryptionVerificationByte() {
            return requiresDataDescriptor() ? dosTime >>> 8 : (int) (expectedCrc32 >>> 24);
        }

        /// Returns the traditional ZIP verification byte when CRC-32 is already known.
        private int encryptionVerificationByte(long crc32) {
            return requiresDataDescriptor() ? encryptionVerificationByte() : (int) (crc32 >>> 24);
        }

        /// Validates that this metadata can be stored in ZIP32 records.
        private void validate() {
            if (method != STORED_METHOD && method != DEFLATED_METHOD) {
                throw new UnsupportedOperationException("Unsupported ZIP compression method: " + method);
            }
            if (!encryption.equals(ZipEncryption.none())
                    && !encryption.equals(ZipEncryption.traditional())
                    && !ZipAesExtraField.isAesEncryption(encryption)) {
                throw new UnsupportedOperationException("Unsupported ZIP encryption method: " + encryption);
            }
            requireUInt16(versionMadeBy, "version made by");
            requireUInt16(internalAttributes, "internal attributes");
            requireUInt32(externalAttributes, "external attributes");
            requireUInt16(localExtraData.length, "local extra data length");
            requireUInt16(centralDirectoryExtraData.length, "central directory extra data length");
            requireUInt16(rawComment.length, "comment length");
            if (expectedUncompressedSize != ZipArkivoEntryAttributes.UNKNOWN_SIZE) {
                requireUInt32(expectedUncompressedSize, "uncompressed size");
                requireUInt32(encryptedCompressedSize(expectedUncompressedSize), "compressed size");
            }
            if (expectedCrc32 != ZipArkivoEntryAttributes.UNKNOWN_CRC32) {
                requireUInt32(expectedCrc32, "CRC-32");
            }
        }

        /// Returns the local header extra data bytes to write.
        private byte[] localHeaderExtraData() {
            return localExtraData;
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

        /// The DOS time field.
        private final int dosTime;

        /// The DOS date field.
        private final int dosDate;

        /// The CRC-32 value.
        private final long crc32;

        /// The compressed entry size.
        private final long compressedSize;

        /// The uncompressed entry size.
        private final long uncompressedSize;

        /// The local header offset.
        private final long localHeaderOffset;

        /// The ZIP version made by field.
        private final int versionMadeBy;

        /// The ZIP internal file attributes.
        private final int internalAttributes;

        /// The ZIP external file attributes.
        private final long externalAttributes;

        /// The raw central directory extra data.
        private final byte[] centralDirectoryExtraData;

        /// The raw ZIP entry comment bytes.
        private final byte[] rawComment;

        /// Creates central directory metadata.
        private CentralEntry(
                String entryName,
                byte[] rawName,
                int flags,
                int method,
                int dosTime,
                int dosDate,
                long crc32,
                long compressedSize,
                long uncompressedSize,
                long localHeaderOffset,
                EntryMetadata metadata
        ) {
            this.entryName = Objects.requireNonNull(entryName, "entryName");
            this.rawName = Objects.requireNonNull(rawName, "rawName");
            this.flags = flags;
            this.method = method;
            this.dosTime = dosTime;
            this.dosDate = dosDate;
            this.crc32 = crc32;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.localHeaderOffset = localHeaderOffset;
            this.versionMadeBy = metadata.versionMadeBy;
            this.internalAttributes = metadata.internalAttributes;
            this.externalAttributes = metadata.externalAttributes;
            this.centralDirectoryExtraData = metadata.centralDirectoryExtraData.clone();
            this.rawComment = metadata.rawComment.clone();
        }
    }

}
