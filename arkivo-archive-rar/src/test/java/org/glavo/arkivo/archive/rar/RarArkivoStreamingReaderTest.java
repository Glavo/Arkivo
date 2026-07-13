// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests RAR streaming reader behavior.
@NotNullByDefault
public final class RarArkivoStreamingReaderTest {
    /// The RAR5 archive signature.
    private static final byte @Unmodifiable [] RAR5_SIGNATURE =
            new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00};

    /// The RAR4 archive signature.
    private static final byte @Unmodifiable [] RAR4_SIGNATURE =
            new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x00};

    /// Reproducible seed for malformed RAR mutations.
    private static final long MALFORMED_MUTATION_SEED = 0x5241524d55544154L;

    /// The packed body of a real RAR4 method-3 six-byte text fixture.
    private static final byte @Unmodifiable [] RAR4_COMPRESSED_BODY = Base64.getDecoder().decode(
            "DQwM/hAMt2G79EFqVSh/2gEYP7Diz78doSA="
    );

    /// The packed body of a real RAR5 method-3 entry whose plaintext contains numbers 000 through 511.
    private static final byte @Unmodifiable [] RAR5_COMPRESSED_BODY = Base64.getDecoder().decode(
            "xKo0RDQk+jLrR/rETX295gWAggqMwQMqlcoim1Aak91A/ABAAgDUYAQ27RX5t1SDKmROzXwf4A=="
    );

    /// The 2,048-byte plaintext represented by `RAR5_COMPRESSED_BODY`.
    private static final byte @Unmodifiable [] RAR5_COMPRESSED_CONTENT = numberedRar5Content();

    /// The raw BLAKE2sp hash of `RAR5_COMPRESSED_CONTENT`.
    private static final byte @Unmodifiable [] RAR5_COMPRESSED_BLAKE2SP = Base64.getDecoder().decode(
            "fNXBrDHwz1iESlf7kHLER2jb6hRW43wh5JH0hTmC7eA="
    );

    /// The raw BLAKE2sp hash of the stored text `hello`.
    private static final byte @Unmodifiable [] HELLO_BLAKE2SP = Base64.getDecoder().decode(
            "Ij3+QlZd35chCzSjhIYLYDcX1cY8GHLJ/JnxsV3mYxs="
    );

    /// The raw BLAKE2sp hash of the stored text `hash`.
    private static final byte @Unmodifiable [] HASH_CONTENT_BLAKE2SP = Base64.getDecoder().decode(
            "fnH2NMRgUAlngPPefASxIh0Qh+2hkakijdXj5iM2nFc="
    );

    /// The raw BLAKE2sp hash of the stored multi-volume fixture content.
    private static final byte @Unmodifiable [] SPLIT_STORED_BLAKE2SP = Base64.getDecoder().decode(
            "v8V/QwGHsrun9a3YDUHLqK0vtdNnqwrPUSa7duptEj4="
    );

    /// The raw BLAKE2sp hash of the encrypted stored multi-volume fixture content.
    private static final byte @Unmodifiable [] ENCRYPTED_SPLIT_STORED_BLAKE2SP =
            Base64.getDecoder().decode("ZOxvj1vcgZpkO6zX45qK4uCr1oxdeq7FDyDloZamWEw=");

    /// RAR4 file header flag indicating that the name field includes Unicode metadata.
    private static final long RAR4_FILE_FLAG_UNICODE = 0x0200L;

    /// The UTF-16LE password used by RAR 3.x AES fixtures.
    private static final byte @Unmodifiable [] RAR3_PASSWORD =
            "rar3 password".getBytes(StandardCharsets.UTF_16LE);

    /// The eight-byte salt used by RAR 3.x encrypted file fixtures.
    private static final byte @Unmodifiable [] RAR3_FILE_SALT =
            new byte[]{0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef};

    /// The password used by RAR5 encrypted archive fixtures.
    private static final byte @Unmodifiable [] RAR5_PASSWORD =
            "correct horse battery staple".getBytes(StandardCharsets.UTF_8);

    /// The PBKDF2 salt used by RAR5 encrypted file fixtures.
    private static final byte @Unmodifiable [] RAR5_FILE_SALT = new byte[]{
            0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
            0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f
    };

    /// The PBKDF2 salt used by encrypted archive header fixtures.
    private static final byte @Unmodifiable [] RAR5_HEADER_SALT = new byte[]{
            0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27,
            0x28, 0x29, 0x2a, 0x2b, 0x2c, 0x2d, 0x2e, 0x2f
    };

    /// The AES-CBC initialization vector used by encrypted file fixtures.
    private static final byte @Unmodifiable [] RAR5_FILE_IV = new byte[]{
            0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37,
            0x38, 0x39, 0x3a, 0x3b, 0x3c, 0x3d, 0x3e, 0x3f
    };

    /// The small PBKDF2 iteration exponent used by fast unit-test fixtures.
    private static final int RAR5_KDF_LOG = 3;

    /// Verifies deterministic RAR truncations and mutations never leak parser runtime exceptions.
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    public void boundsAndNormalizesMalformedInputFailures() throws IOException {
        byte[] content = "RAR malformed-input fixture".repeat(32).getBytes(StandardCharsets.UTF_8);
        byte[] validArchive = archive(storedFile(
                "payload.bin",
                1_700_000_000L,
                0100644,
                content,
                null
        ));
        @Unmodifiable Map<String, Object> environment = Map.of(
                ArkivoFileSystem.MAX_ENTRY_COUNT.key(), 16L,
                ArkivoFileSystem.MAX_ENTRY_SIZE.key(), 1L << 20,
                ArkivoFileSystem.MAX_TOTAL_ENTRY_SIZE.key(), 1L << 20,
                ArkivoFileSystem.MAX_METADATA_SIZE.key(), 1L << 20
        );
        Path archivePath = createTemporaryArchivePath("rar-malformed-");
        try {
            Files.write(archivePath, validArchive);
            try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem("rar", archivePath, environment)) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/payload.bin")));
            }
            assertArrayEquals(content, readSingleRarBody(validArchive, environment));

            for (int length : malformedTruncationLengths(validArchive.length)) {
                exerciseMalformedRar(
                        archivePath,
                        Arrays.copyOf(validArchive, length),
                        environment,
                        "truncation@" + length
                );
            }

            SplittableRandom random = new SplittableRandom(MALFORMED_MUTATION_SEED);
            for (int index = 0; index < 48; index++) {
                byte[] mutated = validArchive.clone();
                int changes = random.nextInt(1, 5);
                for (int change = 0; change < changes; change++) {
                    int offset = random.nextInt(mutated.length);
                    mutated[offset] ^= (byte) (1 << random.nextInt(Byte.SIZE));
                }
                exerciseMalformedRar(archivePath, mutated, environment, "mutation#" + index);
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Exercises detection, indexed reading, and forward-only reading for one damaged RAR archive.
    private static void exerciseMalformedRar(
            Path archivePath,
            byte[] archive,
            @Unmodifiable Map<String, Object> environment,
            String variant
    ) throws IOException {
        tolerateMalformedRarFailure(
                () -> ArkivoFormats.detect(ByteBuffer.wrap(archive).asReadOnlyBuffer()),
                variant + " detection"
        );
        Files.write(archivePath, archive, StandardOpenOption.TRUNCATE_EXISTING);
        tolerateMalformedRarFailure(() -> {
            try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem("rar", archivePath, environment)) {
                Path payload = fileSystem.getPath("/payload.bin");
                if (Files.exists(payload)) {
                    Files.readAllBytes(payload);
                }
            }
        }, variant + " file system");
        tolerateMalformedRarFailure(
                () -> readSingleRarBody(archive, environment),
                variant + " streaming reader"
        );
    }

    /// Reads all regular RAR entry bodies with an independent output bound.
    private static byte[] readSingleRarBody(
            byte[] archive,
            @Unmodifiable Map<String, Object> environment
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var reader = ArkivoFormats.openStreamingReader(
                "rar",
                Channels.newChannel(new ByteArrayInputStream(archive)),
                environment
        )) {
            while (reader.next()) {
                BasicFileAttributes attributes = reader.readAttributes(BasicFileAttributes.class);
                if (!attributes.isRegularFile()) {
                    continue;
                }
                try (InputStream body = reader.openInputStream()) {
                    byte[] bytes = body.readNBytes((1 << 20) + 1);
                    if (bytes.length > 1 << 20) {
                        throw new IOException("Malformed RAR exceeded the defensive output bound");
                    }
                    output.write(bytes);
                }
            }
        }
        return output.toByteArray();
    }

    /// Returns representative malformed prefix lengths around headers, body, and tail boundaries.
    private static @Unmodifiable List<Integer> malformedTruncationLengths(int length) {
        TreeSet<Integer> lengths = new TreeSet<>();
        for (int value = 0; value <= Math.min(16, length - 1); value++) {
            lengths.add(value);
        }
        for (int offset = 1; offset <= Math.min(16, length); offset++) {
            lengths.add(length - offset);
        }
        for (int fraction = 1; fraction < 8; fraction++) {
            lengths.add((int) ((long) length * fraction / 8L));
        }
        lengths.remove(length);
        return List.copyOf(lengths);
    }

    /// Accepts malformed-input I/O failures while rejecting leaked runtime exceptions and errors.
    private static void tolerateMalformedRarFailure(Executable operation, String context) {
        assertDoesNotThrow(() -> {
            try {
                operation.execute();
            } catch (IOException | UnsupportedOperationException expected) {
                // A damaged RAR may be rejected while parsing metadata, decoding a body, or closing.
            }
        }, context);
    }

    /// Verifies a RAR file system can own one arbitrary seekable channel from its current position.
    @Test
    public void opensFileSystemFromSingleSeekableChannel() throws IOException {
        byte[] content = "single channel".getBytes(StandardCharsets.UTF_8);
        byte[] archive = archive(storedFile("value.txt", 1_700_000_000L, 0100644, content, null));
        int archiveOffset = 5;
        byte[] embedded = new byte[archiveOffset + archive.length];
        System.arraycopy(archive, 0, embedded, archiveOffset, archive.length);
        TestByteArraySeekableChannel channel = new TestByteArraySeekableChannel(embedded);
        channel.position(archiveOffset);

        try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(channel)) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/value.txt")));
        }
        assertEquals(false, channel.isOpen());

        TestByteArraySeekableChannel detectedChannel = new TestByteArraySeekableChannel(embedded);
        detectedChannel.position(archiveOffset);
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(detectedChannel)) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/value.txt")));
        }
        assertEquals(false, detectedChannel.isOpen());
    }

    /// Verifies that explicit volume sources and configured cached-content storage are both owned by the file system.
    @Test
    public void explicitVolumeFileSystemOwnsConfiguredStorage() throws IOException {
        byte[] content = "volume-content".getBytes(StandardCharsets.UTF_8);
        TestSeekableChannelSource source = new TestSeekableChannelSource(rar4Archive(
                storedFile("value.txt", 1_700_000_000L, 0100644, content, null)
        ));
        TrackingEditStorage storage = new TrackingEditStorage(false);
        try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                source,
                Map.of(ArkivoFileSystem.EDIT_STORAGE.key(), storage)
        )) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/value.txt")));
        }
        assertEquals(1, source.closeCount());
        assertEquals(true, source.allOpenedChannelsClosed());
        assertEquals(1, storage.createdContentCount());
        assertEquals(1, storage.contentCloseCount());
        assertEquals(1, storage.closeCount());
    }

    /// Verifies that file redirections share one cached body and failed cleanup can be retried.
    @Test
    public void fileSystemStorageSharesRedirectedBodiesAndRetriesCleanup() throws IOException {
        byte[] content = "shared-content".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("rar-storage-");
        Files.write(archivePath, archive(
                storedFile("source.txt", 1_700_000_000L, 0100644, content, null),
                redirectedEntry(
                        "hard.txt",
                        1_700_000_001L,
                        0100644,
                        RarArkivoEntryAttributes.REDIRECTION_TYPE_HARD_LINK,
                        0,
                        "source.txt"
                ),
                redirectedEntry(
                        "copy.txt",
                        1_700_000_002L,
                        0100644,
                        RarArkivoEntryAttributes.REDIRECTION_TYPE_FILE_COPY,
                        0,
                        "source.txt"
                )
        ));
        TrackingEditStorage storage = new TrackingEditStorage(true);
        RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                archivePath,
                Map.of(ArkivoFileSystem.EDIT_STORAGE.key(), storage)
        );
        try {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/source.txt")));
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/hard.txt")));
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/copy.txt")));
            IOException failure = assertThrows(IOException.class, fileSystem::close);
            assertEquals("content close failed", failure.getMessage());
            assertEquals(1, storage.createdContentCount());
            assertEquals(1, storage.contentCloseCount());
            assertEquals(1, storage.closeCount());

            fileSystem.close();
            fileSystem.close();
            assertEquals(2, storage.contentCloseCount());
            assertEquals(1, storage.closeCount());
        } finally {
            try {
                fileSystem.close();
            } finally {
                Files.deleteIfExists(archivePath);
            }
        }
    }

    /// Verifies common read limits apply to RAR streaming readers and file systems.
    @Test
    public void enforcesCommonArchiveReadLimits() throws IOException {
        byte[] first = {1, 2, 3};
        byte[] second = {4, 5, 6, 7};
        byte[] archive = archive(
                storedFile("first.bin", 1_700_000_000L, 0100644, first, null),
                storedFile("second.bin", 1_700_000_001L, 0100644, second, null)
        );

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                Map.of(ArkivoFileSystem.MAX_ENTRY_COUNT.key(), 1L)
        )) {
            assertEquals(true, reader.next());
            try (InputStream input = reader.openInputStream()) {
                assertArrayEquals(first, input.readAllBytes());
            }
            ArkivoReadLimitException exception = assertThrows(ArkivoReadLimitException.class, reader::next);
            assertEquals(ArkivoReadLimitKind.ENTRY_COUNT, exception.kind());
            assertEquals(1L, exception.maximum());
            assertEquals(2L, exception.actual());
            assertNull(exception.entryPath());
        }

        Path archivePath = createTemporaryArchivePath("rar-read-limits-");
        try {
            Files.write(archivePath, archive);
            ArkivoReadLimitException exception = assertThrows(
                    ArkivoReadLimitException.class,
                    () -> RarArkivoFileSystem.open(
                            archivePath,
                            Map.of(ArkivoFileSystem.MAX_TOTAL_ENTRY_SIZE.key(), 6L)
                    )
            );
            assertEquals(ArkivoReadLimitKind.TOTAL_ENTRY_SIZE, exception.kind());
            assertEquals(6L, exception.maximum());
            assertEquals(7L, exception.actual());
            assertEquals("second.bin", exception.entryPath());
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Verifies RAR signatures and headers consume the common metadata budget.
    @Test
    public void enforcesCommonArchiveMetadataLimit() throws IOException {
        byte[] archive = archive(storedFile(
                "value.bin",
                1_700_000_000L,
                0100644,
                new byte[]{1, 2, 3},
                null
        ));
        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                Map.of(ArkivoFileSystem.MAX_METADATA_SIZE.key(), 8L)
        )) {
            ArkivoReadLimitException exception = assertThrows(ArkivoReadLimitException.class, reader::next);
            assertEquals(ArkivoReadLimitKind.METADATA_SIZE, exception.kind());
            assertEquals(8L, exception.maximum());
            assertTrue(exception.actual() > 8L);
            assertNull(exception.entryPath());

            ArkivoReadLimitException repeated = assertThrows(ArkivoReadLimitException.class, reader::next);
            assertEquals(exception.kind(), repeated.kind());
            assertEquals(exception.maximum(), repeated.maximum());
            assertEquals(exception.actual(), repeated.actual());
        }
    }

    /// Verifies that stored RAR5 entries can be streamed with metadata.
    @Test
    public void readsStoredRar5Entries() throws IOException {
        byte[] first = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] archive = archive(
                service("CMT", "comment".getBytes(StandardCharsets.UTF_8)),
                directory("dir/", 1_700_000_000L, 040755),
                storedFile("dir/hello.txt", 1_700_000_010L, 0100644, first, owner("alice", "staff", 1000, 1001)),
                symbolicLink("link", 1_700_000_020L, 0120777, "dir/hello.txt")
        );
        ArrayList<String> paths = new ArrayList<>();

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes directory = reader.readAttributes(RarArkivoEntryAttributes.class);
            paths.add(directory.path());
            assertEquals("dir/", directory.path());
            assertEquals(true, directory.isDirectory());
            assertEquals(040755, directory.fileAttributes());
            assertEquals(FileTime.fromMillis(1_700_000_000_000L), directory.lastModifiedTime());

            assertEquals(true, reader.next());
            RarArkivoEntryAttributes file = reader.readAttributes(RarArkivoEntryAttributes.class);
            BasicFileAttributes basicFile = reader.readAttributes(BasicFileAttributes.class);
            PosixFileAttributes posixFile = reader.readAttributes(PosixFileAttributes.class);
            paths.add(file.path());
            assertEquals("dir/hello.txt", file.path());
            assertEquals(true, file.isRegularFile());
            assertEquals(RarArkivoEntryAttributes.HOST_OS_UNIX, file.hostOs());
            assertEquals(0, file.compressionMethod());
            assertEquals(first.length, file.packedSize());
            assertEquals(first.length, file.unpackedSize());
            assertEquals(first.length, basicFile.size());
            assertEquals(crc32(first), file.dataCrc32());
            assertEquals(RarArkivoEntryAttributes.NO_REDIRECTION_TYPE, file.redirectionType());
            assertEquals(0, file.redirectionFlags());
            assertNull(file.redirectionTarget());
            assertEquals("alice", file.userName());
            assertEquals("staff", file.groupName());
            assertEquals(1000, file.userId());
            assertEquals(1001, file.groupId());
            assertEquals("alice", posixFile.owner().getName());
            assertEquals("staff", posixFile.group().getName());
            assertEquals(
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.OTHERS_READ
                    ),
                    posixFile.permissions()
            );
            try (var input = reader.openInputStream()) {
                assertArrayEquals(first, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            RarArkivoEntryAttributes link = reader.readAttributes(RarArkivoEntryAttributes.class);
            paths.add(link.path());
            assertEquals("link", link.path());
            assertEquals(true, link.isSymbolicLink());
            assertEquals("dir/hello.txt", link.linkName());
            assertEquals(RarArkivoEntryAttributes.REDIRECTION_TYPE_UNIX_SYMLINK, link.redirectionType());
            assertEquals(0, link.redirectionFlags());
            assertEquals("dir/hello.txt", link.redirectionTarget());
            assertEquals(false, link.redirectionTargetDirectory());
            assertNull(link.userName());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(new byte[0], input.readAllBytes());
            }

            assertEquals(false, reader.next());
        }

        assertEquals(List.of("dir/", "dir/hello.txt", "link"), paths);
    }

    /// Verifies that stored RAR5 entries split across file parts are exposed as one logical entry.
    @Test
    public void readsStoredMultiVolumeEntry() throws IOException {
        byte[] firstPart = "split ".getBytes(StandardCharsets.UTF_8);
        byte[] secondPart = "entry".getBytes(StandardCharsets.UTF_8);
        byte[] content = concatenate(firstPart, secondPart);
        long contentCrc32 = crc32(content);
        byte[] archive = archive(
                splitStoredFilePart("split.txt", 1_700_000_000L, 0100644, content.length, contentCrc32, firstPart, false, true),
                splitStoredFilePart(
                        "split.txt",
                        1_700_000_000L,
                        0100644,
                        content.length,
                        contentCrc32,
                        secondPart,
                        blake2spHash(SPLIT_STORED_BLAKE2SP),
                        true,
                        false
                ),
                storedFile("after.txt", 1_700_000_001L, 0100644, "after".getBytes(StandardCharsets.UTF_8), null)
        );

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals("split.txt", attributes.path());
            assertEquals(firstPart.length, attributes.packedSize());
            assertEquals(content.length, attributes.unpackedSize());
            assertEquals(contentCrc32, attributes.dataCrc32());
            assertEquals(false, attributes.continuesFromPreviousVolume());
            assertEquals(true, attributes.continuesInNextVolume());

            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals("split.txt", reader.readAttributes(RarArkivoEntryAttributes.class).path());

            assertEquals(true, reader.next());
            assertEquals("after.txt", reader.readAttributes(RarArkivoEntryAttributes.class).path());
            try (var input = reader.openInputStream()) {
                assertArrayEquals("after".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            assertEquals(true, reader.next());
            assertEquals("after.txt", reader.readAttributes(RarArkivoEntryAttributes.class).path());
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that a RAR5 entry whose first part is unavailable cannot be opened as content.
    @Test
    public void rejectsStoredEntryStartingFromPreviousVolumeOnOpen() throws IOException {
        byte[] body = "tail".getBytes(StandardCharsets.UTF_8);
        byte[] archive = archive(splitStoredFilePart(
                "tail.txt",
                1_700_000_000L,
                0100644,
                body.length,
                crc32(body),
                body,
                true,
                false
        ));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals(true, attributes.continuesFromPreviousVolume());

            IOException exception = assertThrows(IOException.class, reader::openInputStream);
            assertEquals(true, exception.getMessage().contains("previous volume"));
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that non-symbolic-link RAR5 redirection records are preserved as metadata.
    @Test
    public void readsNonSymbolicRedirectionMetadata() throws IOException {
        byte[] archive = archive(
                redirectedEntry(
                        "hard-link",
                        1_700_000_000L,
                        0100644,
                        RarArkivoEntryAttributes.REDIRECTION_TYPE_HARD_LINK,
                        0,
                        "dir/hello.txt"
                ),
                redirectedEntry(
                        "file-copy",
                        1_700_000_001L,
                        0100644,
                        RarArkivoEntryAttributes.REDIRECTION_TYPE_FILE_COPY,
                        0,
                        "dir/hello.txt"
                ),
                redirectedEntry(
                        "junction",
                        1_700_000_002L,
                        040755,
                        RarArkivoEntryAttributes.REDIRECTION_TYPE_WINDOWS_JUNCTION,
                        RarArkivoEntryAttributes.REDIRECTION_FLAG_TARGET_DIRECTORY,
                        "target-dir"
                )
        );

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes hardLink = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals("hard-link", hardLink.path());
            assertEquals(true, hardLink.isRegularFile());
            assertEquals(false, hardLink.isSymbolicLink());
            assertEquals(false, hardLink.isOther());
            assertNull(hardLink.linkName());
            assertEquals(RarArkivoEntryAttributes.REDIRECTION_TYPE_HARD_LINK, hardLink.redirectionType());
            assertEquals(0, hardLink.redirectionFlags());
            assertEquals("dir/hello.txt", hardLink.redirectionTarget());
            assertEquals(false, hardLink.redirectionTargetDirectory());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(new byte[0], input.readAllBytes());
            }

            assertEquals(true, reader.next());
            RarArkivoEntryAttributes fileCopy = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals("file-copy", fileCopy.path());
            assertEquals(true, fileCopy.isRegularFile());
            assertEquals(false, fileCopy.isSymbolicLink());
            assertEquals(false, fileCopy.isOther());
            assertNull(fileCopy.linkName());
            assertEquals(RarArkivoEntryAttributes.REDIRECTION_TYPE_FILE_COPY, fileCopy.redirectionType());
            assertEquals(0, fileCopy.redirectionFlags());
            assertEquals("dir/hello.txt", fileCopy.redirectionTarget());
            assertEquals(false, fileCopy.redirectionTargetDirectory());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(new byte[0], input.readAllBytes());
            }

            assertEquals(true, reader.next());
            RarArkivoEntryAttributes junction = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals("junction", junction.path());
            assertEquals(false, junction.isRegularFile());
            assertEquals(false, junction.isSymbolicLink());
            assertEquals(true, junction.isOther());
            assertEquals(RarArkivoEntryAttributes.REDIRECTION_TYPE_WINDOWS_JUNCTION, junction.redirectionType());
            assertEquals(RarArkivoEntryAttributes.REDIRECTION_FLAG_TARGET_DIRECTORY, junction.redirectionFlags());
            assertEquals("target-dir", junction.redirectionTarget());
            assertEquals(true, junction.redirectionTargetDirectory());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(new byte[0], input.readAllBytes());
            }

            assertEquals(false, reader.next());
        }
    }

    /// Verifies that stored RAR5 entries are exposed through the read-only file system API.
    @Test
    public void opensStoredEntriesAsReadOnlyFileSystem() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] backslashContent = "backslash path".getBytes(StandardCharsets.UTF_8);
        Set<PosixFilePermission> filePermissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ
        );
        byte[] splitFirstPart = "split ".getBytes(StandardCharsets.UTF_8);
        byte[] splitSecondPart = "content".getBytes(StandardCharsets.UTF_8);
        byte[] splitContent = concatenate(splitFirstPart, splitSecondPart);
        long splitCrc32 = crc32(splitContent);
        byte[] hash = HELLO_BLAKE2SP.clone();
        Path archivePath = createTemporaryArchivePath("rar-fs-");
        Path copiedDirectory = archivePath.getParent().resolve("copied-dir");
        Path existingFile = archivePath.getParent().resolve("existing-file");
        Files.write(archivePath, archive(
                directory("dir/", 1_700_000_000L, 040755),
                storedFile(
                        "dir/hello.txt",
                        1_700_000_010L,
                        0100644,
                        content,
                        concatenate(owner("alice", "staff", 1000, 1001), blake2spHash(hash))
                ),
                storedFile(
                        "windows\\path\\backslash.txt",
                        1_700_000_010L,
                        0100644,
                        backslashContent,
                        null
                ),
                redirectedEntry(
                        "dir/hard-link.txt",
                        1_700_000_010L,
                        0100644,
                        RarArkivoEntryAttributes.REDIRECTION_TYPE_HARD_LINK,
                        0,
                        "dir/hello.txt"
                ),
                redirectedEntry(
                        "dir/file-copy.txt",
                        1_700_000_010L,
                        0100644,
                        RarArkivoEntryAttributes.REDIRECTION_TYPE_FILE_COPY,
                        0,
                        "dir/hello.txt"
                ),
                splitStoredFilePart(
                        "dir/split.txt",
                        1_700_000_011L,
                        0100644,
                        splitContent.length,
                        splitCrc32,
                        splitFirstPart,
                        false,
                        true
                ),
                splitStoredFilePart(
                        "dir/split.txt",
                        1_700_000_011L,
                        0100644,
                        splitContent.length,
                        splitCrc32,
                        splitSecondPart,
                        true,
                        false
                ),
                symbolicLink("link", 1_700_000_020L, 0120777, "dir/hello.txt"),
                compressedFile("dir/compressed.bin", 1_700_000_020L, 0100644, new byte[]{1, 2, 3}, 6)
        ));

        RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(archivePath);
        try (fileSystem) {
            assertEquals(true, fileSystem.isReadOnly());

            Path directory = fileSystem.getPath("/dir");
            BasicFileAttributes directoryAttributes = Files.readAttributes(directory, BasicFileAttributes.class);
            assertEquals(true, directoryAttributes.isDirectory());
            Files.copy(directory, copiedDirectory);
            assertEquals(true, Files.isDirectory(copiedDirectory));
            assertThrows(FileAlreadyExistsException.class, () -> Files.copy(directory, copiedDirectory));
            Files.copy(directory, copiedDirectory, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(existingFile, "existing", StandardCharsets.UTF_8);
            Files.copy(directory, existingFile, StandardCopyOption.REPLACE_EXISTING);
            assertEquals(true, Files.isDirectory(existingFile));

            Path file = fileSystem.getPath("/dir/hello.txt");
            RarArkivoEntryAttributes fileAttributes = Files.readAttributes(file, RarArkivoEntryAttributes.class);
            RarArkivoEntryAttributeView rarView = Objects.requireNonNull(Files.getFileAttributeView(
                    file,
                    RarArkivoEntryAttributeView.class
            ));
            assertEquals(true, fileAttributes.isRegularFile());
            assertEquals(content.length, fileAttributes.size());
            assertEquals("alice", fileAttributes.userName());
            assertEquals("rar", rarView.name());
            assertEquals("dir/hello.txt", rarView.readAttributes().path());
            assertEquals("alice", rarView.readAttributes().userName());
            assertArrayEquals(hash, rarView.readAttributes().blake2spHash());
            PosixFileAttributes posixAttributes = Files.readAttributes(file, PosixFileAttributes.class);
            assertEquals("alice", posixAttributes.owner().getName());
            assertEquals("staff", posixAttributes.group().getName());
            assertEquals(filePermissions, posixAttributes.permissions());
            FileOwnerAttributeView ownerView =
                    Objects.requireNonNull(Files.getFileAttributeView(file, FileOwnerAttributeView.class));
            assertEquals("owner", ownerView.name());
            assertEquals("alice", ownerView.getOwner().getName());
            assertThrows(ReadOnlyFileSystemException.class, () -> ownerView.setOwner(() -> "other-user"));
            PosixFileAttributeView posixView =
                    Objects.requireNonNull(Files.getFileAttributeView(file, PosixFileAttributeView.class));
            assertEquals("posix", posixView.name());
            assertEquals(filePermissions, posixView.readAttributes().permissions());
            assertEquals("alice", posixView.getOwner().getName());
            assertThrows(ReadOnlyFileSystemException.class, () -> posixView.setGroup(() -> "other-group"));
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> posixView.setPermissions(Set.<PosixFilePermission>of())
            );
            assertThrows(ReadOnlyFileSystemException.class, () -> rarView.setTimes(
                    FileTime.fromMillis(1_700_000_030_000L),
                    null,
                    null
            ));
            var fileStore = Files.getFileStore(file);
            assertEquals(fileStore.name(), fileStore.getAttribute("name"));
            assertEquals(fileStore.type(), fileStore.getAttribute("type"));
            assertEquals(Boolean.valueOf(fileStore.isReadOnly()), fileStore.getAttribute("basic:readOnly"));
            assertEquals(Long.valueOf(fileStore.getTotalSpace()), fileStore.getAttribute("totalSpace"));
            assertEquals(Long.valueOf(fileStore.getUsableSpace()), fileStore.getAttribute("usableSpace"));
            assertEquals(Long.valueOf(fileStore.getUnallocatedSpace()), fileStore.getAttribute("unallocatedSpace"));
            assertThrows(UnsupportedOperationException.class, () -> fileStore.getAttribute("rar:type"));
            assertThrows(UnsupportedOperationException.class, () -> fileStore.getAttribute("missing"));
            assertEquals(true, fileStore.supportsFileAttributeView(RarArkivoEntryAttributeView.class));
            assertEquals(true, fileStore.supportsFileAttributeView(FileOwnerAttributeView.class));
            assertEquals(true, fileStore.supportsFileAttributeView(PosixFileAttributeView.class));
            assertEquals(true, fileStore.supportsFileAttributeView("rar"));
            assertEquals(true, fileStore.supportsFileAttributeView("owner"));
            assertEquals(true, fileStore.supportsFileAttributeView("posix"));
            assertArrayEquals(content, Files.readAllBytes(file));

            Path backslashFile = fileSystem.getPath("/windows/path/backslash.txt");
            assertArrayEquals(backslashContent, Files.readAllBytes(backslashFile));

            Path hardLink = fileSystem.getPath("/dir/hard-link.txt");
            RarArkivoEntryAttributes hardLinkAttributes =
                    Files.readAttributes(hardLink, RarArkivoEntryAttributes.class);
            assertEquals(true, hardLinkAttributes.isRegularFile());
            assertEquals(false, hardLinkAttributes.isOther());
            assertEquals(RarArkivoEntryAttributes.REDIRECTION_TYPE_HARD_LINK, hardLinkAttributes.redirectionType());
            assertEquals("dir/hello.txt", hardLinkAttributes.redirectionTarget());
            assertEquals(content.length, hardLinkAttributes.size());
            assertArrayEquals(content, Files.readAllBytes(hardLink));

            Path fileCopy = fileSystem.getPath("/dir/file-copy.txt");
            RarArkivoEntryAttributes fileCopyAttributes =
                    Files.readAttributes(fileCopy, RarArkivoEntryAttributes.class);
            assertEquals(true, fileCopyAttributes.isRegularFile());
            assertEquals(false, fileCopyAttributes.isOther());
            assertEquals(RarArkivoEntryAttributes.REDIRECTION_TYPE_FILE_COPY, fileCopyAttributes.redirectionType());
            assertEquals("dir/hello.txt", fileCopyAttributes.redirectionTarget());
            assertEquals(content.length, fileCopyAttributes.size());
            assertArrayEquals(content, Files.readAllBytes(fileCopy));

            Map<String, Object> selectedBasicAttributes = Files.readAttributes(file, "basic:size,isRegularFile");
            assertEquals((long) content.length, selectedBasicAttributes.get("size"));
            assertEquals(true, selectedBasicAttributes.get("isRegularFile"));
            assertEquals(false, selectedBasicAttributes.containsKey("packedSize"));

            Map<String, Object> selectedRarAttributes = Files.readAttributes(
                    file,
                    "rar:path,hostOs,fileAttributes,compressionMethod,packedSize,unpackedSize,dataCrc32,"
                            + "blake2spHash,userName,groupName,userId,groupId"
            );
            assertEquals("dir/hello.txt", selectedRarAttributes.get("path"));
            assertEquals(RarArkivoEntryAttributes.HOST_OS_UNIX, selectedRarAttributes.get("hostOs"));
            assertEquals(0100644L, selectedRarAttributes.get("fileAttributes"));
            assertEquals(0, selectedRarAttributes.get("compressionMethod"));
            assertEquals((long) content.length, selectedRarAttributes.get("packedSize"));
            assertEquals((long) content.length, selectedRarAttributes.get("unpackedSize"));
            assertEquals(crc32(content), selectedRarAttributes.get("dataCrc32"));
            assertArrayEquals(hash, (byte[]) selectedRarAttributes.get("blake2spHash"));
            assertEquals("alice", selectedRarAttributes.get("userName"));
            assertEquals("staff", selectedRarAttributes.get("groupName"));
            assertEquals(1000L, selectedRarAttributes.get("userId"));
            assertEquals(1001L, selectedRarAttributes.get("groupId"));

            Map<String, Object> ownerNamedAttributes = Files.readAttributes(file, "owner:owner");
            assertEquals("alice", ((UserPrincipal) ownerNamedAttributes.get("owner")).getName());

            Map<String, Object> posixNamedAttributes =
                    Files.readAttributes(file, "posix:owner,group,permissions,isRegularFile");
            assertEquals("alice", ((UserPrincipal) posixNamedAttributes.get("owner")).getName());
            assertEquals("staff", ((GroupPrincipal) posixNamedAttributes.get("group")).getName());
            assertEquals(filePermissions, posixNamedAttributes.get("permissions"));
            assertEquals(true, posixNamedAttributes.get("isRegularFile"));

            byte[] namedHash = Objects.requireNonNull(
                    (byte[]) Files.readAttributes(file, "rar:blake2spHash").get("blake2spHash"),
                    "namedHash"
            );
            namedHash[0] = 99;
            assertArrayEquals(hash, (byte[]) Files.readAttributes(file, "rar:blake2spHash").get("blake2spHash"));

            Path splitFile = fileSystem.getPath("/dir/split.txt");
            RarArkivoEntryAttributes splitAttributes = Files.readAttributes(splitFile, RarArkivoEntryAttributes.class);
            assertEquals(splitContent.length, splitAttributes.size());
            assertEquals(splitFirstPart.length, splitAttributes.packedSize());
            assertEquals(splitContent.length, splitAttributes.unpackedSize());
            assertEquals(splitCrc32, splitAttributes.dataCrc32());
            assertEquals(false, splitAttributes.continuesFromPreviousVolume());
            assertEquals(true, splitAttributes.continuesInNextVolume());
            assertArrayEquals(splitContent, Files.readAllBytes(splitFile));

            Path link = fileSystem.getPath("/link");
            RarArkivoEntryAttributes linkAttributes = Files.readAttributes(link, RarArkivoEntryAttributes.class);
            assertEquals(true, linkAttributes.isSymbolicLink());
            assertEquals(fileSystem.getPath("dir/hello.txt"), Files.readSymbolicLink(link));
            Map<String, Object> selectedLinkAttributes = Files.readAttributes(
                    link,
                    "rar:isSymbolicLink,linkName,redirectionType,redirectionFlags,redirectionTarget,"
                            + "redirectionTargetDirectory"
            );
            assertEquals(true, selectedLinkAttributes.get("isSymbolicLink"));
            assertEquals("dir/hello.txt", selectedLinkAttributes.get("linkName"));
            assertEquals(
                    RarArkivoEntryAttributes.REDIRECTION_TYPE_UNIX_SYMLINK,
                    selectedLinkAttributes.get("redirectionType")
            );
            assertEquals(0L, selectedLinkAttributes.get("redirectionFlags"));
            assertEquals("dir/hello.txt", selectedLinkAttributes.get("redirectionTarget"));
            assertEquals(false, selectedLinkAttributes.get("redirectionTargetDirectory"));

            try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
                assertEquals(content.length, channel.size());
                channel.position(1);
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(2);
                assertEquals(2, channel.read(buffer));
                buffer.flip();
                assertEquals((byte) 'e', buffer.get());
                assertEquals((byte) 'l', buffer.get());
            }

            ArrayList<String> children = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path child : stream) {
                    children.add(child.toString());
                }
            }
            assertEquals(
                    Set.of(
                            "/dir/hello.txt",
                            "/dir/hard-link.txt",
                            "/dir/file-copy.txt",
                            "/dir/split.txt",
                            "/dir/compressed.bin"
                    ),
                    Set.copyOf(children)
            );

            IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(fileSystem.getPath("/dir/compressed.bin")));
            assertEquals(true, exception.getMessage().contains("content is not available"));
            assertThrows(ReadOnlyFileSystemException.class, () -> Files.delete(file));
        }

        assertThrows(ClosedFileSystemException.class, () -> fileSystem.getPath("/dir"));
        Files.deleteIfExists(existingFile);
        Files.deleteIfExists(copiedDirectory);
        deleteTemporaryArchive(archivePath);
    }

    /// Verifies that a repeatable seekable channel source supports random-access RAR file system operations.
    @Test
    public void randomAccessFileSystemFromSeekableChannelSource() throws IOException {
        byte[] content = "seekable channel source content".getBytes(StandardCharsets.UTF_8);
        TestSeekableChannelSource source = new TestSeekableChannelSource(archive(
                storedFile("hello.txt", 0, 0100644, content, null)
        ));

        try (RarArkivoFileSystem fileSystem = RarArkivoFormat.instance().open(source)) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/hello.txt")));
            assertEquals(true, source.openCount() > 0);
            assertEquals(true, source.allOpenedChannelsClosed());
            assertEquals(0, source.closeCount());
        }

        assertEquals(true, source.allOpenedChannelsClosed());
        assertEquals(1, source.closeCount());
    }

    /// Verifies that failed RAR parsing closes a seekable channel source and every channel opened from it.
    @Test
    public void failedSeekableChannelSourceOpenClosesSource() throws IOException {
        TestSeekableChannelSource source = new TestSeekableChannelSource(new byte[0]);

        assertThrows(IOException.class, () -> RarArkivoFileSystem.open(source, Map.of()));

        assertEquals(true, source.openCount() > 0);
        assertEquals(true, source.allOpenedChannelsClosed());
        assertEquals(1, source.closeCount());
    }

    /// Verifies that stored RAR5 entries can be read from explicit archive volumes.
    @Test
    public void opensStoredSplitEntryFromVolumeSource() throws IOException {
        byte[] firstPart = "volume ".getBytes(StandardCharsets.UTF_8);
        byte[] secondPart = "source".getBytes(StandardCharsets.UTF_8);
        byte[] content = concatenate(firstPart, secondPart);
        long contentCrc32 = crc32(content);
        Path firstVolume = createTemporaryArchivePath("rar-volumes-");
        Path secondVolume = firstVolume.getParent().resolve("sample.part2.rar");
        Files.write(firstVolume, concatenate(
                archiveVolume(
                        true,
                        true,
                        splitStoredFilePart(
                                "split.txt",
                                1_700_000_000L,
                                0100644,
                                content.length,
                                contentCrc32,
                                firstPart,
                                false,
                                true
                        )
                ),
                new byte[15]
        ));
        Files.write(secondVolume, archiveVolume(
                true,
                splitStoredFilePart(
                        "split.txt",
                        1_700_000_000L,
                        0100644,
                        content.length,
                        contentCrc32,
                        secondPart,
                        true,
                        false
                ),
                storedFile("after.txt", 1_700_000_001L, 0100644, "after".getBytes(StandardCharsets.UTF_8), null)
        ));

        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(ArkivoVolumeSource.of(List.of(
                    firstVolume,
                    secondVolume
            )))) {
                Path splitFile = fileSystem.getPath("/split.txt");
                assertArrayEquals(content, Files.readAllBytes(splitFile));
                RarArkivoEntryAttributes attributes = Files.readAttributes(splitFile, RarArkivoEntryAttributes.class);
                assertEquals(firstPart.length, attributes.packedSize());
                assertEquals(content.length, attributes.unpackedSize());
                assertEquals(true, attributes.continuesInNextVolume());
                assertThrows(UnsupportedOperationException.class, splitFile::toUri);

                assertArrayEquals(
                        "after".getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(fileSystem.getPath("/after.txt"))
                );
            }
        } finally {
            Files.deleteIfExists(secondVolume);
            deleteTemporaryArchive(firstVolume);
        }
    }

    /// Verifies conventional RAR paths are discovered and streamed without caller-managed volume lists.
    @Test
    public void streamsStoredSplitEntryFromConventionalPaths() throws IOException {
        byte[] firstPart = "path ".getBytes(StandardCharsets.UTF_8);
        byte[] secondPart = "volumes".getBytes(StandardCharsets.UTF_8);
        byte[] content = concatenate(firstPart, secondPart);
        long contentCrc32 = crc32(content);
        Path firstVolume = createTemporaryArchivePath("rar-path-volumes-");
        Path secondVolume = firstVolume.resolveSibling("sample.part2.rar");
        Files.write(firstVolume, concatenate(
                archiveVolume(
                        true,
                        true,
                        splitStoredFilePart(
                                "split.txt",
                                1_700_000_000L,
                                0100644,
                                content.length,
                                contentCrc32,
                                firstPart,
                                false,
                                true
                        )
                ),
                new byte[15]
        ));
        Files.write(secondVolume, archiveVolume(
                true,
                splitStoredFilePart(
                        "split.txt",
                        1_700_000_000L,
                        0100644,
                        content.length,
                        contentCrc32,
                        secondPart,
                        true,
                        false
                ),
                storedFile("after.txt", 1_700_000_001L, 0100644, "after".getBytes(StandardCharsets.UTF_8), null)
        ));

        try {
            List<Path> discoveredPaths = Objects.requireNonNull(
                    RarArkivoFormat.instance().discoverVolumePaths(firstVolume)
            );
            assertEquals(List.of(firstVolume, secondVolume), discoveredPaths);
            assertThrows(UnsupportedOperationException.class, discoveredPaths::clear);

            try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(firstVolume)) {
                assertEquals(true, reader.next());
                assertEquals("split.txt", reader.readAttributes(RarArkivoEntryAttributes.class).path());
                try (InputStream input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }

                assertEquals(true, reader.next());
                assertEquals("after.txt", reader.readAttributes(RarArkivoEntryAttributes.class).path());
                try (InputStream input = reader.openInputStream()) {
                    assertArrayEquals("after".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
                }
                assertEquals(false, reader.next());
            }
        } finally {
            Files.deleteIfExists(secondVolume);
            deleteTemporaryArchive(firstVolume);
        }
    }

    /// Verifies the public streaming reader merges a stored entry across real RAR5 volumes and owns its source.
    @Test
    public void streamsStoredSplitEntryFromVolumeSource() throws IOException {
        byte[] firstPart = "streaming ".getBytes(StandardCharsets.UTF_8);
        byte[] secondPart = "volumes".getBytes(StandardCharsets.UTF_8);
        byte[] content = concatenate(firstPart, secondPart);
        long contentCrc32 = crc32(content);
        TrackingRarVolumeSource source = new TrackingRarVolumeSource(new byte[][]{
                concatenate(
                        archiveVolume(
                                true,
                                true,
                                splitStoredFilePart(
                                        "split.txt",
                                        1_700_000_000L,
                                        0100644,
                                        content.length,
                                        contentCrc32,
                                        firstPart,
                                        false,
                                        true
                                )
                        ),
                        new byte[15]
                ),
                archiveVolume(
                        true,
                        splitStoredFilePart(
                                "split.txt",
                                1_700_000_000L,
                                0100644,
                                content.length,
                                contentCrc32,
                                secondPart,
                                true,
                                false
                        ),
                        storedFile(
                                "after.txt",
                                1_700_000_001L,
                                0100644,
                                "after".getBytes(StandardCharsets.UTF_8),
                                null
                        )
                )
        });

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(source)) {
            assertEquals(true, reader.next());
            assertEquals("split.txt", reader.readAttributes(RarArkivoEntryAttributes.class).path());
            try (InputStream input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            assertEquals("after.txt", reader.readAttributes(RarArkivoEntryAttributes.class).path());
            try (InputStream input = reader.openInputStream()) {
                assertArrayEquals("after".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }

        assertEquals(1, source.closeCount());
        assertEquals(true, source.allOpenedChannelsClosed());
    }
    /// Verifies that stored RAR4 entries split across explicit archive volumes can be read.
    @Test
    public void opensStoredRar4SplitEntryFromVolumeSource() throws IOException {
        byte[] firstPart = "rar4 volume ".getBytes(StandardCharsets.UTF_8);
        byte[] secondPart = "source".getBytes(StandardCharsets.UTF_8);
        byte[] content = concatenate(firstPart, secondPart);
        long contentCrc32 = crc32(content);
        Path firstVolume = createTemporaryArchivePath("rar4-volumes-");
        Path secondVolume = firstVolume.getParent().resolve("sample.part2.rar");
        Files.write(firstVolume, rar4ArchiveVolume(false, splitStoredFilePart(
                "split.txt",
                1_700_000_000L,
                0100644,
                content.length,
                contentCrc32,
                firstPart,
                false,
                true
        )));
        Files.write(secondVolume, rar4ArchiveVolume(
                true,
                splitStoredFilePart(
                        "split.txt",
                        1_700_000_000L,
                        0100644,
                        content.length,
                        contentCrc32,
                        secondPart,
                        true,
                        false
                ),
                storedFile("after.txt", 1_700_000_001L, 0100644, "after".getBytes(StandardCharsets.UTF_8), null)
        ));

        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(ArkivoVolumeSource.of(List.of(
                    firstVolume,
                    secondVolume
            )))) {
                Path splitFile = fileSystem.getPath("/split.txt");
                assertArrayEquals(content, Files.readAllBytes(splitFile));
                RarArkivoEntryAttributes attributes = Files.readAttributes(splitFile, RarArkivoEntryAttributes.class);
                assertEquals(firstPart.length, attributes.packedSize());
                assertEquals(content.length, attributes.unpackedSize());
                assertEquals(true, attributes.continuesInNextVolume());

                assertArrayEquals(
                        "after".getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(fileSystem.getPath("/after.txt"))
                );
            }
        } finally {
            Files.deleteIfExists(secondVolume);
            deleteTemporaryArchive(firstVolume);
        }
    }

    /// Verifies normal RAR5 compressed-body decoding from a forward-only source.
    @Test
    public void readsCompressedRar5Entry() throws IOException {
        byte[] archive = archive(splitCompressedFilePart(
                "numbers.txt",
                1_700_000_000L,
                0100644,
                RAR5_COMPRESSED_CONTENT.length,
                crc32(RAR5_COMPRESSED_CONTENT),
                RAR5_COMPRESSED_BODY,
                blake2spHash(RAR5_COMPRESSED_BLAKE2SP),
                false,
                false
        ));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            assertEquals(true, reader.next());
            assertEquals(
                    3,
                    reader.readAttributes(RarArkivoEntryAttributes.class).compressionMethod()
            );
            try (InputStream input = reader.openInputStream()) {
                assertArrayEquals(RAR5_COMPRESSED_CONTENT, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Rejects decompressed RAR5 content whose BLAKE2sp hash does not match.
    @Test
    public void rejectsCompressedRar5Blake2spMismatch() throws IOException {
        byte[] wrongHash = RAR5_COMPRESSED_BLAKE2SP.clone();
        wrongHash[0] ^= 1;
        byte[] archive = archive(splitCompressedFilePart(
                "numbers.txt",
                1_700_000_000L,
                0100644,
                RAR5_COMPRESSED_CONTENT.length,
                crc32(RAR5_COMPRESSED_CONTENT),
                RAR5_COMPRESSED_BODY,
                blake2spHash(wrongHash),
                false,
                false
        ));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            assertEquals(true, reader.next());
            IOException exception = assertThrows(IOException.class, () -> {
                try (InputStream input = reader.openInputStream()) {
                    input.readAllBytes();
                }
            });
            assertEquals(true, exception.getMessage().contains("BLAKE2sp"));
        }
    }

    /// Verifies RAR5 decompression when packed data crosses a physical volume boundary.
    @Test
    public void opensCompressedRar5SplitEntryFromVolumeSource() throws IOException {
        int splitOffset = 17;
        byte[] firstPart = Arrays.copyOfRange(RAR5_COMPRESSED_BODY, 0, splitOffset);
        byte[] secondPart = Arrays.copyOfRange(
                RAR5_COMPRESSED_BODY,
                splitOffset,
                RAR5_COMPRESSED_BODY.length
        );
        Path firstVolume = createTemporaryArchivePath("rar5-compressed-volumes-");
        Path secondVolume = firstVolume.resolveSibling("sample.part2.rar");
        Files.write(firstVolume, archiveVolume(false, splitCompressedFilePart(
                "numbers.txt",
                1_700_000_000L,
                0100644,
                RAR5_COMPRESSED_CONTENT.length,
                0L,
                firstPart,
                false,
                true
        )));
        Files.write(secondVolume, archiveVolume(
                true,
                splitCompressedFilePart(
                        "numbers.txt",
                        1_700_000_000L,
                        0100644,
                        RAR5_COMPRESSED_CONTENT.length,
                        crc32(RAR5_COMPRESSED_CONTENT),
                        secondPart,
                        blake2spHash(RAR5_COMPRESSED_BLAKE2SP),
                        true,
                        false
                ),
                storedFile(
                        "after.txt",
                        1_700_000_001L,
                        0100644,
                        "after".getBytes(StandardCharsets.UTF_8),
                        null
                )
        ));

        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                    ArkivoVolumeSource.of(List.of(firstVolume, secondVolume))
            )) {
                assertArrayEquals(
                        RAR5_COMPRESSED_CONTENT,
                        Files.readAllBytes(fileSystem.getPath("/numbers.txt"))
                );
                assertArrayEquals(
                        "after".getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(fileSystem.getPath("/after.txt"))
                );
            }
        } finally {
            Files.deleteIfExists(secondVolume);
            deleteTemporaryArchive(firstVolume);
        }
    }


    /// Verifies that compressed RAR4 entries use the final continuation CRC after packed data crosses volumes.
    @Test
    public void opensCompressedRar4SplitEntryFromVolumeSource() throws IOException {
        byte[] content = "file1\n".getBytes(StandardCharsets.UTF_8);
        byte[] firstPart = Arrays.copyOfRange(RAR4_COMPRESSED_BODY, 0, 10);
        byte[] secondPart = Arrays.copyOfRange(RAR4_COMPRESSED_BODY, 10, RAR4_COMPRESSED_BODY.length);
        Path firstVolume = createTemporaryArchivePath("rar4-compressed-volumes-");
        Path secondVolume = firstVolume.getParent().resolve("sample.part2.rar");
        Files.write(firstVolume, rar4ArchiveVolume(false, splitCompressedFilePart(
                "file1.txt",
                1_700_000_000L,
                0100644,
                content.length,
                0L,
                firstPart,
                false,
                true
        )));
        Files.write(secondVolume, rar4ArchiveVolume(
                true,
                splitCompressedFilePart(
                        "file1.txt",
                        1_700_000_000L,
                        0100644,
                        content.length,
                        crc32(content),
                        secondPart,
                        true,
                        false
                ),
                storedFile("after.txt", 1_700_000_001L, 0100644, "after".getBytes(StandardCharsets.UTF_8), null)
        ));

        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(ArkivoVolumeSource.of(List.of(
                    firstVolume,
                    secondVolume
            )))) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/file1.txt")));
                assertArrayEquals(
                        "after".getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(fileSystem.getPath("/after.txt"))
                );
            }
        } finally {
            Files.deleteIfExists(secondVolume);
            deleteTemporaryArchive(firstVolume);
        }
    }

    /// Verifies that one RAR5 AES stream can span an unaligned physical volume boundary.
    @Test
    public void opensRar5EncryptedStoredSplitEntryFromVolumeSource() throws IOException {
        byte[] content = "RAR5 encrypted content spanning two volumes".getBytes(StandardCharsets.UTF_8);
        TestRar5Keys keys = deriveRar5Keys(RAR5_PASSWORD, RAR5_FILE_SALT, RAR5_KDF_LOG);
        byte[] ciphertext = encryptRar5Aes(content, keys.aesKey(), RAR5_FILE_IV);
        int splitOffset = 7;
        byte[] firstPart = Arrays.copyOfRange(ciphertext, 0, splitOffset);
        byte[] secondPart = Arrays.copyOfRange(ciphertext, splitOffset, ciphertext.length);
        byte[] encryptionRecord = fileEncryptionRecord(keys, true);
        long finalCrc32 = keyedRar5Crc32(crc32(content), keys.hashKey());
        byte[] finalHash = keyedRar5Blake2sp(ENCRYPTED_SPLIT_STORED_BLAKE2SP, keys.hashKey());
        byte[] finalExtraArea = concatenate(encryptionRecord, blake2spHash(finalHash));
        Path firstVolume = createTemporaryArchivePath("rar5-encrypted-volumes-");
        Path secondVolume = firstVolume.resolveSibling("sample.part2.rar");
        Files.write(firstVolume, archiveVolume(false, encryptedSplitFilePart(
                "secret.txt",
                0,
                crc32(firstPart),
                content.length,
                firstPart,
                encryptionRecord,
                false,
                true
        )));
        Files.write(secondVolume, archiveVolume(
                true,
                encryptedSplitFilePart(
                        "secret.txt",
                        0,
                        finalCrc32,
                        content.length,
                        secondPart,
                        finalExtraArea,
                        true,
                        false
                ),
                storedFile("after.txt", 1_700_000_001L, 0100644, "after".getBytes(StandardCharsets.UTF_8), null)
        ));

        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                    ArkivoVolumeSource.of(List.of(firstVolume, secondVolume)),
                    rar5PasswordEnvironment(RAR5_PASSWORD)
            )) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/secret.txt")));
                assertArrayEquals(
                        "after".getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(fileSystem.getPath("/after.txt"))
                );
            }
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                    ArkivoVolumeSource.of(List.of(firstVolume, secondVolume))
            )) {
                Path encryptedPath = fileSystem.getPath("/secret.txt");
                assertEquals(
                        true,
                        Files.readAttributes(encryptedPath, RarArkivoEntryAttributes.class).isEncrypted()
                );
                IOException unavailable = assertThrows(IOException.class, () -> Files.readAllBytes(encryptedPath));
                assertEquals(true, unavailable.getMessage().contains("content is not available"));
                assertArrayEquals(
                        "after".getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(fileSystem.getPath("/after.txt"))
                );
            }
        } finally {
            Files.deleteIfExists(secondVolume);
            deleteTemporaryArchive(firstVolume);
        }
    }

    /// Verifies RAR5 decompression over an AES stream split inside one cipher block.
    @Test
    public void opensRar5EncryptedCompressedSplitEntryFromVolumeSource() throws IOException {
        TestRar5Keys keys = deriveRar5Keys(RAR5_PASSWORD, RAR5_FILE_SALT, RAR5_KDF_LOG);
        byte[] ciphertext = encryptRar5Aes(RAR5_COMPRESSED_BODY, keys.aesKey(), RAR5_FILE_IV);
        int splitOffset = 7;
        byte[] firstPart = Arrays.copyOfRange(ciphertext, 0, splitOffset);
        byte[] secondPart = Arrays.copyOfRange(ciphertext, splitOffset, ciphertext.length);
        byte[] encryptionRecord = fileEncryptionRecord(keys, true);
        long finalCrc32 = keyedRar5Crc32(crc32(RAR5_COMPRESSED_CONTENT), keys.hashKey());
        byte[] finalHash = keyedRar5Blake2sp(RAR5_COMPRESSED_BLAKE2SP, keys.hashKey());
        byte[] finalExtraArea = concatenate(encryptionRecord, blake2spHash(finalHash));
        Path firstVolume = createTemporaryArchivePath("rar5-encrypted-compressed-volumes-");
        Path secondVolume = firstVolume.resolveSibling("sample.part2.rar");
        Files.write(firstVolume, archiveVolume(false, encryptedSplitFilePart(
                "numbers.txt",
                3,
                0L,
                RAR5_COMPRESSED_CONTENT.length,
                firstPart,
                encryptionRecord,
                false,
                true
        )));
        Files.write(secondVolume, archiveVolume(
                true,
                encryptedSplitFilePart(
                        "numbers.txt",
                        3,
                        finalCrc32,
                        RAR5_COMPRESSED_CONTENT.length,
                        secondPart,
                        finalExtraArea,
                        true,
                        false
                ),
                storedFile(
                        "after.txt",
                        1_700_000_001L,
                        0100644,
                        "after".getBytes(StandardCharsets.UTF_8),
                        null
                )
        ));

        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                    ArkivoVolumeSource.of(List.of(firstVolume, secondVolume)),
                    rar5PasswordEnvironment(RAR5_PASSWORD)
            )) {
                assertArrayEquals(
                        RAR5_COMPRESSED_CONTENT,
                        Files.readAllBytes(fileSystem.getPath("/numbers.txt"))
                );
                assertArrayEquals(
                        "after".getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(fileSystem.getPath("/after.txt"))
                );
            }
        } finally {
            Files.deleteIfExists(secondVolume);
            deleteTemporaryArchive(firstVolume);
        }
    }


    /// Verifies that one RAR 3.x AES stream can span an unaligned physical volume boundary.
    @Test
    public void opensRar4EncryptedStoredSplitEntryFromVolumeSource() throws IOException {
        byte[] content = "RAR4 encrypted content spanning two volumes".getBytes(StandardCharsets.UTF_8);
        TestRar3Keys keys = deriveRar3Keys(RAR3_PASSWORD, RAR3_FILE_SALT);
        byte[] ciphertext = encryptRar3Aes(content, keys.key(), keys.initializationVector());
        int splitOffset = 9;
        byte[] firstPart = Arrays.copyOfRange(ciphertext, 0, splitOffset);
        byte[] secondPart = Arrays.copyOfRange(ciphertext, splitOffset, ciphertext.length);
        Path firstVolume = createTemporaryArchivePath("rar4-encrypted-volumes-");
        Path secondVolume = firstVolume.resolveSibling("sample.r00");
        Files.write(firstVolume, rar4EncryptedArchiveVolume(
                false,
                new Rar4EncryptedFixture(
                        encryptedSplitFilePart(
                                "secret.txt",
                                0,
                                crc32(firstPart),
                                content.length,
                                firstPart,
                                null,
                                false,
                                true
                        ),
                        RAR3_FILE_SALT
                )
        ));
        Files.write(secondVolume, rar4EncryptedArchiveVolume(
                true,
                new Rar4EncryptedFixture(
                        encryptedSplitFilePart(
                                "secret.txt",
                                0,
                                crc32(content),
                                content.length,
                                secondPart,
                                null,
                                true,
                                false
                        ),
                        RAR3_FILE_SALT
                ),
                storedFile("after.txt", 1_700_000_001L, 0100644, "after".getBytes(StandardCharsets.UTF_8), null)
        ));

        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                    ArkivoVolumeSource.of(List.of(firstVolume, secondVolume)),
                    rar3PasswordEnvironment(RAR3_PASSWORD)
            )) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/secret.txt")));
                assertArrayEquals(
                        "after".getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(fileSystem.getPath("/after.txt"))
                );
            }
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                    ArkivoVolumeSource.of(List.of(firstVolume, secondVolume))
            )) {
                Path encryptedPath = fileSystem.getPath("/secret.txt");
                assertEquals(
                        true,
                        Files.readAttributes(encryptedPath, RarArkivoEntryAttributes.class).isEncrypted()
                );
                IOException unavailable = assertThrows(IOException.class, () -> Files.readAllBytes(encryptedPath));
                assertEquals(true, unavailable.getMessage().contains("content is not available"));
                assertArrayEquals(
                        "after".getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(fileSystem.getPath("/after.txt"))
                );
            }
        } finally {
            Files.deleteIfExists(secondVolume);
            deleteTemporaryArchive(firstVolume);
        }
    }

    /// Verifies RAR4 decompression over an encrypted stream split inside an AES block.
    @Test
    public void opensRar4EncryptedCompressedSplitEntryFromVolumeSource() throws IOException {
        byte[] content = "file1\n".getBytes(StandardCharsets.UTF_8);
        TestRar3Keys keys = deriveRar3Keys(RAR3_PASSWORD, RAR3_FILE_SALT);
        byte[] ciphertext = encryptRar3Aes(
                RAR4_COMPRESSED_BODY,
                keys.key(),
                keys.initializationVector()
        );
        int splitOffset = 11;
        byte[] firstPart = Arrays.copyOfRange(ciphertext, 0, splitOffset);
        byte[] secondPart = Arrays.copyOfRange(ciphertext, splitOffset, ciphertext.length);
        Path firstVolume = createTemporaryArchivePath("rar4-encrypted-compressed-volumes-");
        Path secondVolume = firstVolume.resolveSibling("sample.r00");
        Files.write(firstVolume, rar4EncryptedArchiveVolume(
                false,
                new Rar4EncryptedFixture(
                        encryptedSplitFilePart(
                                "file1.txt",
                                3,
                                crc32(firstPart),
                                content.length,
                                firstPart,
                                null,
                                false,
                                true
                        ),
                        RAR3_FILE_SALT
                )
        ));
        Files.write(secondVolume, rar4EncryptedArchiveVolume(
                true,
                new Rar4EncryptedFixture(
                        encryptedSplitFilePart(
                                "file1.txt",
                                3,
                                crc32(content),
                                content.length,
                                secondPart,
                                null,
                                true,
                                false
                        ),
                        RAR3_FILE_SALT
                ),
                storedFile("after.txt", 1_700_000_001L, 0100644, "after".getBytes(StandardCharsets.UTF_8), null)
        ));

        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                    ArkivoVolumeSource.of(List.of(firstVolume, secondVolume)),
                    rar3PasswordEnvironment(RAR3_PASSWORD)
            )) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/file1.txt")));
                assertArrayEquals(
                        "after".getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(fileSystem.getPath("/after.txt"))
                );
            }
        } finally {
            Files.deleteIfExists(secondVolume);
            deleteTemporaryArchive(firstVolume);
        }
    }

    /// Verifies that an encrypted continuation cannot silently switch to plaintext data.
    @Test
    public void rejectsPlainContinuationOfEncryptedSplitEntry() throws IOException {
        byte[] content = "encrypted continuation state".getBytes(StandardCharsets.UTF_8);
        TestRar5Keys keys = deriveRar5Keys(RAR5_PASSWORD, RAR5_FILE_SALT, RAR5_KDF_LOG);
        byte[] ciphertext = encryptRar5Aes(content, keys.aesKey(), RAR5_FILE_IV);
        int splitOffset = 5;
        byte[] firstPart = Arrays.copyOfRange(ciphertext, 0, splitOffset);
        byte[] secondPart = Arrays.copyOfRange(ciphertext, splitOffset, ciphertext.length);
        byte[] archive = archive(
                encryptedSplitFilePart(
                        "secret.txt",
                        0,
                        crc32(firstPart),
                        content.length,
                        firstPart,
                        fileEncryptionRecord(keys, false),
                        false,
                        true
                ),
                encryptedSplitFilePart(
                        "secret.txt",
                        0,
                        crc32(content),
                        content.length,
                        secondPart,
                        null,
                        true,
                        false
                )
        );

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                rar5PasswordEnvironment(RAR5_PASSWORD)
        )) {
            assertEquals(true, reader.next());
            var input = reader.openInputStream();
            IOException exception = assertThrows(IOException.class, input::readAllBytes);
            assertEquals(true, exception.getMessage().contains("continuation encryption state differs"));
            assertThrows(IOException.class, input::close);
        }
    }

    /// Verifies that the combined encrypted stream must end on an AES block boundary.
    @Test
    public void rejectsTruncatedEncryptedSplitEntry() throws IOException {
        byte[] content = "encrypted stream needs two blocks".getBytes(StandardCharsets.UTF_8);
        TestRar5Keys keys = deriveRar5Keys(RAR5_PASSWORD, RAR5_FILE_SALT, RAR5_KDF_LOG);
        byte[] ciphertext = encryptRar5Aes(content, keys.aesKey(), RAR5_FILE_IV);
        int splitOffset = 7;
        byte[] firstPart = Arrays.copyOfRange(ciphertext, 0, splitOffset);
        byte[] secondPart = Arrays.copyOfRange(ciphertext, splitOffset, ciphertext.length - 1);
        byte[] encryptionRecord = fileEncryptionRecord(keys, false);
        byte[] archive = archive(
                encryptedSplitFilePart(
                        "secret.txt",
                        0,
                        crc32(firstPart),
                        content.length,
                        firstPart,
                        encryptionRecord,
                        false,
                        true
                ),
                encryptedSplitFilePart(
                        "secret.txt",
                        0,
                        crc32(content),
                        content.length,
                        secondPart,
                        encryptionRecord,
                        true,
                        false
                )
        );

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                rar5PasswordEnvironment(RAR5_PASSWORD)
        )) {
            assertEquals(true, reader.next());
            var input = reader.openInputStream();
            IOException exception = assertThrows(IOException.class, input::readAllBytes);
            assertEquals(true, exception.getMessage().contains("Unexpected end of encrypted RAR"));
            assertThrows(IOException.class, input::close);
        }
    }

    /// Verifies that RAR4 and RAR5 end markers can direct top-level iteration to a following volume.
    @Test
    public void readsEntriesAfterNextVolumeEndMarker() throws IOException {
        Member first = storedFile(
                "first.txt",
                1_700_000_000L,
                0100644,
                "first".getBytes(StandardCharsets.UTF_8),
                null
        );
        Member second = storedFile(
                "second.txt",
                1_700_000_001L,
                0100644,
                "second".getBytes(StandardCharsets.UTF_8),
                null
        );

        assertEndMarkedVolumes(
                concatenate(archiveVolume(true, true, first), new byte[15]),
                archiveVolume(true, false, second)
        );
        assertEndMarkedVolumes(
                rar4ArchiveVolume(true, true, first),
                rar4ArchiveVolume(true, false, second)
        );
    }

    /// Verifies two entries exposed by volumes separated with a next-volume end marker.
    private static void assertEndMarkedVolumes(byte[] firstVolume, byte[] secondVolume) throws IOException {
        List<byte[]> contents = List.of(firstVolume, secondVolume);
        ArkivoVolumeSource volumes = index -> index >= 0L && index < contents.size()
                ? new TestByteArraySeekableChannel(contents.get((int) index))
                : null;
        try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(volumes)) {
            assertArrayEquals(
                    "first".getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(fileSystem.getPath("/first.txt"))
            );
            assertArrayEquals(
                    "second".getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(fileSystem.getPath("/second.txt"))
            );
        }
    }

    /// Verifies that stored RAR5 entries can be read from conventional `partN.rar` paths.
    @Test
    public void opensStoredSplitEntryFromPartPath() throws IOException {
        byte[] firstPart = "part path ".getBytes(StandardCharsets.UTF_8);
        byte[] secondPart = "source".getBytes(StandardCharsets.UTF_8);
        byte[] content = concatenate(firstPart, secondPart);
        long contentCrc32 = crc32(content);
        Path firstVolume = createTemporaryArchivePath("rar-part-path-").resolveSibling("sample.part1.rar");
        Path secondVolume = firstVolume.resolveSibling("sample.part2.rar");
        Files.write(firstVolume, archiveVolume(false, splitStoredFilePart(
                "split.txt",
                1_700_000_000L,
                0100644,
                content.length,
                contentCrc32,
                firstPart,
                false,
                true
        )));
        Files.write(secondVolume, archiveVolume(
                true,
                splitStoredFilePart(
                        "split.txt",
                        1_700_000_000L,
                        0100644,
                        content.length,
                        contentCrc32,
                        secondPart,
                        true,
                        false
                ),
                storedFile("after.txt", 1_700_000_001L, 0100644, "after".getBytes(StandardCharsets.UTF_8), null)
        ));

        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(firstVolume)) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/split.txt")));
                assertArrayEquals(
                        "after".getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(fileSystem.getPath("/after.txt"))
                );
            }
        } finally {
            Files.deleteIfExists(secondVolume);
            deleteTemporaryArchive(firstVolume);
        }
    }

    /// Verifies that stored RAR4 entries can be read from legacy `.rar` and `.r00` paths.
    @Test
    public void opensStoredRar4SplitEntryFromLegacyPath() throws IOException {
        byte[] firstPart = "rar4 legacy ".getBytes(StandardCharsets.UTF_8);
        byte[] secondPart = "path".getBytes(StandardCharsets.UTF_8);
        byte[] content = concatenate(firstPart, secondPart);
        long contentCrc32 = crc32(content);
        Path firstVolume = createTemporaryArchivePath("rar4-legacy-path-");
        Path secondVolume = firstVolume.resolveSibling("sample.r00");
        Files.write(firstVolume, rar4ArchiveVolume(false, splitStoredFilePart(
                "split.txt",
                1_700_000_000L,
                0100644,
                content.length,
                contentCrc32,
                firstPart,
                false,
                true
        )));
        Files.write(secondVolume, rar4ArchiveVolume(
                true,
                splitStoredFilePart(
                        "split.txt",
                        1_700_000_000L,
                        0100644,
                        content.length,
                        contentCrc32,
                        secondPart,
                        true,
                        false
                ),
                storedFile("after.txt", 1_700_000_001L, 0100644, "after".getBytes(StandardCharsets.UTF_8), null)
        ));

        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(firstVolume)) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/split.txt")));
                assertArrayEquals(
                        "after".getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(fileSystem.getPath("/after.txt"))
                );
            }
        } finally {
            Files.deleteIfExists(secondVolume);
            deleteTemporaryArchive(firstVolume);
        }
    }

    /// Verifies that provider URI entry points register and unregister RAR file systems.
    @Test
    public void providerUriLifecycleSupportsEntryPaths() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("rar-provider-");
        Files.write(archivePath, archive(storedFile(
                "dir/hello.txt",
                1_700_000_000L,
                0100644,
                content,
                null
        )));

        RarArkivoFileSystemProvider provider = RarArkivoFileSystemProvider.instance();
        URI archiveUri = archivePath.toUri().normalize();
        URI fileSystemUri = URI.create(RarArkivoFileSystemProvider.SCHEME + ":" + archiveUri.toASCIIString());
        URI entryUri = URI.create(RarArkivoFileSystemProvider.SCHEME + ":" + archiveUri.toASCIIString() + "!/dir/hello.txt");

        try {
            FileSystem fileSystem = provider.newFileSystem(fileSystemUri, Map.of());
            try (fileSystem) {
                assertEquals(fileSystem, provider.getFileSystem(fileSystemUri));
                assertThrows(FileSystemAlreadyExistsException.class, () -> provider.newFileSystem(fileSystemUri, Map.of()));

                Path entry = provider.getPath(entryUri);
                assertEquals(entryUri, entry.toUri());
                assertArrayEquals(content, Files.readAllBytes(entry));
            }

            assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(fileSystemUri));

            try (FileSystem reopenedFileSystem = provider.newFileSystem(fileSystemUri, Map.of())) {
                assertEquals(reopenedFileSystem, provider.getFileSystem(fileSystemUri));
                assertArrayEquals(content, Files.readAllBytes(provider.getPath(entryUri)));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that compressed RAR entries are visible but not exposed as stored data.
    @Test
    public void rejectsUnsupportedCompressionMethodOnOpen() throws IOException {
        byte[] archive = archive(compressedFile("compressed.bin", 1_700_000_000L, 0100644, new byte[]{1, 2, 3}, 6));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals("compressed.bin", attributes.path());
            assertEquals(6, attributes.compressionMethod());

            IOException exception = assertThrows(IOException.class, reader::openInputStream);
            assertEquals(true, exception.getMessage().contains("Unsupported RAR compression method"));
            assertEquals(false, reader.next());
        }
    }

    /// Verifies RAR 3.x AES-128 stored entry streaming, CRC validation, and file-system caching.
    @Test
    public void readsRar3EncryptedStoredEntry() throws IOException {
        byte[] content = "RAR3 encrypted stored content".getBytes(StandardCharsets.UTF_8);
        byte[] after = "after".getBytes(StandardCharsets.UTF_8);
        Rar4EncryptedFixture encrypted = rar4EncryptedStoredFile("secret.txt", content, RAR3_FILE_SALT);
        byte[] archive = rar4EncryptedArchive(
                encrypted,
                storedFile("after.txt", 1_700_000_001L, 0100644, after, null)
        );
        Map<String, Object> environment = rar3PasswordEnvironment(RAR3_PASSWORD);

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                environment
        )) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals("secret.txt", attributes.path());
            assertEquals(true, attributes.isEncrypted());
            assertEquals(content.length, attributes.unpackedSize());
            assertEquals((content.length + 15) & ~15, attributes.packedSize());
            try (var body = reader.openInputStream()) {
                assertArrayEquals(content, body.readAllBytes());
            }
            assertEquals(true, reader.next());
            try (var body = reader.openInputStream()) {
                assertArrayEquals(after, body.readAllBytes());
            }
            assertEquals(false, reader.next());
        }

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                environment
        )) {
            assertEquals(true, reader.next());
            try (var body = reader.openInputStream()) {
                assertArrayEquals(Arrays.copyOf(content, 4), body.readNBytes(4));
            }
            assertEquals(true, reader.next());
            assertEquals("after.txt", reader.readAttributes(RarArkivoEntryAttributes.class).path());
        }

        Path archivePath = createTemporaryArchivePath("rar3-encrypted-entry-");
        Files.write(archivePath, archive);
        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(archivePath, environment)) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/secret.txt")));
                assertArrayEquals(after, Files.readAllBytes(fileSystem.getPath("/after.txt")));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies unsalted RAR 3.x AES file encryption used by older archives.
    @Test
    public void readsUnsaltedRar3EncryptedStoredEntry() throws IOException {
        byte[] content = "unsalted RAR3 content".getBytes(StandardCharsets.UTF_8);
        byte[] archive = rar4EncryptedArchive(rar4EncryptedStoredFile("unsalted.txt", content, null));
        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                Channels.newChannel(new ByteArrayInputStream(archive)),
                rar3PasswordEnvironment(RAR3_PASSWORD)
        )) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals(true, attributes.isEncrypted());
            assertEquals((content.length + 15) & ~15, attributes.packedSize());
            try (var body = reader.openInputStream()) {
                assertArrayEquals(content, body.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies metadata-only access without a password and CRC-based RAR3 wrong-password detection.
    @Test
    public void rejectsIncorrectRar3EntryPassword() throws IOException {
        byte[] content = "RAR3 password check".getBytes(StandardCharsets.UTF_8);
        byte[] archive = rar4EncryptedArchive(rar4EncryptedStoredFile(
                "secret.txt",
                content,
                RAR3_FILE_SALT
        ));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            assertEquals(true, reader.readAttributes(RarArkivoEntryAttributes.class).isEncrypted());
            assertEquals(false, reader.next());
        }

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                rar3PasswordEnvironment("wrong".getBytes(StandardCharsets.UTF_16LE))
        )) {
            assertEquals(true, reader.next());
            var body = reader.openInputStream();
            IOException exception = assertThrows(IOException.class, body::readAllBytes);
            assertEquals(true, exception.getMessage().contains("Invalid RAR entry CRC32"));
            assertThrows(IOException.class, body::close);
        }
    }


    /// Verifies independently salted RAR 3.x encrypted headers and file data through the indexed file system.
    @Test
    public void readsRar3EncryptedHeadersThroughFileSystem() throws IOException {
        byte[] content = "hidden RAR3 name and content".getBytes(StandardCharsets.UTF_8);
        byte[] archive = rar4EncryptedHeaderArchive(rar4EncryptedStoredFile(
                "hidden/value.txt",
                content,
                RAR3_FILE_SALT
        ));
        Path archivePath = createTemporaryArchivePath("rar3-encrypted-headers-");
        Files.write(archivePath, archive);
        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                    archivePath,
                    rar3PasswordEnvironment(RAR3_PASSWORD)
            )) {
                Path entry = fileSystem.getPath("/hidden/value.txt");
                assertArrayEquals(content, Files.readAllBytes(entry));
                assertEquals(true, Files.readAttributes(entry, RarArkivoEntryAttributes.class).isEncrypted());
            }

            IOException incorrectPassword = assertThrows(
                    IOException.class,
                    () -> RarArkivoFileSystem.open(
                            archivePath,
                            rar3PasswordEnvironment("wrong".getBytes(StandardCharsets.UTF_16LE))
                    )
            );
            assertEquals(true, incorrectPassword.getMessage().contains("Incorrect RAR3 archive password"));

            IOException missingPassword = assertThrows(
                    IOException.class,
                    () -> RarArkivoFileSystem.open(archivePath)
            );
            assertEquals(true, missingPassword.getMessage().contains("password provider is required"));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that damaged RAR 3.x encrypted headers are rejected before exposing entry metadata.
    @Test
    public void rejectsCorruptRar3EncryptedHeader() throws IOException {
        Rar4EncryptedFixture encrypted = rar4EncryptedStoredFile(
                "damaged.txt",
                "damaged header".getBytes(StandardCharsets.UTF_8),
                RAR3_FILE_SALT
        );
        byte[] archive = rar4EncryptedHeaderArchive(encrypted);
        int mainHeaderSize = rar4BlockHeader(0x73, 0x0080L, new byte[6]).length;
        int memberHeaderSize = rar4MemberHeader(encrypted.member(), true, encrypted.salt()).length;
        int encryptedMemberHeaderSize = (memberHeaderSize + 15) & ~15;
        int lastCiphertextByte = RAR4_SIGNATURE.length
                + mainHeaderSize
                + RAR3_FILE_SALT.length
                + encryptedMemberHeaderSize
                - 1;
        archive[lastCiphertextByte] ^= 0x01;

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                rar3PasswordEnvironment(RAR3_PASSWORD)
        )) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("Incorrect RAR3 archive password or corrupt encrypted header"));
        }
    }

    /// Verifies that independently encrypted RAR4 volumes reinstall header state after each volume signature.
    @Test
    public void readsRar3EncryptedHeadersAcrossVolumes() throws IOException {
        byte[] firstContent = "first RAR3 encrypted volume".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "second RAR3 encrypted volume".getBytes(StandardCharsets.UTF_8);
        byte[] firstVolume = rar4EncryptedHeaderArchiveVolume(
                true,
                0,
                rar4EncryptedStoredFile("first.txt", firstContent, RAR3_FILE_SALT)
        );
        byte[] secondVolume = rar4EncryptedHeaderArchiveVolume(
                false,
                16,
                rar4EncryptedStoredFile("second.txt", secondContent, RAR3_FILE_SALT)
        );
        List<byte[]> contents = List.of(firstVolume, secondVolume);
        ArkivoVolumeSource volumes = index -> index >= 0L && index < contents.size()
                ? new TestByteArraySeekableChannel(contents.get((int) index))
                : null;

        try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                volumes,
                rar3PasswordEnvironment(RAR3_PASSWORD)
        )) {
            assertArrayEquals(firstContent, Files.readAllBytes(fileSystem.getPath("/first.txt")));
            assertArrayEquals(secondContent, Files.readAllBytes(fileSystem.getPath("/second.txt")));
        }
    }

    /// Verifies that every provider-owned RAR3 password result is cleared after key derivation.
    @Test
    public void clearsRar3PasswordProviderResults() throws IOException {
        byte[] content = "cleared RAR3 password".getBytes(StandardCharsets.UTF_8);
        byte[] archive = rar4EncryptedHeaderArchive(rar4EncryptedStoredFile(
                "secret.txt",
                content,
                RAR3_FILE_SALT
        ));
        List<byte[]> suppliedPasswords = new ArrayList<>();
        ArkivoPasswordProvider passwordProvider = () -> {
            byte[] password = RAR3_PASSWORD.clone();
            suppliedPasswords.add(password);
            return password;
        };
        Path archivePath = createTemporaryArchivePath("rar3-password-clear-");
        Files.write(archivePath, archive);
        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                    archivePath,
                    Map.of(RarArkivoFileSystem.PASSWORD_PROVIDER.key(), passwordProvider)
            )) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/secret.txt")));
            }
            assertEquals(3, suppliedPasswords.size());
            for (byte[] suppliedPassword : suppliedPasswords) {
                assertArrayEquals(new byte[suppliedPassword.length], suppliedPassword);
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies RAR5 AES-encrypted stored entry streaming, password checks, tweaked CRC32, and partial-body draining.
    @Test
    public void readsRar5EncryptedStoredEntry() throws IOException {
        byte[] content = "RAR5 encrypted stored content".getBytes(StandardCharsets.UTF_8);
        byte[] after = "after".getBytes(StandardCharsets.UTF_8);
        byte[] archive = archive(
                encryptedStoredFile("secret.txt", content, true),
                storedFile("after.txt", 1_700_000_001L, 0100644, after, null)
        );
        Map<String, Object> environment = rar5PasswordEnvironment(RAR5_PASSWORD);

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                environment
        )) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals("secret.txt", attributes.path());
            assertEquals(true, attributes.isEncrypted());
            assertEquals(content.length, attributes.unpackedSize());
            assertEquals((content.length + 15) & ~15, attributes.packedSize());
            try (var body = reader.openInputStream()) {
                assertArrayEquals(content, body.readAllBytes());
            }
            assertEquals(true, reader.next());
            try (var body = reader.openInputStream()) {
                assertArrayEquals(after, body.readAllBytes());
            }
            assertEquals(false, reader.next());
        }

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                environment
        )) {
            assertEquals(true, reader.next());
            try (var body = reader.openInputStream()) {
                assertArrayEquals(Arrays.copyOf(content, 5), body.readNBytes(5));
            }
            assertEquals(true, reader.next());
            assertEquals("after.txt", reader.readAttributes(RarArkivoEntryAttributes.class).path());
        }
    }

    /// Verifies the channel environment overload and zero-length AES entry boundary.
    @Test
    public void readsEmptyRar5EncryptedStoredEntryFromChannel() throws IOException {
        byte[] archive = archive(encryptedStoredFile("empty.bin", new byte[0], true));
        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                Channels.newChannel(new ByteArrayInputStream(archive)),
                rar5PasswordEnvironment(RAR5_PASSWORD)
        )) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals(0L, attributes.packedSize());
            assertEquals(0L, attributes.unpackedSize());
            try (var body = reader.openInputStream()) {
                assertArrayEquals(new byte[0], body.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that an archive can index encrypted file metadata without retaining unreadable ciphertext as content.
    @Test
    public void indexesRar5EncryptedStoredEntryWithoutPasswordProvider() throws IOException {
        byte[] archive = archive(encryptedStoredFile(
                "secret.txt",
                "secret".getBytes(StandardCharsets.UTF_8),
                true
        ));
        Path archivePath = createTemporaryArchivePath("rar5-encrypted-metadata-");
        Files.write(archivePath, archive);
        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(archivePath)) {
                Path entry = fileSystem.getPath("/secret.txt");
                assertEquals(true, Files.readAttributes(entry, RarArkivoEntryAttributes.class).isEncrypted());
                IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(entry));
                assertEquals(true, exception.getMessage().contains("content is not available"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that metadata can be skipped without a password and an incorrect password is rejected before data.
    @Test
    public void rejectsIncorrectRar5EntryPassword() throws IOException {
        byte[] archive = archive(encryptedStoredFile(
                "secret.txt",
                "secret".getBytes(StandardCharsets.UTF_8),
                false
        ));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            assertEquals(true, reader.readAttributes(RarArkivoEntryAttributes.class).isEncrypted());
            assertEquals(false, reader.next());
        }

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                rar5PasswordEnvironment("wrong".getBytes(StandardCharsets.UTF_8))
        )) {
            assertEquals(true, reader.next());
            IOException exception = assertThrows(IOException.class, reader::openInputStream);
            assertEquals(true, exception.getMessage().contains("Incorrect RAR5 password"));
        }
    }

    /// Verifies that encrypted RAR5 headers and entry data are available through the indexed file system.
    @Test
    public void readsRar5EncryptedHeadersThroughFileSystem() throws IOException {
        byte[] content = "hidden name and content".getBytes(StandardCharsets.UTF_8);
        byte[] archive = encryptedHeaderArchive(encryptedStoredFile("hidden/value.txt", content, true));
        Path archivePath = createTemporaryArchivePath("rar5-encrypted-headers-");
        Files.write(archivePath, archive);
        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                    archivePath,
                    rar5PasswordEnvironment(RAR5_PASSWORD)
            )) {
                Path entry = fileSystem.getPath("/hidden/value.txt");
                assertArrayEquals(content, Files.readAllBytes(entry));
                assertEquals(true, Files.readAttributes(entry, RarArkivoEntryAttributes.class).isEncrypted());
            }

            IOException exception = assertThrows(
                    IOException.class,
                    () -> RarArkivoFileSystem.open(
                            archivePath,
                            rar5PasswordEnvironment("wrong".getBytes(StandardCharsets.UTF_8))
                    )
            );
            assertEquals(true, exception.getMessage().contains("Incorrect RAR5 archive password"));

            IOException missingPassword = assertThrows(
                    IOException.class,
                    () -> RarArkivoFileSystem.open(archivePath)
            );
            assertEquals(true, missingPassword.getMessage().contains("password provider is required"));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that provider-owned return values are cleared after header and entry key derivation.
    @Test
    public void clearsRar5PasswordProviderResults() throws IOException {
        byte[] content = "cleared password".getBytes(StandardCharsets.UTF_8);
        byte[] archive = encryptedHeaderArchive(encryptedStoredFile("secret.txt", content, true));
        List<byte[]> suppliedPasswords = new ArrayList<>();
        ArkivoPasswordProvider passwordProvider = () -> {
            byte[] password = RAR5_PASSWORD.clone();
            suppliedPasswords.add(password);
            return password;
        };
        Path archivePath = createTemporaryArchivePath("rar5-password-clear-");
        Files.write(archivePath, archive);
        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                    archivePath,
                    Map.of(RarArkivoFileSystem.PASSWORD_PROVIDER.key(), passwordProvider)
            )) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/secret.txt")));
            }
            assertEquals(2, suppliedPasswords.size());
            for (byte[] suppliedPassword : suppliedPasswords) {
                assertArrayEquals(new byte[suppliedPassword.length], suppliedPassword);
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that each encrypted RAR5 volume authenticates and installs its own header key.
    @Test
    public void readsRar5EncryptedHeadersAcrossVolumes() throws IOException {
        byte[] firstContent = "first encrypted volume".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "second encrypted volume".getBytes(StandardCharsets.UTF_8);
        Path firstVolume = createTemporaryArchivePath("rar5-encrypted-volume-");
        Path secondVolume = firstVolume.resolveSibling("encrypted.part2.rar");
        Files.write(firstVolume, encryptedHeaderArchiveVolume(
                true,
                0,
                encryptedStoredFile("first.txt", firstContent, true)
        ));
        Files.write(secondVolume, encryptedHeaderArchiveVolume(
                false,
                32,
                encryptedStoredFile("second.txt", secondContent, true)
        ));

        try {
            try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                    ArkivoVolumeSource.of(List.of(firstVolume, secondVolume)),
                    rar5PasswordEnvironment(RAR5_PASSWORD)
            )) {
                assertArrayEquals(firstContent, Files.readAllBytes(fileSystem.getPath("/first.txt")));
                assertArrayEquals(secondContent, Files.readAllBytes(fileSystem.getPath("/second.txt")));
            }
        } finally {
            Files.deleteIfExists(secondVolume);
            deleteTemporaryArchive(firstVolume);
        }
    }

    /// Verifies that password-dependent CRC32 mismatches are reported after decryption.
    @Test
    public void rejectsCorruptedRar5EncryptedStoredEntry() throws IOException {
        Member encrypted = encryptedStoredFile(
                "corrupt.bin",
                "corrupt me".getBytes(StandardCharsets.UTF_8),
                true
        );
        Member wrongCrc = new Member(
                encrypted.path(),
                encrypted.modificationTime(),
                encrypted.attributes(),
                encrypted.directory(),
                encrypted.symbolicLink(),
                encrypted.compressionMethod(),
                encrypted.crc32() ^ 1L,
                encrypted.unpackedSize(),
                encrypted.body(),
                encrypted.extraArea(),
                encrypted.service(),
                encrypted.continuesFromPreviousVolume(),
                encrypted.continuesInNextVolume()
        );

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive(wrongCrc)),
                rar5PasswordEnvironment(RAR5_PASSWORD)
        )) {
            assertEquals(true, reader.next());
            var body = reader.openInputStream();
            IOException exception = assertThrows(IOException.class, body::readAllBytes);
            assertEquals(true, exception.getMessage().contains("Invalid RAR entry CRC32"));
            IOException closeException = assertThrows(IOException.class, body::close);
            assertEquals(true, closeException.getMessage().contains("Invalid RAR entry CRC32"));
            body.close();
        }

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive(wrongCrc)),
                rar5PasswordEnvironment(RAR5_PASSWORD)
        )) {
            assertEquals(true, reader.next());
            var body = reader.openInputStream();
            assertEquals(true, body.read() >= 0);
            IOException exception = assertThrows(IOException.class, body::close);
            assertEquals(true, exception.getMessage().contains("Invalid RAR entry CRC32"));
        }
    }

    /// Verifies that RAR5 high precision Unix file time extra records are exposed through NIO attributes.
    @Test
    public void readsUnixFileTimeExtraRecord() throws IOException {
        byte[] archive = archive(storedFile(
                "times.txt",
                1L,
                0100644,
                "time".getBytes(StandardCharsets.UTF_8),
                unixFileTimes(
                        1_700_000_000L,
                        123_456_789L,
                        1_700_000_001L,
                        987_654_321L,
                        1_700_000_002L,
                        1L
                )
        ));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);

            assertEquals(Instant.ofEpochSecond(1_700_000_000L, 123_456_789L), attributes.lastModifiedTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_700_000_001L, 987_654_321L), attributes.creationTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_700_000_002L, 1L), attributes.lastAccessTime().toInstant());
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that RAR5 Windows FILETIME extra records are converted to NIO file times.
    @Test
    public void readsWindowsFileTimeExtraRecord() throws IOException {
        byte[] archive = archive(storedFile(
                "windows-times.txt",
                1L,
                0100644,
                "time".getBytes(StandardCharsets.UTF_8),
                windowsFileTimes(
                        Instant.ofEpochSecond(1_700_000_010L, 100L),
                        Instant.ofEpochSecond(1_700_000_011L, 200L),
                        Instant.ofEpochSecond(1_700_000_012L, 300L)
                )
        ));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);

            assertEquals(Instant.ofEpochSecond(1_700_000_010L, 100L), attributes.lastModifiedTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_700_000_011L, 200L), attributes.creationTime().toInstant());
            assertEquals(Instant.ofEpochSecond(1_700_000_012L, 300L), attributes.lastAccessTime().toInstant());
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that RAR5 BLAKE2sp file hash extra records are exposed as entry metadata.
    @Test
    public void readsBlake2spHashExtraRecord() throws IOException {
        byte[] hash = HASH_CONTENT_BLAKE2SP.clone();
        byte[] archive = archive(storedFile(
                "hash.txt",
                1L,
                0100644,
                "hash".getBytes(StandardCharsets.UTF_8),
                blake2spHash(hash)
        ));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes attributes = reader.readAttributes(RarArkivoEntryAttributes.class);

            byte @Nullable [] firstHash = attributes.blake2spHash();
            assertArrayEquals(hash, firstHash);
            Objects.requireNonNull(firstHash, "firstHash")[0] = 99;
            assertArrayEquals(hash, attributes.blake2spHash());
            assertEquals(false, reader.next());
        }
    }

    /// Rejects a stored body whose BLAKE2sp hash does not match while reading.
    @Test
    public void rejectsStoredBlake2spMismatchWhileReading() throws IOException {
        byte[] wrongHash = HASH_CONTENT_BLAKE2SP.clone();
        wrongHash[0] ^= 1;
        byte[] archive = archive(storedFile(
                "hash.txt",
                1L,
                0100644,
                "hash".getBytes(StandardCharsets.UTF_8),
                blake2spHash(wrongHash)
        ));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            assertEquals(true, reader.next());
            IOException exception = assertThrows(IOException.class, () -> {
                try (InputStream input = reader.openInputStream()) {
                    input.readAllBytes();
                }
            });
            assertEquals(true, exception.getMessage().contains("BLAKE2sp"));
        }
    }

    /// Rejects a mismatched BLAKE2sp hash when an unread stored body is skipped.
    @Test
    public void rejectsStoredBlake2spMismatchWhileSkipping() throws IOException {
        byte[] wrongHash = HASH_CONTENT_BLAKE2SP.clone();
        wrongHash[0] ^= 1;
        byte[] archive = archive(
                storedFile(
                        "hash.txt",
                        1L,
                        0100644,
                        "hash".getBytes(StandardCharsets.UTF_8),
                        blake2spHash(wrongHash)
                ),
                storedFile("after.txt", 2L, 0100644, new byte[0], null)
        );

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            assertEquals(true, reader.next());
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("BLAKE2sp"));
        }
    }

    /// Verifies that entries without a RAR5 file hash extra record expose no BLAKE2sp hash.
    @Test
    public void exposesNullWhenBlake2spHashIsAbsent() throws IOException {
        byte[] archive = archive(storedFile("no-hash.txt", 0, 0100644, new byte[0], null));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());

            assertNull(reader.readAttributes(RarArkivoEntryAttributes.class).blake2spHash());
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that unsafe member paths are rejected.
    @Test
    public void rejectsParentDirectoryEntryPath() throws IOException {
        byte[] archive = archive(storedFile("../evil.txt", 0, 0100644, new byte[0], null));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            IOException exception = assertThrows(IOException.class, reader::next);

            assertEquals(true, exception.getMessage().contains("must not contain .."));
        }
    }

    /// Verifies that stored RAR4 entries can be streamed and exposed through the file system API.
    @Test
    public void readsStoredRar4Entries() throws IOException {
        byte[] content = "rar4 stored content".getBytes(StandardCharsets.UTF_8);
        byte[] unicodeContent = "rar4 unicode content".getBytes(StandardCharsets.UTF_8);
        byte[] linkTarget = "dir/hello.txt".getBytes(StandardCharsets.UTF_8);
        String unicodePath = "unicod\u00e9/na\u00efve.txt";
        long modificationTime = 1_700_000_010L;
        byte[] archive = rar4Archive(
                directory("dir/", modificationTime, 040755),
                storedFile("dir/hello.txt", modificationTime, 0100644, content, null),
                storedFile(unicodePath, modificationTime, 0100644, unicodeContent, null),
                storedFile("link", modificationTime, 0120777, linkTarget, null)
        );

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            RarArkivoEntryAttributes directory = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals("dir/", directory.path());
            assertEquals(true, directory.isDirectory());
            assertEquals(RarArkivoEntryAttributes.HOST_OS_UNIX, directory.hostOs());
            assertEquals(0, directory.compressionMethod());
            assertEquals(FileTime.from(Instant.ofEpochSecond(modificationTime)), directory.lastModifiedTime());

            assertEquals(true, reader.next());
            RarArkivoEntryAttributes file = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals("dir/hello.txt", file.path());
            assertEquals(true, file.isRegularFile());
            assertEquals(RarArkivoEntryAttributes.HOST_OS_UNIX, file.hostOs());
            assertEquals(0, file.compressionMethod());
            assertEquals(content.length, file.packedSize());
            assertEquals(content.length, file.unpackedSize());
            assertEquals(crc32(content), file.dataCrc32());
            assertEquals(FileTime.from(Instant.ofEpochSecond(modificationTime)), file.lastModifiedTime());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            RarArkivoEntryAttributes unicodeFile = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals(unicodePath, unicodeFile.path());
            assertEquals(true, unicodeFile.isRegularFile());
            assertEquals(0, unicodeFile.compressionMethod());
            assertEquals(unicodeContent.length, unicodeFile.packedSize());
            assertEquals(unicodeContent.length, unicodeFile.unpackedSize());
            assertEquals(crc32(unicodeContent), unicodeFile.dataCrc32());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(unicodeContent, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            RarArkivoEntryAttributes link = reader.readAttributes(RarArkivoEntryAttributes.class);
            assertEquals("link", link.path());
            assertEquals(true, link.isSymbolicLink());
            assertEquals("dir/hello.txt", link.linkName());
            assertEquals(RarArkivoEntryAttributes.REDIRECTION_TYPE_UNIX_SYMLINK, link.redirectionType());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(new byte[0], input.readAllBytes());
            }

            assertEquals(false, reader.next());
        }

        Path archivePath = createTemporaryArchivePath("rar4-fs-");
        Files.write(archivePath, archive);
        try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(archivePath)) {
            Path file = fileSystem.getPath("/dir/hello.txt");
            assertArrayEquals(content, Files.readAllBytes(file));
            assertEquals(content.length, Files.size(file));
            assertArrayEquals(unicodeContent, Files.readAllBytes(fileSystem.getPath("/" + unicodePath)));
            Path link = fileSystem.getPath("/link");
            assertEquals(true, Files.isSymbolicLink(link));
            assertEquals(fileSystem.getPath("dir/hello.txt"), Files.readSymbolicLink(link));
        }
        deleteTemporaryArchive(archivePath);
    }

    /// Verifies that stored entry CRC32 mismatches are reported while draining the entry.
    @Test
    public void rejectsBadStoredEntryCrc32() throws IOException {
        byte[] archive = archive(storedFileWithCrc("bad.txt", 0, 0100644, "bad".getBytes(StandardCharsets.UTF_8), 0));

        try (RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());

            IOException exception = assertThrows(IOException.class, () -> {
                try (var input = reader.openInputStream()) {
                    input.readAllBytes();
                }
            });
            assertEquals(true, exception.getMessage().contains("Invalid RAR entry CRC32"));
        }
    }

    /// Verifies that reader close can retry source cleanup after failure.
    @Test
    public void readerCloseRetriesSourceCleanupAfterFailure() throws IOException {
        CloseFailingOnceInputStream source = new CloseFailingOnceInputStream(archive(
                storedFile("hello.txt", 0, 0100644, "hello".getBytes(StandardCharsets.UTF_8), null)
        ));
        RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(source);
        assertEquals(true, reader.next());

        IOException exception = assertThrows(IOException.class, reader::close);
        assertEquals("close failed", exception.getMessage());
        assertThrows(IOException.class, reader::next);
        assertEquals(1, source.closeCount());

        reader.close();
        reader.close();

        assertEquals(2, source.closeCount());
    }


    /// Verifies an owned multi-volume source is retried after its first close failure.
    @Test
    public void volumeReaderCloseRetriesSourceCleanupAfterFailure() throws IOException {
        TrackingRarVolumeSource source = new TrackingRarVolumeSource(
                new byte[][]{archive(storedFile(
                        "hello.txt",
                        0,
                        0100644,
                        "hello".getBytes(StandardCharsets.UTF_8),
                        null
                ))},
                true
        );
        RarArkivoStreamingReader reader = RarArkivoStreamingReader.open(source);
        assertEquals(true, reader.next());

        IOException exception = assertThrows(IOException.class, reader::close);
        assertEquals("source close failed", exception.getMessage());
        assertEquals(1, source.closeCount());
        assertEquals(true, source.allOpenedChannelsClosed());

        reader.close();
        reader.close();

        assertEquals(2, source.closeCount());
    }
    /// Creates the deterministic plaintext stored in the real RAR5 compressed-body fixture.
    private static byte[] numberedRar5Content() {
        byte[] content = new byte[512 * 4];
        for (int value = 0; value < 512; value++) {
            int offset = value * 4;
            content[offset] = (byte) ('0' + value / 100);
            content[offset + 1] = (byte) ('0' + value / 10 % 10);
            content[offset + 2] = (byte) ('0' + value % 10);
            content[offset + 3] = '\n';
        }
        return content;
    }
    /// Creates one RAR5 archive.
    private static byte[] archive(Member... members) throws IOException {
        return archiveVolume(true, members);
    }

    /// Creates one RAR4 archive.
    private static byte[] rar4Archive(Member... members) throws IOException {
        return rar4ArchiveVolume(true, members);
    }

    /// Creates one RAR4 archive containing an AES-encrypted stored member and optional plain members.
    private static byte[] rar4EncryptedArchive(
            Rar4EncryptedFixture encrypted,
            Member... plainMembers
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(RAR4_SIGNATURE);
        writeRar4Block(output, 0x73, 0, new byte[6], new byte[0]);
        writeRar4Member(output, encrypted.member(), true, encrypted.salt());
        for (Member member : plainMembers) {
            writeRar4Member(output, member);
        }
        writeRar4Block(output, 0x7b, 0, new byte[0], new byte[0]);
        return output.toByteArray();
    }

    /// Creates one RAR4 volume containing one AES-encrypted member and optional plain members.
    private static byte[] rar4EncryptedArchiveVolume(
            boolean includeEndHeader,
            Rar4EncryptedFixture encrypted,
            Member... plainMembers
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(RAR4_SIGNATURE);
        writeRar4Block(output, 0x73, 0, new byte[6], new byte[0]);
        writeRar4Member(output, encrypted.member(), true, encrypted.salt());
        for (Member member : plainMembers) {
            writeRar4Member(output, member);
        }
        if (includeEndHeader) {
            writeRar4Block(output, 0x7b, 0, new byte[0], new byte[0]);
        }
        return output.toByteArray();
    }


    /// Creates one RAR4 archive with independently salted AES-encrypted headers and file data.
    private static byte[] rar4EncryptedHeaderArchive(Rar4EncryptedFixture encrypted) throws IOException {
        return rar4EncryptedHeaderArchiveVolume(false, 0, encrypted);
    }

    /// Creates one independently header-encrypted RAR4 archive volume.
    private static byte[] rar4EncryptedHeaderArchiveVolume(
            boolean nextVolume,
            int headerSaltOffset,
            Rar4EncryptedFixture encrypted
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(RAR4_SIGNATURE);
        writeRar4Block(output, 0x73, 0x0080L, new byte[6], new byte[0]);

        writeRar3EncryptedHeader(
                output,
                rar4MemberHeader(encrypted.member(), true, encrypted.salt()),
                rar3HeaderSalt(headerSaltOffset)
        );
        output.write(encrypted.member().body());
        writeRar3EncryptedHeader(
                output,
                rar4BlockHeader(0x7b, nextVolume ? 0x0001L : 0L, new byte[0]),
                rar3HeaderSalt(headerSaltOffset + 1)
        );
        return output.toByteArray();
    }

    /// Writes one salt-prefixed, zero-padded RAR 3.x AES encrypted header.
    private static void writeRar3EncryptedHeader(
            ByteArrayOutputStream output,
            byte[] header,
            byte[] salt
    ) throws IOException {
        TestRar3Keys keys = deriveRar3Keys(RAR3_PASSWORD, salt);
        output.write(salt);
        output.write(encryptRar3Aes(header, keys.key(), keys.initializationVector()));
    }

    /// Returns one deterministic salt for a synthetic encrypted RAR4 header.
    private static byte[] rar3HeaderSalt(int index) {
        byte[] salt = RAR3_FILE_SALT.clone();
        salt[0] ^= (byte) (0x40 + index);
        return salt;
    }

    /// Creates one RAR4 archive volume.
    private static byte[] rar4ArchiveVolume(boolean includeEndHeader, Member... members) throws IOException {
        return rar4ArchiveVolume(includeEndHeader, false, members);
    }

    /// Creates one RAR4 archive volume with an optional next-volume end marker.
    private static byte[] rar4ArchiveVolume(
            boolean includeEndHeader,
            boolean nextVolume,
            Member... members
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(RAR4_SIGNATURE);
        writeRar4Block(output, 0x73, 0, new byte[6], new byte[0]);
        for (Member member : members) {
            writeRar4Member(output, member);
        }
        if (includeEndHeader) {
            writeRar4Block(output, 0x7b, nextVolume ? 0x0001L : 0L, new byte[0], new byte[0]);
        }
        return output.toByteArray();
    }

    /// Creates one RAR5 archive volume.
    private static byte[] archiveVolume(boolean includeEndHeader, Member... members) throws IOException {
        return archiveVolume(includeEndHeader, false, members);
    }

    /// Creates one RAR5 archive volume with an optional next-volume end marker.
    private static byte[] archiveVolume(
            boolean includeEndHeader,
            boolean nextVolume,
            Member... members
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(RAR5_SIGNATURE);
        writeBlock(output, 1, 0, fields(writer -> writer.writeVint(0)), new byte[0], new byte[0]);
        for (Member member : members) {
            writeMember(output, member);
        }
        if (includeEndHeader) {
            writeBlock(
                    output,
                    5,
                    0,
                    fields(writer -> writer.writeVint(nextVolume ? 0x0001L : 0L)),
                    new byte[0],
                    new byte[0]
            );
        }
        return output.toByteArray();
    }

    /// Creates one RAR5 archive whose headers and stored members are AES encrypted.
    private static byte[] encryptedHeaderArchive(Member... members) throws IOException {
        return encryptedHeaderArchiveVolume(false, 0, members);
    }

    /// Creates one RAR5 archive volume whose headers and stored members are AES encrypted.
    private static byte[] encryptedHeaderArchiveVolume(
            boolean nextVolume,
            int headerIndexOffset,
            Member... members
    ) throws IOException {
        byte[] headerSalt = headerSalt(headerIndexOffset);
        TestRar5Keys keys = deriveRar5Keys(RAR5_PASSWORD, headerSalt, RAR5_KDF_LOG);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(RAR5_SIGNATURE);
        writeBlock(
                output,
                4,
                0L,
                archiveEncryptionFields(keys, headerSalt),
                new byte[0],
                new byte[0]
        );

        int headerIndex = headerIndexOffset;
        writeEncryptedHeaderBlock(
                output,
                keys.aesKey(),
                headerInitializationVector(headerIndex++),
                1,
                0L,
                fields(writer -> writer.writeVint(0L)),
                new byte[0],
                new byte[0]
        );
        for (Member member : members) {
            writeEncryptedMember(output, keys.aesKey(), headerInitializationVector(headerIndex++), member);
        }
        writeEncryptedHeaderBlock(
                output,
                keys.aesKey(),
                headerInitializationVector(headerIndex),
                5,
                0L,
                fields(writer -> writer.writeVint(nextVolume ? 0x0001L : 0L)),
                new byte[0],
                new byte[0]
        );
        return output.toByteArray();
    }

    /// Writes one member whose header is protected by archive header encryption.
    private static void writeEncryptedMember(
            ByteArrayOutputStream output,
            byte[] headerKey,
            byte[] headerInitializationVector,
            Member member
    ) throws IOException {
        int type = member.service() ? 3 : 2;
        byte[] extraArea = member.service() ? new byte[0] : member.extraArea();
        writeEncryptedHeaderBlock(
                output,
                headerKey,
                headerInitializationVector,
                type,
                blockFlags(member),
                fileFields(member, !member.service()),
                extraArea,
                member.body()
        );
    }

    /// Writes one independently IV-protected encrypted header followed by its data area.
    private static void writeEncryptedHeaderBlock(
            ByteArrayOutputStream output,
            byte[] headerKey,
            byte[] initializationVector,
            int type,
            long flags,
            byte[] fields,
            byte[] extraArea,
            byte[] data
    ) throws IOException {
        byte[] header = blockHeader(type, flags, fields, extraArea, data.length);
        output.write(initializationVector);
        output.write(encryptRar5Aes(header, headerKey, initializationVector));
        output.write(data);
    }

    /// Returns deterministic per-header initialization vectors for encrypted-header fixtures.
    private static byte[] headerInitializationVector(int index) {
        byte[] result = RAR5_FILE_IV.clone();
        result[0] ^= (byte) (0x40 + index);
        return result;
    }

    /// Returns a deterministic per-volume salt for encrypted-header fixtures.
    private static byte[] headerSalt(int headerIndexOffset) {
        byte[] result = RAR5_HEADER_SALT.clone();
        result[result.length - 1] ^= (byte) headerIndexOffset;
        return result;
    }

    /// Encodes archive encryption header fields with password verification data.
    private static byte[] archiveEncryptionFields(TestRar5Keys keys, byte[] salt) throws IOException {
        return fields(writer -> {
            writer.writeVint(0L);
            writer.writeVint(0x0001L);
            writer.write(new byte[]{(byte) RAR5_KDF_LOG});
            writer.write(salt);
            writer.write(keys.passwordCheck());
            writer.write(passwordCheckChecksum(keys.passwordCheck()));
        });
    }

    /// Writes one RAR5 member block.
    private static void writeMember(ByteArrayOutputStream output, Member member) throws IOException {
        if (member.service()) {
            writeBlock(output, 3, blockFlags(member), fileFields(member, false), new byte[0], member.body());
            return;
        }
        writeBlock(output, 2, blockFlags(member), fileFields(member, true), member.extraArea(), member.body());
    }

    /// Writes one RAR4 member block.
    private static void writeRar4Member(ByteArrayOutputStream output, Member member) throws IOException {
        writeRar4Member(output, member, false, null);
    }

    /// Writes one RAR4 member block with optional RAR 3.x AES metadata.
    private static void writeRar4Member(
            ByteArrayOutputStream output,
            Member member,
            boolean encrypted,
            byte @Nullable [] encryptionSalt
    ) throws IOException {
        if (member.service()) {
            return;
        }

        output.write(rar4MemberHeader(member, encrypted, encryptionSalt));
        output.write(member.body());
    }

    /// Encodes one complete RAR4 member header with optional RAR 3.x AES metadata.
    private static byte[] rar4MemberHeader(
            Member member,
            boolean encrypted,
            byte @Nullable [] encryptionSalt
    ) throws IOException {
        return rar4MemberHeader(member, encrypted, encryptionSalt, 29);
    }

    /// Encodes one complete RAR4 member header with an explicit extraction version.
    private static byte[] rar4MemberHeader(
            Member member,
            boolean encrypted,
            byte @Nullable [] encryptionSalt,
            int extractionVersion
    ) throws IOException {
        if (extractionVersion < 0 || extractionVersion > 0xff) {
            throw new IllegalArgumentException("RAR4 extraction version must fit in one byte");
        }
        boolean unicodeName = needsRar4UnicodeName(member.path());
        byte[] name = rar4NameBytes(member.path(), unicodeName);
        ByteArrayOutputStream fields = new ByteArrayOutputStream();
        writeUInt32(fields, member.body().length);
        writeUInt32(fields, member.unpackedSize());
        fields.write(3);
        writeUInt32(fields, member.crc32());
        writeUInt32(fields, rar4DosTime(member.modificationTime()));
        fields.write(extractionVersion);
        fields.write(member.compressionMethod() == 0 ? 0x30 : 0x30 + member.compressionMethod());
        writeUInt16(fields, name.length);
        writeUInt32(fields, member.attributes());
        fields.write(name);
        if (encryptionSalt != null) {
            fields.write(encryptionSalt);
        }

        long flags = 0x8000L;
        if (member.continuesFromPreviousVolume()) {
            flags |= 0x0001L;
        }
        if (member.continuesInNextVolume()) {
            flags |= 0x0002L;
        }
        if (unicodeName) {
            flags |= RAR4_FILE_FLAG_UNICODE;
        }
        if (encrypted) {
            flags |= 0x0004L;
        }
        if (encryptionSalt != null) {
            flags |= 0x0400L;
        }
        return rar4BlockHeader(0x74, flags, fields.toByteArray());
    }

    /// Returns whether a RAR4 fixture name needs Unicode metadata.
    private static boolean needsRar4UnicodeName(String path) {
        for (int index = 0; index < path.length(); index++) {
            if (path.charAt(index) > 0x7f) {
                return true;
            }
        }
        return false;
    }

    /// Encodes a RAR4 fixture name.
    private static byte[] rar4NameBytes(String path, boolean unicodeName) {
        if (!unicodeName) {
            return path.getBytes(StandardCharsets.UTF_8);
        }

        byte[] fallbackName = rar4FallbackName(path);
        byte[] encodedName = rar4UnicodeNameData(path);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(fallbackName, 0, fallbackName.length);
        output.write(0);
        output.write(encodedName, 0, encodedName.length);
        return output.toByteArray();
    }

    /// Returns a single-byte fallback name for a RAR4 Unicode fixture name.
    private static byte[] rar4FallbackName(String path) {
        byte[] fallbackName = new byte[path.length()];
        for (int index = 0; index < path.length(); index++) {
            char character = path.charAt(index);
            fallbackName[index] = character <= 0x7f ? (byte) character : (byte) '?';
        }
        return fallbackName;
    }

    /// Encodes Unicode name data for a RAR4 fixture name.
    private static byte[] rar4UnicodeNameData(String path) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0);
        for (int offset = 0; offset < path.length(); ) {
            int groupLength = Math.min(4, path.length() - offset);
            int flags = 0;
            for (int index = 0; index < groupLength; index++) {
                flags |= 0x02 << (6 - index * 2);
            }
            output.write(flags);
            for (int index = 0; index < groupLength; index++) {
                char character = path.charAt(offset++);
                output.write(character & 0xff);
                output.write(character >>> 8);
            }
        }
        return output.toByteArray();
    }

    /// Returns encoded RAR5 block continuation flags for one member.
    private static long blockFlags(Member member) {
        long flags = 0L;
        if (member.continuesFromPreviousVolume()) {
            flags |= 0x0008L;
        }
        if (member.continuesInNextVolume()) {
            flags |= 0x0010L;
        }
        return flags;
    }

    /// Writes one complete RAR4 block.
    private static void writeRar4Block(
            ByteArrayOutputStream output,
            int type,
            long flags,
            byte[] fields,
            byte[] data
    ) throws IOException {
        output.write(rar4BlockHeader(type, flags, fields));
        output.write(data);
    }

    /// Encodes one complete RAR4 block header including its CRC16 field.
    private static byte[] rar4BlockHeader(int type, long flags, byte[] fields) throws IOException {
        ByteArrayOutputStream headerData = new ByteArrayOutputStream();
        headerData.write(type);
        writeUInt16(headerData, flags);
        writeUInt16(headerData, 7 + fields.length);
        headerData.write(fields);

        byte[] headerDataBytes = headerData.toByteArray();
        CRC32 headerCrc32 = new CRC32();
        headerCrc32.update(headerDataBytes, 0, headerDataBytes.length);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeUInt16(output, headerCrc32.getValue());
        output.write(headerDataBytes);
        return output.toByteArray();
    }

    /// Concatenates two byte arrays.
    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /// Creates a stored file member.
    private static Member storedFile(
            String path,
            long modificationTime,
            long attributes,
            byte[] body,
            @Nullable byte @Unmodifiable [] extraArea
    ) {
        return storedFileWithCrc(path, modificationTime, attributes, body, crc32(body), extraArea);
    }

    /// Creates a stored file member with an explicit CRC32.
    private static Member storedFileWithCrc(String path, long modificationTime, long attributes, byte[] body, long crc32) {
        return storedFileWithCrc(path, modificationTime, attributes, body, crc32, null);
    }

    /// Creates a stored file member with an explicit CRC32 and extra area.
    private static Member storedFileWithCrc(
            String path,
            long modificationTime,
            long attributes,
            byte[] body,
            long crc32,
            @Nullable byte @Unmodifiable [] extraArea
    ) {
        return new Member(
                path,
                modificationTime,
                attributes,
                false,
                false,
                0,
                crc32,
                body.length,
                body,
                extraArea,
                false,
                false,
                false
        );
    }

    /// Creates an AES-encrypted RAR 3.x stored member fixture.
    private static Rar4EncryptedFixture rar4EncryptedStoredFile(
            String path,
            byte[] content,
            byte @Nullable [] salt
    ) throws IOException {
        TestRar3Keys keys = deriveRar3Keys(RAR3_PASSWORD, salt);
        byte[] encryptedBody = encryptRar3Aes(content, keys.key(), keys.initializationVector());
        Member member = new Member(
                path,
                1_700_000_000L,
                0100644,
                false,
                false,
                0,
                crc32(content),
                content.length,
                encryptedBody,
                null,
                false,
                false,
                false
        );
        return new Rar4EncryptedFixture(member, salt);
    }

    /// Creates an AES-encrypted RAR5 stored member with plain or password-dependent CRC32 metadata.
    private static Member encryptedStoredFile(String path, byte[] content, boolean tweakedChecksum) throws IOException {
        TestRar5Keys keys = deriveRar5Keys(RAR5_PASSWORD, RAR5_FILE_SALT, RAR5_KDF_LOG);
        long contentCrc32 = crc32(content);
        long storedCrc32 = tweakedChecksum ? keyedRar5Crc32(contentCrc32, keys.hashKey()) : contentCrc32;
        byte[] encryptedBody = encryptRar5Aes(content, keys.aesKey(), RAR5_FILE_IV);
        byte[] encryptionRecord = fileEncryptionRecord(keys, tweakedChecksum);
        return new Member(
                path,
                1_700_000_000L,
                0100644,
                false,
                false,
                0,
                storedCrc32,
                content.length,
                encryptedBody,
                encryptionRecord,
                false,
                false,
                false
        );
    }

    /// Creates one encrypted physical file part with explicit compression and continuation metadata.
    private static Member encryptedSplitFilePart(
            String path,
            int compressionMethod,
            long crc32,
            long unpackedSize,
            byte[] body,
            byte @Nullable [] extraArea,
            boolean continuesFromPreviousVolume,
            boolean continuesInNextVolume
    ) {
        return new Member(
                path,
                1_700_000_000L,
                0100644,
                false,
                false,
                compressionMethod,
                crc32,
                unpackedSize,
                body,
                extraArea,
                false,
                continuesFromPreviousVolume,
                continuesInNextVolume
        );
    }

    /// Encodes one RAR5 file encryption extra record.
    private static byte[] fileEncryptionRecord(TestRar5Keys keys, boolean tweakedChecksum) throws IOException {
        return extraRecord(0x01, fields(writer -> {
            writer.writeVint(0L);
            writer.writeVint(tweakedChecksum ? 0x0003L : 0x0001L);
            writer.write(new byte[]{(byte) RAR5_KDF_LOG});
            writer.write(RAR5_FILE_SALT);
            writer.write(RAR5_FILE_IV);
            writer.write(keys.passwordCheck());
            writer.write(passwordCheckChecksum(keys.passwordCheck()));
        }));
    }

    /// Creates a stored file member that is split across physical archive parts.
    private static Member splitStoredFilePart(
            String path,
            long modificationTime,
            long attributes,
            long unpackedSize,
            long crc32,
            byte[] body,
            boolean continuesFromPreviousVolume,
            boolean continuesInNextVolume
    ) {
        return splitStoredFilePart(
                path,
                modificationTime,
                attributes,
                unpackedSize,
                crc32,
                body,
                null,
                continuesFromPreviousVolume,
                continuesInNextVolume
        );
    }

    /// Creates a stored physical file part with an optional extra area.
    private static Member splitStoredFilePart(
            String path,
            long modificationTime,
            long attributes,
            long unpackedSize,
            long crc32,
            byte[] body,
            byte @Nullable [] extraArea,
            boolean continuesFromPreviousVolume,
            boolean continuesInNextVolume
    ) {
        return new Member(
                path,
                modificationTime,
                attributes,
                false,
                false,
                0,
                crc32,
                unpackedSize,
                body,
                extraArea,
                false,
                continuesFromPreviousVolume,
                continuesInNextVolume
        );
    }

    /// Creates one physical part of a method-3 compressed file.
    private static Member splitCompressedFilePart(
            String path,
            long modificationTime,
            long attributes,
            long unpackedSize,
            long crc32,
            byte[] body,
            boolean continuesFromPreviousVolume,
            boolean continuesInNextVolume
    ) {
        return splitCompressedFilePart(
                path,
                modificationTime,
                attributes,
                unpackedSize,
                crc32,
                body,
                null,
                continuesFromPreviousVolume,
                continuesInNextVolume
        );
    }

    /// Creates one method-3 physical file part with an optional extra area.
    private static Member splitCompressedFilePart(
            String path,
            long modificationTime,
            long attributes,
            long unpackedSize,
            long crc32,
            byte[] body,
            byte @Nullable [] extraArea,
            boolean continuesFromPreviousVolume,
            boolean continuesInNextVolume
    ) {
        return new Member(
                path,
                modificationTime,
                attributes,
                false,
                false,
                3,
                crc32,
                unpackedSize,
                body,
                extraArea,
                false,
                continuesFromPreviousVolume,
                continuesInNextVolume
        );
    }

    /// Creates a compressed file member.
    private static Member compressedFile(
            String path,
            long modificationTime,
            long attributes,
            byte[] body,
            int compressionMethod
    ) {
        return new Member(
                path,
                modificationTime,
                attributes,
                false,
                false,
                compressionMethod,
                crc32(body),
                body.length,
                body,
                null,
                false,
                false,
                false
        );
    }

    /// Creates a directory member.
    private static Member directory(String path, long modificationTime, long attributes) {
        return new Member(path, modificationTime, attributes, true, false, 0, 0, 0, new byte[0], null, false, false, false);
    }

    /// Creates a symbolic link member.
    private static Member symbolicLink(String path, long modificationTime, long attributes, String target)
            throws IOException {
        return new Member(path, modificationTime, attributes, false, true, 0, 0, 0, new byte[0], symlink(target), false, false, false);
    }

    /// Creates a redirected entry member.
    private static Member redirectedEntry(
            String path,
            long modificationTime,
            long attributes,
            int redirectionType,
            long redirectionFlags,
            String target
    ) throws IOException {
        return new Member(
                path,
                modificationTime,
                attributes,
                false,
                false,
                0,
                0,
                0,
                new byte[0],
                redirection(redirectionType, redirectionFlags, target),
                false,
                false,
                false
        );
    }

    /// Creates a service header member.
    private static Member service(String name, byte[] body) {
        return new Member(name, 0, 0, false, false, 0, crc32(body), body.length, body, null, true, false, false);
    }

    /// Encodes RAR5 file header fields.
    private static byte[] fileFields(Member member, boolean includeCrc) throws IOException {
        return fields(writer -> {
            long fileFlags = 0x0002L;
            if (member.directory()) {
                fileFlags |= 0x0001L;
            }
            if (includeCrc) {
                fileFlags |= 0x0004L;
            }
            writer.writeVint(fileFlags);
            writer.writeVint(member.unpackedSize());
            writer.writeVint(member.attributes());
            writer.writeUInt32(member.modificationTime());
            if (includeCrc) {
                writer.writeUInt32(member.crc32());
            }
            writer.writeVint((long) member.compressionMethod() << 7);
            writer.writeVint(RarArkivoEntryAttributes.HOST_OS_UNIX);
            byte[] name = member.path().getBytes(StandardCharsets.UTF_8);
            writer.writeVint(name.length);
            writer.write(name);
        });
    }

    /// Writes one complete RAR5 block.
    private static void writeBlock(
            ByteArrayOutputStream output,
            int type,
            long flags,
            byte[] fields,
            byte[] extraArea,
            byte[] data
    ) throws IOException {
        output.write(blockHeader(type, flags, fields, extraArea, data.length));
        output.write(data);
    }

    /// Returns one complete RAR5 block header without its data area.
    private static byte[] blockHeader(
            int type,
            long flags,
            byte[] fields,
            byte[] extraArea,
            long dataSize
    ) throws IOException {
        ByteArrayOutputStream headerData = new ByteArrayOutputStream();
        VintWriter writer = new VintWriter(headerData);
        long headerFlags = flags;
        if (extraArea.length > 0) {
            headerFlags |= 0x0001L;
        }
        if (dataSize > 0L) {
            headerFlags |= 0x0002L;
        }
        writer.writeVint(type);
        writer.writeVint(headerFlags);
        if (extraArea.length > 0) {
            writer.writeVint(extraArea.length);
        }
        if (dataSize > 0L) {
            writer.writeVint(dataSize);
        }
        writer.write(fields);
        writer.write(extraArea);

        byte[] headerDataBytes = headerData.toByteArray();
        byte[] headerSizeBytes = vint(headerDataBytes.length);
        CRC32 headerCrc32 = new CRC32();
        headerCrc32.update(headerSizeBytes, 0, headerSizeBytes.length);
        headerCrc32.update(headerDataBytes, 0, headerDataBytes.length);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeUInt32(output, headerCrc32.getValue());
        output.write(headerSizeBytes);
        output.write(headerDataBytes);
        return output.toByteArray();
    }

    /// Creates a Unix owner extra area.
    private static byte[] owner(String userName, String groupName, long userId, long groupId) throws IOException {
        return extraRecord(0x06, fields(writer -> {
            writer.writeVint(0x000f);
            writeLengthPrefixedString(writer, userName);
            writeLengthPrefixedString(writer, groupName);
            writer.writeVint(userId);
            writer.writeVint(groupId);
        }));
    }

    /// Creates a symbolic link redirection extra area.
    private static byte[] symlink(String target) throws IOException {
        return redirection(RarArkivoEntryAttributes.REDIRECTION_TYPE_UNIX_SYMLINK, 0, target);
    }

    /// Creates a file system redirection extra area.
    private static byte[] redirection(int redirectionType, long redirectionFlags, String target) throws IOException {
        return extraRecord(0x05, fields(writer -> {
            writer.writeVint(redirectionType);
            writer.writeVint(redirectionFlags);
            writeLengthPrefixedString(writer, target);
        }));
    }

    /// Creates a BLAKE2sp file hash extra area.
    private static byte[] blake2spHash(byte[] hash) throws IOException {
        if (hash.length != 32) {
            throw new IllegalArgumentException("hash must contain 32 bytes");
        }
        return extraRecord(0x02, fields(writer -> {
            writer.writeVint(0);
            writer.write(hash);
        }));
    }

    /// Creates a Unix file time extra area.
    private static byte[] unixFileTimes(
            long modifiedSeconds,
            long modifiedNanos,
            long createdSeconds,
            long createdNanos,
            long accessedSeconds,
            long accessedNanos
    ) throws IOException {
        return extraRecord(0x03, fields(writer -> {
            writer.writeVint(0x001f);
            writer.writeUInt32(modifiedSeconds);
            writer.writeUInt32(createdSeconds);
            writer.writeUInt32(accessedSeconds);
            writer.writeUInt32(modifiedNanos);
            writer.writeUInt32(createdNanos);
            writer.writeUInt32(accessedNanos);
        }));
    }

    /// Creates a Windows FILETIME extra area.
    private static byte[] windowsFileTimes(Instant modified, Instant created, Instant accessed) throws IOException {
        return extraRecord(0x03, fields(writer -> {
            writer.writeVint(0x000e);
            writer.writeUInt64(windowsFileTime(modified));
            writer.writeUInt64(windowsFileTime(created));
            writer.writeUInt64(windowsFileTime(accessed));
        }));
    }

    /// Converts an instant to Windows FILETIME ticks.
    private static long windowsFileTime(Instant instant) {
        return Math.addExact(
                Math.multiplyExact(Math.addExact(instant.getEpochSecond(), 11_644_473_600L), 10_000_000L),
                instant.getNano() / 100L
        );
    }

    /// Creates one extra area record.
    private static byte[] extraRecord(int type, byte[] data) throws IOException {
        ByteArrayOutputStream recordData = new ByteArrayOutputStream();
        VintWriter writer = new VintWriter(recordData);
        writer.writeVint(type);
        writer.write(data);

        byte[] recordDataBytes = recordData.toByteArray();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        VintWriter outputWriter = new VintWriter(output);
        outputWriter.writeVint(recordDataBytes.length);
        outputWriter.write(recordDataBytes);
        return output.toByteArray();
    }

    /// Writes a length-prefixed UTF-8 string.
    private static void writeLengthPrefixedString(VintWriter writer, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writer.writeVint(bytes.length);
        writer.write(bytes);
    }

    /// Encodes fields through a writer callback.
    private static byte[] fields(WriterConsumer consumer) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        consumer.accept(new VintWriter(output));
        return output.toByteArray();
    }

    /// Encodes one RAR variable length integer.
    private static byte[] vint(long value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long remaining = value;
        while (true) {
            int next = (int) (remaining & 0x7fL);
            remaining >>>= 7;
            if (remaining != 0) {
                next |= 0x80;
            }
            output.write(next);
            if (remaining == 0) {
                return output.toByteArray();
            }
        }
    }

    /// Writes one little-endian unsigned 32-bit integer.
    private static void writeUInt32(ByteArrayOutputStream output, long value) {
        output.write((byte) value);
        output.write((byte) (value >>> 8));
        output.write((byte) (value >>> 16));
        output.write((byte) (value >>> 24));
    }

    /// Writes one little-endian unsigned 16-bit integer.
    private static void writeUInt16(ByteArrayOutputStream output, long value) {
        output.write((byte) value);
        output.write((byte) (value >>> 8));
    }

    /// Writes one little-endian unsigned 64-bit integer.
    private static void writeUInt64(ByteArrayOutputStream output, long value) {
        output.write((byte) value);
        output.write((byte) (value >>> 8));
        output.write((byte) (value >>> 16));
        output.write((byte) (value >>> 24));
        output.write((byte) (value >>> 32));
        output.write((byte) (value >>> 40));
        output.write((byte) (value >>> 48));
        output.write((byte) (value >>> 56));
    }

    /// Returns the unsigned CRC32 value for the given bytes.
    private static long crc32(byte[] data) {
        CRC32 crc32 = new CRC32();
        crc32.update(data, 0, data.length);
        return crc32.getValue();
    }

    /// Returns environment options containing one fixed UTF-16LE RAR3 password provider.
    private static Map<String, Object> rar3PasswordEnvironment(byte[] password) {
        return Map.of(
                RarArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(password)
        );
    }

    /// Independently derives RAR 3.x fixture keys for passwords shorter than one SHA-1 block.
    private static TestRar3Keys deriveRar3Keys(byte[] password, byte @Nullable [] salt) throws IOException {
        byte[] seed = salt != null ? concatenate(password, salt) : password.clone();
        byte[] counter = new byte[3];
        byte[] initializationVector = new byte[16];
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            for (int round = 0; round < 0x40000; round++) {
                sha1.update(seed);
                counter[0] = (byte) round;
                counter[1] = (byte) (round >>> 8);
                counter[2] = (byte) (round >>> 16);
                sha1.update(counter);
                if (round % 0x4000 == 0) {
                    byte[] snapshot = ((MessageDigest) sha1.clone()).digest();
                    initializationVector[round / 0x4000] = snapshot[snapshot.length - 1];
                }
            }
            byte[] digest = sha1.digest();
            byte[] key = new byte[16];
            for (int word = 0; word < 4; word++) {
                int offset = word * Integer.BYTES;
                key[offset] = digest[offset + 3];
                key[offset + 1] = digest[offset + 2];
                key[offset + 2] = digest[offset + 1];
                key[offset + 3] = digest[offset];
            }
            return new TestRar3Keys(key, initializationVector);
        } catch (GeneralSecurityException | CloneNotSupportedException exception) {
            throw new IOException("RAR3 test cryptographic primitives are unavailable", exception);
        }
    }

    /// Encrypts zero-padded bytes with independently derived RAR 3.x AES-128-CBC parameters.
    private static byte[] encryptRar3Aes(byte[] plaintext, byte[] key, byte[] initializationVector)
            throws IOException {
        int encryptedSize = (plaintext.length + 15) & ~15;
        byte[] padded = Arrays.copyOf(plaintext, encryptedSize);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(initializationVector));
            return cipher.doFinal(padded);
        } catch (GeneralSecurityException exception) {
            throw new IOException("RAR3 test AES encryption is unavailable", exception);
        }
    }

    /// Returns environment options containing one fixed RAR5 password provider.
    private static Map<String, Object> rar5PasswordEnvironment(byte[] password) {
        return Map.of(
                RarArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(password)
        );
    }

    /// Independently derives the three RAR5 PBKDF2 outputs used by encrypted fixtures.
    private static TestRar5Keys deriveRar5Keys(byte[] password, byte[] salt, int kdfLog) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(password, "HmacSHA256"));
            byte[] saltBlock = Arrays.copyOf(salt, salt.length + Integer.BYTES);
            saltBlock[salt.length + 3] = 1;
            byte[] value = mac.doFinal(saltBlock);
            byte[] accumulated = value.clone();
            for (int iteration = 1; iteration < 1 << kdfLog; iteration++) {
                value = accumulateRar5Pbkdf2Round(mac, value, accumulated);
            }
            byte[] aesKey = accumulated.clone();
            for (int iteration = 0; iteration < 16; iteration++) {
                value = accumulateRar5Pbkdf2Round(mac, value, accumulated);
            }
            byte[] hashKey = accumulated.clone();
            for (int iteration = 0; iteration < 16; iteration++) {
                value = accumulateRar5Pbkdf2Round(mac, value, accumulated);
            }
            byte[] passwordCheck = new byte[8];
            for (int index = 0; index < accumulated.length; index++) {
                passwordCheck[index % passwordCheck.length] ^= accumulated[index];
            }
            return new TestRar5Keys(aesKey, hashKey, passwordCheck);
        } catch (GeneralSecurityException exception) {
            throw new IOException("RAR5 test cryptographic primitives are unavailable", exception);
        }
    }

    /// Advances one independently implemented RAR5 PBKDF2 round.
    private static byte[] accumulateRar5Pbkdf2Round(Mac mac, byte[] previous, byte[] accumulated) {
        byte[] next = mac.doFinal(previous);
        for (int index = 0; index < accumulated.length; index++) {
            accumulated[index] ^= next[index];
        }
        return next;
    }

    /// Encrypts zero-padded bytes with RAR5 AES-256-CBC fixture parameters.
    private static byte[] encryptRar5Aes(byte[] plaintext, byte[] key, byte[] initializationVector)
            throws IOException {
        int encryptedSize = (plaintext.length + 15) & ~15;
        byte[] padded = Arrays.copyOf(plaintext, encryptedSize);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(initializationVector));
            return cipher.doFinal(padded);
        } catch (GeneralSecurityException exception) {
            throw new IOException("RAR5 test AES encryption is unavailable", exception);
        }
    }

    /// Returns the SHA-256 prefix authenticating an eight-byte RAR5 password check.
    private static byte[] passwordCheckChecksum(byte[] passwordCheck) throws IOException {
        try {
            return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(passwordCheck), 4);
        } catch (GeneralSecurityException exception) {
            throw new IOException("RAR5 test SHA-256 is unavailable", exception);
        }
    }

    /// Converts a plain CRC32 value into the RAR5 password-dependent checksum representation.
    private static long keyedRar5Crc32(long crc32, byte[] hashKey) throws IOException {
        byte[] rawCrc = new byte[]{
                (byte) crc32,
                (byte) (crc32 >>> 8),
                (byte) (crc32 >>> 16),
                (byte) (crc32 >>> 24)
        };
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hashKey, "HmacSHA256"));
            byte[] digest = mac.doFinal(rawCrc);
            long result = 0L;
            for (int index = 0; index < digest.length; index++) {
                result ^= (long) Byte.toUnsignedInt(digest[index]) << ((index & 3) * Byte.SIZE);
            }
            return result & 0xffff_ffffL;
        } catch (GeneralSecurityException exception) {
            throw new IOException("RAR5 test HMAC-SHA256 is unavailable", exception);
        }
    }

    /// Converts a raw BLAKE2sp digest into the RAR5 password-dependent hash representation.
    private static byte[] keyedRar5Blake2sp(byte[] digest, byte[] hashKey) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hashKey, "HmacSHA256"));
            return mac.doFinal(digest);
        } catch (GeneralSecurityException exception) {
            throw new IOException("RAR5 test HMAC-SHA256 is unavailable", exception);
        }
    }

    /// Converts an epoch second value to a RAR4 DOS timestamp.
    private static long rar4DosTime(long epochSeconds) {
        var time = Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC);
        int year = time.getYear();
        if (year < 1980 || year > 2107) {
            throw new IllegalArgumentException("RAR4 DOS timestamp year is out of range");
        }
        return (long) (year - 1980) << 25
                | (long) time.getMonthValue() << 21
                | (long) time.getDayOfMonth() << 16
                | (long) time.getHour() << 11
                | (long) time.getMinute() << 5
                | time.getSecond() / 2L;
    }

    /// Creates a temporary archive path under the module build directory.
    private static Path createTemporaryArchivePath(String prefix) throws IOException {
        Path temporaryRoot = Path.of("build", "tmp", "arkivo-rar-tests");
        Files.createDirectories(temporaryRoot);
        Path temporaryDirectory = Files.createTempDirectory(temporaryRoot, prefix);
        return temporaryDirectory.resolve("sample.rar");
    }

    /// Deletes a temporary archive file and its containing directory.
    private static void deleteTemporaryArchive(Path archivePath) throws IOException {
        Files.deleteIfExists(archivePath);
        Files.deleteIfExists(archivePath.getParent());
    }

    /// Consumes a RAR test writer.
    @FunctionalInterface
    private interface WriterConsumer {
        /// Writes fields to the given writer.
        void accept(VintWriter writer) throws IOException;
    }

    /// Repeatable single-archive source that records opened channel and source lifecycles.
    @NotNullByDefault
    private static final class TestSeekableChannelSource implements ArkivoSeekableChannelSource {
        /// The archive bytes exposed by each opened channel.
        private final byte @Unmodifiable [] content;

        /// The channels opened from this source.
        private final ArrayList<TestByteArraySeekableChannel> openedChannels = new ArrayList<>();

        /// The number of times this source has been closed.
        private int closeCount;

        /// Creates a repeatable source over the given archive bytes.
        private TestSeekableChannelSource(byte[] content) {
            this.content = Objects.requireNonNull(content, "content").clone();
        }

        /// Opens an independent channel over the archive bytes.
        @Override
        public SeekableByteChannel openChannel() throws IOException {
            if (closeCount > 0) {
                throw new IOException("source is closed");
            }
            TestByteArraySeekableChannel channel = new TestByteArraySeekableChannel(content);
            openedChannels.add(channel);
            return channel;
        }

        /// Records that this source has been closed.
        @Override
        public void close() {
            closeCount++;
        }

        /// Returns the number of channels opened from this source.
        private int openCount() {
            return openedChannels.size();
        }

        /// Returns whether every channel opened from this source has been closed.
        private boolean allOpenedChannelsClosed() {
            for (TestByteArraySeekableChannel channel : openedChannels) {
                if (channel.isOpen()) {
                    return false;
                }
            }
            return true;
        }

        /// Returns the number of times this source has been closed.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Read-only seekable channel over an immutable byte array.
    @NotNullByDefault
    private static final class TestByteArraySeekableChannel implements SeekableByteChannel {
        /// The immutable channel content.
        private final byte @Unmodifiable [] content;

        /// The current channel position.
        private int position;

        /// Whether this channel is open.
        private boolean open = true;

        /// Creates a read-only channel over the given content.
        private TestByteArraySeekableChannel(byte[] content) {
            this.content = Objects.requireNonNull(content, "content").clone();
        }

        /// Reads bytes from the current channel position.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            Objects.requireNonNull(destination, "destination");
            ensureOpen();
            if (!destination.hasRemaining()) {
                return 0;
            }
            if (position >= content.length) {
                return -1;
            }
            int count = Math.min(destination.remaining(), content.length - position);
            destination.put(content, position, count);
            position += count;
            return count;
        }

        /// Always rejects writes.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            Objects.requireNonNull(source, "source");
            throw new NonWritableChannelException();
        }

        /// Returns the current channel position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Sets the current channel position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0 || newPosition > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("newPosition is out of range");
            }
            position = (int) newPosition;
            return this;
        }

        /// Returns the content size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return content.length;
        }

        /// Always rejects truncation.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            ensureOpen();
            if (size < 0) {
                throw new IllegalArgumentException("size must not be negative");
            }
            throw new NonWritableChannelException();
        }

        /// Returns whether this channel is open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }

        /// Requires this channel to be open.
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Provides independent in-memory RAR volumes and records owned-resource cleanup.
    @NotNullByDefault
    private static final class TrackingRarVolumeSource implements ArkivoVolumeSource {
        /// Immutable volume bytes.
        private final byte @Unmodifiable [] @Unmodifiable [] volumes;

        /// Channels opened from this source.
        private final List<TestByteArraySeekableChannel> openedChannels = new ArrayList<>();

        /// Number of source close calls.
        private int closeCount;

        /// Whether the first source close call fails.
        private final boolean failFirstClose;

        /// Creates a source over copied volume content.
        private TrackingRarVolumeSource(byte[][] volumes) {
            this(volumes, false);
        }

        /// Creates a source with configurable first-close behavior.
        private TrackingRarVolumeSource(byte[][] volumes, boolean failFirstClose) {
            this.volumes = new byte[volumes.length][];
            for (int index = 0; index < volumes.length; index++) {
                this.volumes[index] = volumes[index].clone();
            }
            this.failFirstClose = failFirstClose;
        }

        /// Opens one independent physical volume.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) throws IOException {
            if (closeCount != 0) {
                throw new ClosedChannelException();
            }
            if (index < 0L || index >= volumes.length) {
                return null;
            }
            TestByteArraySeekableChannel channel = new TestByteArraySeekableChannel(volumes[(int) index]);
            openedChannels.add(channel);
            return channel;
        }

        /// Records source closure.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (failFirstClose && closeCount == 1) {
                throw new IOException("source close failed");
            }
        }

        /// Returns the number of source close calls.
        private int closeCount() {
            return closeCount;
        }

        /// Returns whether every opened physical channel is closed.
        private boolean allOpenedChannelsClosed() {
            return openedChannels.stream().noneMatch(TestByteArraySeekableChannel::isOpen);
        }
    }

    /// Writes RAR test header primitives.
    @NotNullByDefault
    private static final class VintWriter {
        /// The destination stream.
        private final ByteArrayOutputStream output;

        /// Creates a primitive writer.
        private VintWriter(ByteArrayOutputStream output) {
            this.output = Objects.requireNonNull(output, "output");
        }

        /// Writes one variable length integer.
        private void writeVint(long value) {
            byte[] bytes = vint(value);
            output.write(bytes, 0, bytes.length);
        }

        /// Writes one little-endian unsigned 32-bit integer.
        private void writeUInt32(long value) {
            RarArkivoStreamingReaderTest.writeUInt32(output, value);
        }

        /// Writes one little-endian unsigned 64-bit integer.
        private void writeUInt64(long value) {
            RarArkivoStreamingReaderTest.writeUInt64(output, value);
        }

        /// Writes raw bytes.
        private void write(byte[] bytes) throws IOException {
            output.write(bytes);
        }
    }

    /// Stores one RAR4 member together with its file-encryption salt.
    ///
    /// @param member the encrypted member metadata and ciphertext
    /// @param salt the eight-byte RAR 3.x derivation salt, or `null` for unsalted encryption
    @NotNullByDefault
    private record Rar4EncryptedFixture(
            Member member,
            byte @Nullable @Unmodifiable [] salt
    ) {
        /// Creates one immutable encrypted RAR4 fixture member.
        private Rar4EncryptedFixture {
            Objects.requireNonNull(member, "member");
            if (salt != null && salt.length != 8) {
                throw new IllegalArgumentException("RAR4 fixture salt must contain eight bytes");
            }
            salt = salt != null ? salt.clone() : null;
        }
    }

    /// Stores independently derived RAR3 fixture key material.
    ///
    /// @param key the AES-128 encryption key
    /// @param initializationVector the AES-CBC initialization vector
    @NotNullByDefault
    private record TestRar3Keys(
            byte @Unmodifiable [] key,
            byte @Unmodifiable [] initializationVector
    ) {
        /// Creates immutable fixture key material.
        private TestRar3Keys {
            key = key.clone();
            initializationVector = initializationVector.clone();
        }
    }

    /// One RAR test member.
    ///
    /// @param path                        the stored RAR entry path or service header name
    /// @param modificationTime            the Unix modification time in seconds
    /// @param attributes                  the raw operating-system-specific file attributes
    /// @param directory                   whether this member is a directory
    /// @param symbolicLink                whether this member is a symbolic link
    /// @param compressionMethod           the RAR compression method number
    /// @param crc32                       the unsigned CRC32 value stored in the file header
    /// @param unpackedSize                the unpacked size stored in the file header
    /// @param body                        the packed data bytes
    /// @param extraArea                   the encoded extra area, or `null` when absent
    /// @param service                     whether this member is a service header
    /// @param continuesFromPreviousVolume whether this member continues data from the previous volume
    /// @param continuesInNextVolume       whether this member continues data in the next volume
    @NotNullByDefault
    private record Member(
            String path,
            long modificationTime,
            long attributes,
            boolean directory,
            boolean symbolicLink,
            int compressionMethod,
            long crc32,
            long unpackedSize,
            byte @Unmodifiable [] body,
            byte @Nullable @Unmodifiable [] extraArea,
            boolean service,
            boolean continuesFromPreviousVolume,
            boolean continuesInNextVolume
    ) {
        /// Creates one RAR test member.
        private Member {
            Objects.requireNonNull(path, "path");
            if (unpackedSize < 0) {
                throw new IllegalArgumentException("unpackedSize must not be negative");
            }
            body = body.clone();
            extraArea = extraArea != null ? extraArea.clone() : new byte[0];
        }
    }

    /// Stores independently derived RAR5 fixture key material.
    ///
    /// @param aesKey the AES-256 encryption key
    /// @param hashKey the password-dependent checksum key
    /// @param passwordCheck the folded password verification value
    @NotNullByDefault
    private record TestRar5Keys(
            byte @Unmodifiable [] aesKey,
            byte @Unmodifiable [] hashKey,
            byte @Unmodifiable [] passwordCheck
    ) {
        /// Creates immutable fixture key material.
        private TestRar5Keys {
            aesKey = aesKey.clone();
            hashKey = hashKey.clone();
            passwordCheck = passwordCheck.clone();
        }
    }

    /// Tracks cached-content allocation and close calls while delegating content to memory storage.
    @NotNullByDefault
    private static final class TrackingEditStorage implements ArkivoEditStorage {
        /// The delegate memory storage.
        private final ArkivoEditStorage delegate = ArkivoEditStorage.memory();

        /// Whether the first stored-content close call must fail.
        private final boolean failFirstContentClose;

        /// The number of created content objects.
        private int createdContentCount;

        /// The total number of stored-content close calls.
        private int contentCloseCount;

        /// The number of storage close calls.
        private int closeCount;

        /// Creates tracking storage with the requested cleanup behavior.
        private TrackingEditStorage(boolean failFirstContentClose) {
            this.failFirstContentClose = failFirstContentClose;
        }

        /// Creates one tracked stored-content object.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) throws IOException {
            createdContentCount++;
            return new TrackingStoredContent(delegate.createContent(path, expectedSize));
        }

        /// Closes the delegate storage and records the call.
        @Override
        public void close() throws IOException {
            closeCount++;
            delegate.close();
        }

        /// Returns the number of created content objects.
        private int createdContentCount() {
            return createdContentCount;
        }

        /// Returns the total number of stored-content close calls.
        private int contentCloseCount() {
            return contentCloseCount;
        }

        /// Returns the number of storage close calls.
        private int closeCount() {
            return closeCount;
        }

        /// Tracks one delegated stored-content object.
        @NotNullByDefault
        private final class TrackingStoredContent implements ArkivoStoredContent {
            /// The delegated stored content.
            private final ArkivoStoredContent content;

            /// Whether this content has failed its first close call.
            private boolean firstCloseFailed;

            /// Creates tracked stored content.
            private TrackingStoredContent(ArkivoStoredContent content) {
                this.content = content;
            }

            /// Opens a channel over the delegated content.
            @Override
            public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
                return content.openChannel(options);
            }

            /// Returns the delegated content size.
            @Override
            public long size() throws IOException {
                return content.size();
            }

            /// Closes the delegated content or injects the configured first failure.
            @Override
            public void close() throws IOException {
                contentCloseCount++;
                if (failFirstContentClose && !firstCloseFailed) {
                    firstCloseFailed = true;
                    throw new IOException("content close failed");
                }
                content.close();
            }
        }
    }

    /// Input stream that fails its first close call.
    @NotNullByDefault
    private static final class CloseFailingOnceInputStream extends ByteArrayInputStream {
        /// The number of close calls.
        private int closeCount;

        /// Creates a close-failing input stream over the given bytes.
        private CloseFailingOnceInputStream(byte[] bytes) {
            super(bytes);
        }

        /// Fails on the first close call and records all close attempts.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            super.close();
        }

        /// Returns the number of close calls.
        private int closeCount() {
            return closeCount;
        }
    }
}
