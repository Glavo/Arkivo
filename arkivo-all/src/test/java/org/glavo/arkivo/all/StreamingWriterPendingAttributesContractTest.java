// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.archive.ar.ArArkivoStreamingWriter;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoEntryAttributeView;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoStreamingWriter;
import org.glavo.arkivo.archive.tar.TarArkivoEntryAttributeView;
import org.glavo.arkivo.archive.tar.TarArkivoEntryAttributes;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingWriter;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributeView;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingWriter;
import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.glavo.arkivo.archive.zip.ZipMethod;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies pending streaming-writer attributes across archive formats with different snapshot contracts.
@NotNullByDefault
final class StreamingWriterPendingAttributesContractTest {
    /// Cached basic, POSIX, and format views stop accepting changes when metadata-only completion fails.
    @ParameterizedTest
    @ValueSource(strings = {"zip", "7z"})
    void freezesCachedViewsAfterFailedEntryClose(String format) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter(format, target);
        ArkivoStreamingWriter.Entry entry;
        if (format.equals("zip")) {
            entry = writer.beginFile("invalid");
            ZipArkivoEntryAttributeView zip = Objects.requireNonNull(entry.attributeView(ZipArkivoEntryAttributeView.class));
            zip.setMethod(ZipMethod.STORED);
            zip.setUncompressedSizeAndCrc32(1, 0);
        } else {
            writer.beginDirectory("existing").close();
            entry = writer.beginDirectory("existing");
        }
        BasicFileAttributeView basic = Objects.requireNonNull(entry.attributeView(BasicFileAttributeView.class));
        PosixFileAttributeView posix = Objects.requireNonNull(entry.attributeView(PosixFileAttributeView.class));
        FileTime before = basic.readAttributes().lastModifiedTime();
        assertThrows(IOException.class, entry::close);
        assertThrows(IOException.class, () -> entry.attributeView(BasicFileAttributeView.class));
        assertThrows(IOException.class, entry::openChannel);
        assertThrows(IllegalStateException.class, () -> basic.setTimes(FileTime.fromMillis(123456000), null, null));
        assertThrows(IllegalStateException.class, () -> posix.setPermissions(Set.of(PosixFilePermission.OWNER_READ)));
        if (basic instanceof ZipArkivoEntryAttributeView zip) {
            assertThrows(IllegalStateException.class, () -> zip.setMethod(ZipMethod.DEFLATED));
            assertThrows(IllegalStateException.class, () -> zip.setEncryption(ZipEncryption.NONE));
            assertThrows(IllegalStateException.class, () -> zip.setUncompressedSizeAndCrc32(0, 0));
            assertThrows(IllegalStateException.class, () -> zip.setInternalAttributes(1));
            assertThrows(IllegalStateException.class, () -> zip.setExternalAttributes(1));
            assertThrows(IllegalStateException.class, () -> zip.setLocalExtraData(new byte[0]));
            assertThrows(IllegalStateException.class, () -> zip.setCentralDirectoryExtraData(new byte[0]));
            assertThrows(IllegalStateException.class, () -> zip.setRawComment(new byte[]{1}));
        } else if (basic instanceof SevenZipArkivoEntryAttributeView sevenZip) {
            assertThrows(IllegalStateException.class, () -> sevenZip.setWindowsAttributes(0));
            assertThrows(IllegalStateException.class, sevenZip::clearFilter);
        } else {
            throw new AssertionError("Unexpected pending metadata view: " + basic.getClass());
        }
        assertEquals(before, basic.readAttributes().lastModifiedTime());
        assertThrows(IOException.class, entry::close);
        assertThrows(IOException.class, writer::close);
        writer.close();
        assertThrows(IllegalStateException.class, () -> basic.setTimes(FileTime.fromMillis(123456000), null, null));
    }

    /// Malformed link targets are rejected before any pending entry is published.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z", "cpio"})
    void rejectsMalformedLinkTargets(String format, @TempDir Path directory) throws IOException {
        Path path = directory.resolve("link-targets." + format);
        String target = "target\uD83D\uDE00";
        try (var writer = ArkivoFormats.openStreamingWriter(format, Files.newOutputStream(path))) {
            for (String invalid : List.of("x\uD800", "x\uDC00", "x\uD800y", "x\uDC00\uD800", "x\0y")) {
                assertThrows(IllegalArgumentException.class, () -> writer.beginSymbolicLink("link", invalid));
                if (writer instanceof TarArkivoStreamingWriter tar) {
                    assertThrows(IllegalArgumentException.class, () -> tar.beginHardLink("hard-link", invalid));
                }
            }
            writer.beginFile(target).close();
            writer.beginSymbolicLink("link", target).close();
            if (writer instanceof TarArkivoStreamingWriter tar) {
                tar.beginHardLink("hard-link", target).close();
            }
            writer.beginFile("following").close();
        }
        if (format.equals("cpio")) {
            try (var reader = ArkivoFormats.openStreamingReader(format, Files.newInputStream(path))) {
                assertTrue(reader.next());
                assertEquals(target, reader.readAttributes().path());
                assertTrue(reader.next());
                assertEquals("link", reader.readAttributes().path());
                try (var input = reader.openInputStream()) {
                    assertEquals(target, new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
                assertTrue(reader.next());
                assertEquals("following", reader.readAttributes().path());
                assertFalse(reader.next());
            }
        } else {
            try (var fs = ArkivoFormats.openFileSystem(format, path)) {
                assertEquals(fs.getPath(target), Files.readSymbolicLink(fs.getPath("/link")));
                assertEquals("", Files.readString(fs.getPath("/link")));
                if (format.equals("tar")) {
                    assertTrue(Files.isSameFile(fs.getPath("/hard-link"), fs.getPath("/" + target)));
                }
                assertEquals("", Files.readString(fs.getPath("/following")));
            }
        }
    }

    /// Malformed UTF-16 names are rejected before entry initialization without rejecting valid surrogate pairs.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z", "cpio"})
    void rejectsUnpairedSurrogates(String format, @TempDir Path directory) throws IOException {
        Path path = directory.resolve("names." + format);
        try (var writer = ArkivoFormats.openStreamingWriter(format, Files.newOutputStream(path))) {
            for (String name : List.of("x\uD800", "x\uDC00", "x\uD800y", "x\uDC00\uD800")) {
                assertThrows(IllegalArgumentException.class, () -> writer.beginFile(name));
                assertThrows(IllegalArgumentException.class, () -> writer.beginDirectory(name));
                assertThrows(IllegalArgumentException.class, () -> writer.beginSymbolicLink(name, "target"));
                if (writer instanceof TarArkivoStreamingWriter tar) {
                    assertThrows(IllegalArgumentException.class, () -> tar.beginHardLink(name, "target"));
                }
            }
            writer.beginFile("x\uD83D\uDE00").close();
            writer.beginFile("following").close();
        }
        if (format.equals("7z")) {
            try (var archive = ArkivoFormats.openFileSystem(format, path)) {
                assertEquals("", Files.readString(archive.getPath("/x\uD83D\uDE00")));
                assertEquals("", Files.readString(archive.getPath("/following")));
            }
            return;
        }
        try (var reader = ArkivoFormats.openStreamingReader(format, Files.newInputStream(path))) {
            assertTrue(reader.next());
            assertEquals("x\uD83D\uDE00", reader.readAttributes().path());
            assertTrue(reader.next());
            assertEquals("following", reader.readAttributes().path());
            assertFalse(reader.next());
        }
    }

    /// A staging setup failure leaves the same entry configurable and available for retry or empty completion.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar"})
    void retainsPendingEntryAfterBodySetupFailure(String format, @TempDir Path directory) throws IOException {
        for (boolean failChannelOpen : new boolean[]{false, true}) {
            for (boolean retryBody : new boolean[]{false, true}) {
                Path path = directory.resolve(failChannelOpen + "-" + retryBody + "." + format);
                FailingBodyStorage storage = new FailingBodyStorage(failChannelOpen);
                FileTime modified = FileTime.fromMillis(1_600_000_000_000L);
                try (ArkivoStreamingWriter writer = format.equals("ar")
                        ? ArArkivoStreamingWriter.create(path, storage)
                        : TarArkivoStreamingWriter.create(path, storage)) {
                    try (ArkivoStreamingWriter.Entry entry = writer.beginFile("pending")) {
                        assertSame(storage.failure, assertThrows(IOException.class, entry::openOutputStream));
                        BasicFileAttributeView view = Objects.requireNonNull(
                                entry.attributeView(BasicFileAttributeView.class));
                        view.setTimes(modified, null, null);
                        assertEquals(modified, view.readAttributes().lastModifiedTime());
                        if (retryBody) {
                            try (OutputStream body = entry.openOutputStream()) {
                                body.write("payload".getBytes(StandardCharsets.UTF_8));
                            }
                        }
                    }
                    writer.beginFile("following").close();
                }
                assertTrue(storage.closed);
                assertEquals(0, storage.openContents);
                try (var archive = ArkivoFormats.openFileSystem(format, path)) {
                    assertEquals(retryBody ? "payload" : "", Files.readString(archive.getPath("pending")));
                    assertEquals(modified, Files.getLastModifiedTime(archive.getPath("pending")));
                    assertEquals("", Files.readString(archive.getPath("following")));
                }
            }
        }
    }

    /// Fails the first allocation or channel open without writing any archive bytes.
    @NotNullByDefault
    private static final class FailingBodyStorage implements ArkivoEditStorage {
        /// The storage retaining successfully staged bytes.
        private final ArkivoEditStorage delegate = ArkivoEditStorage.memory();
        /// Whether failure occurs after allocation rather than before it.
        private final boolean failChannelOpen;
        /// The failure whose identity must survive setup cleanup.
        private final IOException failure = new IOException("Body staging setup failed");
        /// Whether the scheduled failure remains available.
        private boolean failNext = true;
        /// Allocated contents not yet released.
        private int openContents;
        /// Whether storage closure completed.
        private boolean closed;

        /// Selects the staging operation that fails once.
        private FailingBodyStorage(boolean failChannelOpen) {
            this.failChannelOpen = failChannelOpen;
        }

        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) throws IOException {
            boolean fail = failNext;
            failNext = false;
            if (fail && !failChannelOpen) {
                throw failure;
            }
            ArkivoStoredContent content = delegate.createContent(path, expectedSize);
            openContents++;
            return new Content(content, fail);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
            closed = true;
        }

        /// Tracks content release after a successful or failed channel open.
        @NotNullByDefault
        private final class Content implements ArkivoStoredContent {
            /// The retained bytes owned by this content.
            private final ArkivoStoredContent content;
            /// Whether the first channel open must fail.
            private boolean failOpen;
            /// Whether content release completed.
            private boolean released;

            /// Wraps allocated content with optional channel-open failure.
            private Content(ArkivoStoredContent content, boolean failOpen) {
                this.content = content;
                this.failOpen = failOpen;
            }

            @Override
            public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
                if (failOpen) {
                    failOpen = false;
                    throw failure;
                }
                return content.openChannel(options);
            }

            @Override
            public long size() throws IOException {
                return content.size();
            }

            @Override
            public void close() throws IOException {
                if (!released) {
                    content.close();
                    released = true;
                    openContents--;
                }
            }
        }
    }

    /// Verifies TAR's documented live projection and the defaults for every writable TAR entry type.
    @Test
    void exposesLiveTarAttributesUntilEntryCommit() throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(archive)) {
            ArkivoStreamingWriter.Entry file = writer.beginFile("file.bin");
            TarArkivoEntryAttributeView tarView = Objects.requireNonNull(
                    file.attributeView(TarArkivoEntryAttributeView.class)
            );
            BasicFileAttributeView basicView = Objects.requireNonNull(
                    file.attributeView(BasicFileAttributeView.class)
            );
            PosixFileAttributeView posixView = Objects.requireNonNull(
                    file.attributeView(PosixFileAttributeView.class)
            );
            TarArkivoEntryAttributes liveAttributes = tarView.readAttributes();
            PosixFileAttributes livePosixAttributes = posixView.readAttributes();

            assertEquals("tar", tarView.name());
            assertEquals("posix", posixView.name());
            assertTarType(liveAttributes, "file.bin", (byte) '0', 0644, true, false, false, null);
            assertEquals(FileTime.fromMillis(0L), liveAttributes.lastModifiedTime());
            assertEquals(FileTime.fromMillis(0L), liveAttributes.lastAccessTime());
            assertEquals(FileTime.fromMillis(0L), liveAttributes.creationTime());
            assertNull(liveAttributes.recordedLastAccessTime());
            assertNull(liveAttributes.recordedStatusChangeTime());
            assertNull(liveAttributes.recordedCreationTime());
            assertEquals(0L, liveAttributes.userId());
            assertEquals(0L, liveAttributes.groupId());
            assertNull(liveAttributes.userName());
            assertNull(liveAttributes.groupName());
            assertEquals("", livePosixAttributes.owner().getName());
            assertEquals("", livePosixAttributes.group().getName());

            FileTime modified = FileTime.from(Instant.parse("2025-01-02T03:04:05.123456789Z"));
            FileTime accessed = FileTime.from(Instant.parse("2025-02-03T04:05:06.234567890Z"));
            FileTime changed = FileTime.from(Instant.parse("2025-03-04T05:06:07.345678901Z"));
            FileTime created = FileTime.from(Instant.parse("2025-04-05T06:07:08.456789012Z"));
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-r-----");
            basicView.setTimes(modified, null, null);
            tarView.setRecordedLastAccessTime(accessed);
            tarView.setRecordedStatusChangeTime(changed);
            tarView.setRecordedCreationTime(created);
            tarView.setUserId(1234L);
            tarView.setGroupId(5678L);
            posixView.setOwner(() -> "pending-user");
            posixView.setGroup(() -> "pending-group");
            posixView.setPermissions(permissions);

            assertEquals(modified, liveAttributes.lastModifiedTime());
            assertEquals(accessed, liveAttributes.lastAccessTime());
            assertEquals(created, liveAttributes.creationTime());
            assertEquals(accessed, liveAttributes.recordedLastAccessTime());
            assertEquals(changed, liveAttributes.recordedStatusChangeTime());
            assertEquals(created, liveAttributes.recordedCreationTime());
            assertEquals(1234L, liveAttributes.userId());
            assertEquals(5678L, liveAttributes.groupId());
            assertEquals("pending-user", liveAttributes.userName());
            assertEquals("pending-group", liveAttributes.groupName());
            assertEquals("pending-user", livePosixAttributes.owner().getName());
            assertEquals("pending-group", livePosixAttributes.group().getName());
            assertEquals(permissions, livePosixAttributes.permissions());
            assertEquals(0640, liveAttributes.mode());

            file.close();
            assertThrows(IllegalStateException.class, () -> tarView.setMode(0600));

            assertTarEntry(
                    writer.beginDirectory("directory"),
                    "directory/",
                    (byte) '5',
                    0755,
                    false,
                    true,
                    false,
                    null
            );
            assertTarEntry(
                    writer.beginSymbolicLink("symbolic", "file.bin"),
                    "symbolic",
                    (byte) '2',
                    0777,
                    false,
                    false,
                    true,
                    "file.bin"
            );
            assertTarEntry(
                    writer.beginHardLink("hard", "file.bin"),
                    "hard",
                    TarArkivoEntryAttributes.HARD_LINK_TYPE,
                    0644,
                    true,
                    false,
                    false,
                    "file.bin"
            );
        }

        assertTrue(archive.size() > 0);
    }

    /// Verifies ZIP-specific and POSIX reads are stable snapshots with defensive raw metadata copies.
    @Test
    void exposesStableZipAttributeSnapshots() throws IOException {
        byte[] content = "pending ZIP body".getBytes(StandardCharsets.UTF_8);
        CRC32 checksum = new CRC32();
        checksum.update(content);
        long crc32 = checksum.getValue();
        ByteArrayOutputStream archive = new ByteArrayOutputStream();

        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archive)) {
            ArkivoStreamingWriter.Entry file = writer.beginFile("file.bin");
            ZipArkivoEntryAttributeView zipView = Objects.requireNonNull(
                    file.attributeView(ZipArkivoEntryAttributeView.class)
            );
            PosixFileAttributeView posixView = Objects.requireNonNull(
                    file.attributeView(PosixFileAttributeView.class)
            );
            ZipArkivoEntryAttributes defaults = zipView.readAttributes();
            PosixFileAttributes posixDefaults = posixView.readAttributes();

            assertEquals("zip", zipView.name());
            assertZipType(defaults, "file.bin", true, false, false);
            assertArrayEquals("file.bin".getBytes(StandardCharsets.UTF_8), defaults.rawPath());
            assertNull(defaults.rawComment());
            assertArrayEquals(new byte[0], defaults.localExtraData());
            assertArrayEquals(new byte[0], defaults.centralDirectoryExtraData());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, defaults.compressedSize());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, defaults.size());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_CRC32, defaults.crc32());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_UNIX_ID, defaults.userId());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_UNIX_ID, defaults.groupId());
            assertEquals(ZipMethod.DEFLATED, defaults.compressionMethod());
            assertEquals(ZipMethod.DEFLATED.id(), defaults.compressionMethodId());
            assertEquals(ZipEncryption.NONE, defaults.encryption());
            assertEquals("owner", posixDefaults.owner().getName());
            assertEquals("group", posixDefaults.group().getName());
            assertEquals(PosixFilePermissions.fromString("rw-r--r--"), posixDefaults.permissions());

            FileTime firstTime = FileTime.from(Instant.parse("2025-05-06T07:08:09Z"));
            FileTime secondTime = FileTime.from(Instant.parse("2025-06-07T08:09:10Z"));
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rwxr-----");
            byte[] localExtra = {0x34, 0x12, 0x01, 0x00, 0x55};
            byte[] centralExtra = {0x35, 0x12, 0x01, 0x00, 0x66};
            byte[] rawComment = "first comment".getBytes(StandardCharsets.UTF_8);
            zipView.setTimes(firstTime, null, null);
            zipView.setMethod(ZipMethod.STORED);
            zipView.setUncompressedSizeAndCrc32(content.length, crc32);
            zipView.setInternalAttributes(0x1234);
            zipView.setExternalAttributes(0x1234_5678L);
            zipView.setLocalExtraData(localExtra);
            zipView.setCentralDirectoryExtraData(centralExtra);
            zipView.setRawComment(rawComment);
            posixView.setPermissions(permissions);
            localExtra[4] = 0;
            centralExtra[4] = 0;
            rawComment[0] = 0;

            ZipArkivoEntryAttributes configured = zipView.readAttributes();
            PosixFileAttributes configuredPosix = posixView.readAttributes();
            assertEquals(firstTime, configured.lastModifiedTime());
            assertEquals(firstTime, configured.lastAccessTime());
            assertEquals(firstTime, configured.creationTime());
            assertEquals(ZipMethod.STORED, configured.compressionMethod());
            assertEquals(content.length, configured.size());
            assertEquals(crc32, configured.crc32());
            assertEquals(0x1234, configured.internalAttributes());
            assertEquals(0x1234_5678L, configured.externalAttributes());
            assertArrayEquals(new byte[]{0x34, 0x12, 0x01, 0x00, 0x55}, configured.localExtraData());
            assertArrayEquals(new byte[]{0x35, 0x12, 0x01, 0x00, 0x66}, configured.centralDirectoryExtraData());
            assertArrayEquals("first comment".getBytes(StandardCharsets.UTF_8), configured.rawComment());
            assertEquals(permissions, configured.permissions());

            byte[] returnedPath = configured.rawPath();
            byte[] returnedLocalExtra = configured.localExtraData();
            byte[] returnedCentralExtra = configured.centralDirectoryExtraData();
            byte[] returnedComment = Objects.requireNonNull(configured.rawComment());
            returnedPath[0] = 0;
            returnedLocalExtra[4] = 0;
            returnedCentralExtra[4] = 0;
            returnedComment[0] = 0;
            assertArrayEquals("file.bin".getBytes(StandardCharsets.UTF_8), configured.rawPath());
            assertArrayEquals(new byte[]{0x34, 0x12, 0x01, 0x00, 0x55}, configured.localExtraData());
            assertArrayEquals(new byte[]{0x35, 0x12, 0x01, 0x00, 0x66}, configured.centralDirectoryExtraData());
            assertArrayEquals("first comment".getBytes(StandardCharsets.UTF_8), configured.rawComment());

            zipView.setTimes(secondTime, secondTime, secondTime);
            zipView.setInternalAttributes(0x4321);
            zipView.setExternalAttributes(0x8765_4321L);
            zipView.setRawComment("second comment".getBytes(StandardCharsets.UTF_8));
            posixView.setPermissions(PosixFilePermissions.fromString("rw-------"));

            assertEquals(FileTime.fromMillis(0L), defaults.lastModifiedTime());
            assertEquals(ZipMethod.DEFLATED, defaults.compressionMethod());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, defaults.size());
            assertEquals(FileTime.fromMillis(0L), posixDefaults.lastModifiedTime());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, posixDefaults.size());
            assertEquals(PosixFilePermissions.fromString("rw-r--r--"), posixDefaults.permissions());
            assertEquals(firstTime, configured.lastModifiedTime());
            assertEquals(0x1234, configured.internalAttributes());
            assertEquals(0x1234_5678L, configured.externalAttributes());
            assertArrayEquals("first comment".getBytes(StandardCharsets.UTF_8), configured.rawComment());
            assertEquals(firstTime, configuredPosix.lastModifiedTime());
            assertEquals(content.length, configuredPosix.size());
            assertEquals(permissions, configuredPosix.permissions());

            try (OutputStream output = file.openOutputStream()) {
                output.write(content);
            }

            assertZipEntry(writer.beginDirectory("directory"), "directory", false, true, false);
            assertZipEntry(writer.beginSymbolicLink("symbolic", "file.bin"), "symbolic", false, false, true);
        }

        assertTrue(archive.size() > 0);
    }

    /// Verifies pending ZIP metadata rejects values that cannot be represented in unsigned 16-bit fields.
    @Test
    void validatesPendingZipMetadataWidthsImmediately() throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archive)) {
            ArkivoStreamingWriter.Entry file = writer.beginFile("file.bin");
            ZipArkivoEntryAttributeView view = Objects.requireNonNull(
                    file.attributeView(ZipArkivoEntryAttributeView.class)
            );

            assertThrows(IllegalArgumentException.class, () -> view.setInternalAttributes(-1));
            assertThrows(IllegalArgumentException.class, () -> view.setInternalAttributes(0x1_0000));
            assertThrows(IllegalArgumentException.class, () -> view.setRawComment(new byte[0x1_0000]));
            assertThrows(IllegalArgumentException.class, () -> view.setLocalExtraData(new byte[0x1_0000]));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> view.setCentralDirectoryExtraData(new byte[0x1_0000])
            );

            file.close();
        }
    }

    /// Verifies 7z pending reads remain stable and expose pre-encoding sentinels for every entry type.
    @Test
    void exposesStableSevenZipAttributeSnapshots() throws IOException {
        byte[] content = "pending 7z body".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.open(archive)) {
            ArkivoStreamingWriter.Entry file = writer.beginFile("file.bin");
            SevenZipArkivoEntryAttributeView sevenZipView = Objects.requireNonNull(
                    file.attributeView(SevenZipArkivoEntryAttributeView.class)
            );
            PosixFileAttributeView posixView = Objects.requireNonNull(
                    file.attributeView(PosixFileAttributeView.class)
            );
            SevenZipArkivoEntryAttributes defaults = sevenZipView.readAttributes();
            PosixFileAttributes posixDefaults = posixView.readAttributes();

            assertEquals("7z", sevenZipView.name());
            assertSevenZipType(defaults, "file.bin", true, false, false);
            assertEquals(SevenZipArkivoEntryAttributes.UNKNOWN_WINDOWS_ATTRIBUTES, defaults.windowsAttributes());
            assertEquals(SevenZipArkivoEntryAttributes.UNKNOWN_UNIX_MODE, defaults.unixMode());
            assertEquals("owner", posixDefaults.owner().getName());
            assertEquals("group", posixDefaults.group().getName());
            assertEquals(PosixFilePermissions.fromString("rw-r--r--"), posixDefaults.permissions());

            FileTime firstTime = FileTime.from(Instant.parse("2025-07-08T09:10:11Z"));
            FileTime secondTime = FileTime.from(Instant.parse("2025-08-09T10:11:12Z"));
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-r-----");
            sevenZipView.setTimes(firstTime, firstTime, firstTime);
            sevenZipView.setWindowsAttributes(0x20);
            posixView.setPermissions(permissions);
            SevenZipArkivoEntryAttributes configured = sevenZipView.readAttributes();
            PosixFileAttributes configuredPosix = posixView.readAttributes();

            assertEquals(firstTime, configured.lastModifiedTime());
            assertEquals(firstTime, configured.lastAccessTime());
            assertEquals(firstTime, configured.creationTime());
            assertEquals(0x20, configured.windowsAttributes() & 0xffff);
            assertEquals(0100640, configured.unixMode());
            assertEquals(permissions, configuredPosix.permissions());
            assertSevenZipPreEncodingState(configured);

            sevenZipView.setTimes(secondTime, secondTime, secondTime);
            sevenZipView.setWindowsAttributes(0x01);
            assertEquals(FileTime.fromMillis(0L), defaults.lastModifiedTime());
            assertEquals(SevenZipArkivoEntryAttributes.UNKNOWN_WINDOWS_ATTRIBUTES, defaults.windowsAttributes());
            assertEquals(FileTime.fromMillis(0L), posixDefaults.lastModifiedTime());
            assertEquals(firstTime, configured.lastModifiedTime());
            assertEquals(0x20, configured.windowsAttributes() & 0xffff);

            try (OutputStream output = file.openOutputStream()) {
                output.write(content);
            }

            assertSevenZipEntry(writer.beginDirectory("directory"), "directory", false, true, false);
            assertSevenZipEntry(writer.beginSymbolicLink("symbolic", "file.bin"), "symbolic", false, false, true);
        }

        assertTrue(archive.size() > 0);
    }

    /// Reads, verifies, and commits one pending TAR metadata-only entry.
    private static void assertTarEntry(
            ArkivoStreamingWriter.Entry entry,
            String expectedPath,
            byte expectedType,
            int expectedMode,
            boolean regularFile,
            boolean directory,
            boolean symbolicLink,
            @Nullable String expectedLink
    ) throws IOException {
        TarArkivoEntryAttributeView view = Objects.requireNonNull(
                entry.attributeView(TarArkivoEntryAttributeView.class)
        );
        TarArkivoEntryAttributes attributes = view.readAttributes();
        assertTarType(
                attributes,
                expectedPath,
                expectedType,
                expectedMode,
                regularFile,
                directory,
                symbolicLink,
                expectedLink
        );
        PosixFileAttributes posix = Objects.requireNonNull(
                entry.attributeView(PosixFileAttributeView.class)
        ).readAttributes();
        assertEquals(expectedMode, attributes.mode());
        assertEquals(expectedMode, permissionBits(posix.permissions()));
        entry.close();
    }

    /// Verifies one pending TAR entry's type projection and common pre-body values.
    private static void assertTarType(
            TarArkivoEntryAttributes attributes,
            String expectedPath,
            byte expectedType,
            int expectedMode,
            boolean regularFile,
            boolean directory,
            boolean symbolicLink,
            @Nullable String expectedLink
    ) {
        assertEquals(expectedPath, attributes.path());
        assertEquals(expectedType, attributes.typeFlag());
        assertEquals(expectedMode, attributes.mode());
        assertEquals(regularFile, attributes.isRegularFile());
        assertEquals(directory, attributes.isDirectory());
        assertEquals(symbolicLink, attributes.isSymbolicLink());
        assertFalse(attributes.isOther());
        assertEquals(expectedType == TarArkivoEntryAttributes.HARD_LINK_TYPE, attributes.isHardLink());
        assertEquals(expectedLink, attributes.linkName());
        assertEquals(0L, attributes.size());
        assertNull(attributes.fileKey());
    }

    /// Reads, verifies, and commits one pending ZIP metadata-only entry.
    private static void assertZipEntry(
            ArkivoStreamingWriter.Entry entry,
            String expectedPath,
            boolean regularFile,
            boolean directory,
            boolean symbolicLink
    ) throws IOException {
        ZipArkivoEntryAttributes attributes = Objects.requireNonNull(
                entry.attributeView(ZipArkivoEntryAttributeView.class)
        ).readAttributes();
        PosixFileAttributes posix = Objects.requireNonNull(
                entry.attributeView(PosixFileAttributeView.class)
        ).readAttributes();
        assertZipType(attributes, expectedPath, regularFile, directory, symbolicLink);
        assertEquals(ZipMethod.STORED, attributes.compressionMethod());
        assertEquals(0L, attributes.size());
        assertEquals(attributes.permissions(), posix.permissions());
        if (symbolicLink) {
            assertEquals(PosixFilePermissions.fromString("rwxrwxrwx"), attributes.permissions());
            assertEquals(3, attributes.versionMadeBy() >>> 8);
        }
        entry.close();
    }

    /// Verifies one pending ZIP entry's type projection and common pre-body values.
    private static void assertZipType(
            ZipArkivoEntryAttributes attributes,
            String expectedPath,
            boolean regularFile,
            boolean directory,
            boolean symbolicLink
    ) {
        assertEquals(expectedPath, attributes.path());
        assertEquals(regularFile, attributes.isRegularFile());
        assertEquals(directory, attributes.isDirectory());
        assertEquals(symbolicLink, attributes.isSymbolicLink());
        assertFalse(attributes.isOther());
        assertNull(attributes.fileKey());
    }

    /// Reads, verifies, and commits one pending 7z metadata-only entry.
    private static void assertSevenZipEntry(
            ArkivoStreamingWriter.Entry entry,
            String expectedPath,
            boolean regularFile,
            boolean directory,
            boolean symbolicLink
    ) throws IOException {
        SevenZipArkivoEntryAttributes attributes = Objects.requireNonNull(
                entry.attributeView(SevenZipArkivoEntryAttributeView.class)
        ).readAttributes();
        PosixFileAttributes posix = Objects.requireNonNull(
                entry.attributeView(PosixFileAttributeView.class)
        ).readAttributes();
        assertSevenZipType(attributes, expectedPath, regularFile, directory, symbolicLink);
        assertSevenZipPreEncodingState(attributes);
        if (symbolicLink) {
            assertEquals(0120777, attributes.unixMode());
            assertEquals(PosixFilePermissions.fromString("rwxrwxrwx"), posix.permissions());
        }
        entry.close();
    }

    /// Verifies one pending 7z entry's type projection.
    private static void assertSevenZipType(
            SevenZipArkivoEntryAttributes attributes,
            String expectedPath,
            boolean regularFile,
            boolean directory,
            boolean symbolicLink
    ) {
        assertEquals(expectedPath, attributes.path());
        assertEquals(regularFile, attributes.isRegularFile());
        assertEquals(directory, attributes.isDirectory());
        assertEquals(symbolicLink, attributes.isSymbolicLink());
        assertFalse(attributes.isOther());
        assertEquals(0L, attributes.size());
        assertNull(attributes.fileKey());
    }

    /// Verifies the sentinel metadata exposed before a pending 7z entry is encoded.
    private static void assertSevenZipPreEncodingState(SevenZipArkivoEntryAttributes attributes) {
        assertNull(attributes.coderGraph());
        assertFalse(attributes.solid());
        assertEquals(SevenZipArkivoEntryAttributes.NO_SUBSTREAM_INDEX, attributes.substreamIndex());
        assertEquals(0, attributes.substreamCount());
        assertEquals(SevenZipArkivoEntryAttributes.NO_DATA_OFFSET, attributes.dataOffset());
        assertEquals(0L, attributes.decodedOffset());
        assertEquals(0L, attributes.packedSize());
        assertEquals(SevenZipArkivoEntryAttributes.UNKNOWN_CRC32, attributes.packedCrc32());
        assertEquals(List.of(), attributes.packedStreams());
        assertThrows(UnsupportedOperationException.class, () -> attributes.packedStreams().clear());
        assertEquals(SevenZipArkivoEntryAttributes.UNKNOWN_CRC32, attributes.crc32());
    }

    /// Converts POSIX permissions to their low nine Unix mode bits.
    private static int permissionBits(Set<PosixFilePermission> permissions) {
        int bits = 0;
        for (PosixFilePermission permission : permissions) {
            bits |= switch (permission) {
                case OWNER_READ -> 0400;
                case OWNER_WRITE -> 0200;
                case OWNER_EXECUTE -> 0100;
                case GROUP_READ -> 0040;
                case GROUP_WRITE -> 0020;
                case GROUP_EXECUTE -> 0010;
                case OTHERS_READ -> 0004;
                case OTHERS_WRITE -> 0002;
                case OTHERS_EXECUTE -> 0001;
            };
        }
        return bits;
    }
}
