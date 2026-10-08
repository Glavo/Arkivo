// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
import org.glavo.arkivo.archive.zip.ZipLegacyMetadataDecoder;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Tests automatic decoding with independently encoded ZIP records and bounded archive evidence.
@NotNullByDefault
public final class ZipAutoMetadataDecoderTest {
    /// Recognizes unmarked Unicode and decisive legacy script samples through both public reading paths.
    @ParameterizedTest
    @CsvSource({
            "UTF-8, résumé/日本語.txt",
            "GB18030, 下载/软件安装说明.txt",
            "GB18030, 软件安装说明/readme.txt",
            "Big5, 下載/軟體安裝說明.txt",
            "windows-31j, 日本語/こんにちは世界.txt",
            "EUC-JP, 日本語/こんにちは世界.txt",
            "x-windows-949, 자료/사용설명파일.txt",
            "IBM437, München.txt"
    })
    public void independentWriterInteroperability(String encoding, String name, @TempDir Path directory)
            throws IOException {
        byte[] archive = archive(new Sample(name, encoding, null, false, 0));
        Path file = directory.resolve("legacy.zip");
        Files.write(file, archive);
        try (var fs = ZipArkivoFileSystem.open(file)) {
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath(name)));
            assertArrayEquals(name.getBytes(Charset.forName(encoding)),
                    Files.readAttributes(fs.getPath(name), ZipArkivoEntryAttributes.class).rawPath());
        }
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertTrue(reader.next());
            assertEquals(name, reader.readAttributes().path());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
            }
            assertFalse(reader.next());
        }
    }

    /// Keeps insufficient evidence deterministic, including malformed and short multibyte inputs.
    @ParameterizedTest
    @ValueSource(strings = {"82", "E9", "FF", "C3", "C080", "E4B8", "81FF", "D6D0", "A4A4"})
    public void ambiguousBytesUseCp437(String hex) throws IOException {
        byte[] raw = java.util.HexFormat.of().parseHex(hex);
        assertEquals(new String(raw, Charset.forName("IBM437")),
                ZipArchiveOptions.DEFAULT_LEGACY_METADATA_DECODER.decode(raw));
    }

    /// Does not modify or retain heap, direct, sliced, or read-only caller buffers.
    @Test
    public void bufferStateAndConcurrentCalls() {
        IntStream.range(0, 100).parallel().forEach(index -> {
            String expected = index % 2 == 0 ? "résumé.txt" : "软件安装说明.txt";
            byte[] raw = expected.getBytes(Charset.forName(index % 2 == 0 ? "UTF-8" : "GB18030"));
            ByteBuffer buffer = index % 3 == 0 ? ByteBuffer.allocateDirect(raw.length + 2)
                    : ByteBuffer.allocate(raw.length + 2);
            buffer.position(1).put(raw).flip().position(1);
            buffer = buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
            buffer.mark();
            try {
                assertEquals(expected, ZipArchiveOptions.DEFAULT_LEGACY_METADATA_DECODER.decode(buffer));
                assertEquals(1, buffer.position());
                assertEquals(raw.length + 1, buffer.limit());
                assertEquals(ByteOrder.LITTLE_ENDIAN, buffer.order());
                buffer.reset();
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
        });
    }

    /// Uses valid Unicode counterparts to disambiguate short names, without contaminating streaming or other archives.
    @ParameterizedTest
    @ValueSource(strings = {"GB18030", "Big5", "windows-31j", "x-windows-949", "windows-1251", "IBM866", "windows-1252"})
    public void unicodeEvidenceIsArchiveScoped(String encoding, @TempDir Path directory) throws IOException {
        String anchor = switch (encoding) {
            case "GB18030" -> "文件目录.txt";
            case "Big5" -> "檔案目錄.txt";
            case "windows-31j" -> "あいうえお.txt";
            case "x-windows-949" -> "가나다라마.txt";
            case "windows-1251", "IBM866" -> "Привет.txt";
            default -> "€résumé.txt";
        };
        String shortName = anchor.substring(0, 1) + ".txt";
        Sample target = new Sample(shortName, encoding, null, false, 0);
        byte[] archive = archive(target, new Sample(anchor, encoding, null, true, 0));
        Path file = directory.resolve("anchor.zip");
        Files.write(file, archive);
        try (var fs = ZipArkivoFileSystem.open(file)) {
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath(shortName)));
        }
        // A later central-directory anchor cannot influence an earlier streaming name.
        String fallback = new String(shortName.getBytes(Charset.forName(encoding)), Charset.forName("IBM437"));
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertTrue(reader.next());
            assertEquals(fallback, reader.readAttributes().path());
        }
        Files.write(file, archive(target));
        try (var fs = ZipArkivoFileSystem.open(file)) {
            assertTrue(Files.exists(fs.getPath(fallback)));
        }
    }

    /// Preserves archive-derived paths and comments across update snapshots and raw-record copying.
    @Test
    public void updateKeepsPreparedDecoder(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("update.zip");
        Files.write(file, archive(
                new Sample("€résumé.txt", "windows-1252", "€résumé", true, 0),
                new Sample("é.txt", "windows-1252", "é", false, 0)));
        try (var fs = ZipArkivoFileSystem.update(file)) {
            var attributes = Files.readAttributes(fs.getPath("é.txt"), ZipArkivoEntryAttributes.class);
            assertEquals("é.txt", attributes.path());
            assertEquals("é", attributes.comment());
            Files.write(fs.getPath("new.txt"), new byte[]{4});
        }
        try (var fs = ZipArkivoFileSystem.open(file)) {
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath("é.txt")));
            assertEquals("é", Files.readAttributes(fs.getPath("é.txt"), ZipArkivoEntryAttributes.class).comment());
            assertArrayEquals(new byte[]{4}, Files.readAllBytes(fs.getPath("new.txt")));
        }
    }

    /// Keeps names and comments independent and does not propagate creator-specific evidence to other hosts.
    @Test
    public void evidenceDoesNotCrossMetadataKindsOrCreators(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("separate.zip");
        Files.write(file, archive(
                new Sample("€résumé.txt", "windows-1252", null, true, 0),
                new Sample("é.txt", "windows-1252", "é", false, 0),
                new Sample("other-é.txt", "windows-1252", null, false, 3)));
        try (var fs = ZipArkivoFileSystem.open(file)) {
            assertTrue(Files.exists(fs.getPath("é.txt")));
            String fallback = new String(new byte[]{(byte) 0xe9}, Charset.forName("IBM437"));
            assertEquals(fallback, Files.readAttributes(fs.getPath("é.txt"), ZipArkivoEntryAttributes.class).comment());
            assertTrue(Files.exists(fs.getPath("other-" + fallback + ".txt")));
        }
    }

    /// Requires multiple distinct decisive samples before extending a heuristic to an ambiguous name.
    @Test
    public void archiveVotesAndDuplicatePrefixes(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("votes.zip");
        Sample target = new Sample("中.txt", "GB18030", null, false, 0);
        Files.write(file, archive(target,
                new Sample("软件安装说明.txt", "GB18030", null, false, 0),
                new Sample("图片视频下载.txt", "GB18030", null, false, 0),
                new Sample("用户数据备份.txt", "GB18030", null, false, 0)));
        try (var fs = ZipArkivoFileSystem.open(file)) {
            assertTrue(Files.exists(fs.getPath("中.txt")));
        }
        Files.write(file, archive(target,
                new Sample("a/软件安装说明.txt", "GB18030", null, false, 0),
                new Sample("b/软件安装说明.txt", "GB18030", null, false, 0),
                new Sample("c/软件安装说明.txt", "GB18030", null, false, 0)));
        try (var fs = ZipArkivoFileSystem.open(file)) {
            String fallback = new String("中.txt".getBytes(Charset.forName("GB18030")), Charset.forName("IBM437"));
            assertTrue(Files.exists(fs.getPath(fallback)));
        }
    }

    /// Does not apply conflicting Unicode evidence or arbitrarily break ties between compatible code pages.
    @Test
    public void conflictingAndAmbiguousAnchors(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("conflict.zip");
        Files.write(file, archive(
                new Sample("€résumé.txt", "windows-1252", null, true, 0),
                new Sample("Öäü.txt", "IBM437", null, true, 0),
                new Sample("é.txt", "windows-1252", null, false, 0)));
        String fallback = new String("é.txt".getBytes(Charset.forName("windows-1252")), Charset.forName("IBM437"));
        try (var fs = ZipArkivoFileSystem.open(file)) {
            assertTrue(Files.exists(fs.getPath(fallback)));
        }
        Files.write(file, archive(new Sample("café.txt", "IBM437", null, true, 0),
                new Sample("╒.txt", "IBM437", null, false, 0)));
        try (var fs = ZipArkivoFileSystem.open(file)) {
            assertTrue(Files.exists(fs.getPath("╒.txt")));
        }
    }

    /// Decodes explicit directory records and their descendants consistently when the directory carries the evidence.
    @Test
    public void directoryNamesAndAsciiChildren(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("directories.zip");
        Files.write(file, archive(new Sample("软件安装说明/", "GB18030", null, false, 0),
                new Sample("软件安装说明/readme.txt", "GB18030", null, false, 0)));
        try (var fs = ZipArkivoFileSystem.open(file)) {
            assertTrue(Files.isDirectory(fs.getPath("软件安装说明")));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath("软件安装说明/readme.txt")));
        }
        Files.write(file, archive(new Sample("中/", "GB18030", null, false, 0),
                new Sample("中/software.txt", "GB18030", null, false, 0),
                new Sample("中/软件安装说明.txt", "GB18030", null, false, 0)));
        try (var fs = ZipArkivoFileSystem.open(file)) {
            assertTrue(Files.isDirectory(fs.getPath("中")));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath("中/software.txt")));
            try (var roots = Files.list(fs.getPath("/"))) {
                assertEquals(1, roots.count());
            }
        }
    }

    /// Conflicting interpretations of the same raw directory prefix are not resolved by a majority elsewhere.
    @Test
    public void conflictingParentHints(@TempDir Path directory) throws IOException {
        String big5Parent = new String("中".getBytes(Charset.forName("GB18030")), Charset.forName("Big5"));
        ArrayList<Sample> samples = new ArrayList<>();
        samples.add(new Sample("中/", "GB18030", null, false, 0));
        samples.add(new Sample("中/软件安装说明.txt", "GB18030", null, false, 0));
        samples.add(new Sample(big5Parent + "/軟體安裝說明.txt", "Big5", null, false, 0));
        for (String name : new String[]{"图片视频下载", "用户数据备份", "中文简体资料", "工作学习记录", "系统管理说明"}) {
            samples.add(new Sample(name + ".txt", "GB18030", null, false, 0));
        }
        Path file = directory.resolve("prefix-conflict.zip");
        Files.write(file, archive(samples.toArray(Sample[]::new)));
        try (var fs = ZipArkivoFileSystem.open(file)) {
            String fallback = new String("中".getBytes(Charset.forName("GB18030")), Charset.forName("IBM437"));
            assertTrue(Files.isDirectory(fs.getPath(fallback)));
        }
    }

    /// An explicit charset remains authoritative even when unmarked bytes are valid UTF-8.
    @Test
    public void fixedCharsetOverridesAutomaticUtf8(@TempDir Path directory) throws IOException {
        byte[] bytes = archive(new Sample("résumé.txt", "UTF-8", null, false, 0));
        Charset cp437 = Charset.forName("IBM437");
        var options = ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(ArchiveMetadataDecoder.forCharset(cp437));
        String expected = new String("résumé.txt".getBytes(StandardCharsets.UTF_8), cp437);
        Path file = directory.resolve("fixed.zip");
        Files.write(file, bytes);
        try (var fs = ZipArkivoFileSystem.open(file, options)) {
            assertTrue(Files.exists(fs.getPath(expected)));
        }
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(bytes), options)) {
            assertTrue(reader.next());
            assertEquals(expected, reader.readAttributes().path());
        }
    }

    /// Allows a decisive individual name to override a majority encoding hint.
    @Test
    public void mixedEncodings(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("mixed.zip");
        Sample[] samples = {
                new Sample("软件安装说明.txt", "GB18030", null, false, 0),
                new Sample("图片视频下载.txt", "GB18030", null, false, 0),
                new Sample("用户数据备份.txt", "GB18030", null, false, 0),
                new Sample("こんにちは世界.txt", "windows-31j", null, false, 0),
                new Sample("é-世界.txt", "UTF-8", null, false, 0)
        };
        Files.write(file, archive(samples));
        try (var fs = ZipArkivoFileSystem.open(file)) {
            for (Sample sample : samples) {
                assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath(sample.name())));
            }
        }
    }

    /// Stops collecting after the field-count budget, while the normal parser still validates later records.
    @ParameterizedTest
    @ValueSource(ints = {254, 255})
    public void sampleBudgetBoundary(int fillerCount, @TempDir Path directory) throws IOException {
        ArrayList<Sample> samples = new ArrayList<>();
        samples.add(new Sample("é.txt", "windows-1252", null, false, 0));
        for (int index = 0; index < fillerCount; index++) {
            samples.add(new Sample(index + "/München.txt", "IBM437", null, false, 0));
        }
        samples.add(new Sample("€résumé.txt", "windows-1252", null, true, 0));
        byte[] bytes = archive(samples.toArray(Sample[]::new));
        Path file = directory.resolve("budget.zip");
        Files.write(file, bytes);
        String expected = fillerCount == 254 ? "é.txt"
                : new String("é.txt".getBytes(Charset.forName("windows-1252")), Charset.forName("IBM437"));
        try (var fs = ZipArkivoFileSystem.open(file)) {
            assertTrue(Files.exists(fs.getPath(expected)));
            assertTrue(Files.exists(fs.getPath("€résumé.txt")));
        }
        // Corrupt the last central record, beyond the automatic decoder's sampling prefix.
        int last = bytes.length - 22 - 46 - "€résumé.txt".getBytes(Charset.forName("windows-1252")).length
                - unicodeExtra(0x7075, "€résumé.txt", Charset.forName("windows-1252")).length;
        bytes[last] = 0;
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> {
            try (var fs = ZipArkivoFileSystem.open(file); var stream = Files.newDirectoryStream(fs.getPath("/"))) {
                stream.iterator().hasNext();
            }
        });
    }

    /// Caps traversal even if all earlier entries are ASCII and consume no sample budget.
    @Test
    public void recordBudget(@TempDir Path directory) throws IOException {
        ArrayList<Sample> samples = new ArrayList<>();
        samples.add(new Sample("é.txt", "windows-1252", null, false, 0));
        for (int index = 1; index < 4096; index++) {
            samples.add(new Sample(index + ".txt", "UTF-8", null, false, 0));
        }
        samples.add(new Sample("€résumé.txt", "windows-1252", null, true, 0));
        Path file = directory.resolve("record-budget.zip");
        Files.write(file, archive(samples.toArray(Sample[]::new)));
        try (var fs = ZipArkivoFileSystem.open(file)) {
            String fallback = new String("é.txt".getBytes(Charset.forName("windows-1252")), Charset.forName("IBM437"));
            assertTrue(Files.exists(fs.getPath(fallback)));
        }
    }

    /// Includes extra-field copies in the byte budget, independently of the sample-count limit.
    @ParameterizedTest
    @ValueSource(ints = {65400, 65500})
    public void sampleByteBudget(int extraLength) throws IOException {
        byte[] filler = centralBytes(new Sample("München.txt", "IBM437", null, false, 0));
        int extraStart = filler.length;
        filler = Arrays.copyOf(filler, filler.length + extraLength);
        ByteArrayAccess.writeShortLittleEndian(filler, 30, (short) extraLength);
        ByteArrayAccess.writeShortLittleEndian(filler, extraStart, (short) 0xbeef);
        ByteArrayAccess.writeShortLittleEndian(filler, extraStart + 2, (short) (extraLength - 4));
        ByteArrayOutputStream central = new ByteArrayOutputStream();
        central.write(centralBytes(new Sample("é.txt", "windows-1252", null, false, 0)));
        central.write(filler);
        central.write(centralBytes(new Sample("€résumé.txt", "windows-1252", null, true, 0)));
        var decoder = ZipEntryNameDecoder.forCentralDirectory(ZipAutoMetadataDecoder.DEFAULT,
                ByteBuffer.wrap(central.toByteArray()));
        byte[] raw = "é.txt".getBytes(Charset.forName("windows-1252"));
        String expected = extraLength == 65400 ? "é.txt" : new String(raw, Charset.forName("IBM437"));
        assertEquals(expected, decoder.decodePath(raw, 0, new byte[0],
                ZipLegacyMetadataDecoder.HeaderSource.CENTRAL_DIRECTORY, 20, 20, 0));
    }

    /// Extracts one independently generated central record for bounded-analysis tests.
    private static byte[] centralBytes(Sample sample) throws IOException {
        byte[] bytes = archive(sample);
        int central = ByteArrayAccess.readIntLittleEndian(bytes, bytes.length - 6);
        return Arrays.copyOfRange(bytes, central, bytes.length - 22);
    }

    /// Freezes the result without retaining central-directory storage or changing the supplied view.
    @Test
    public void preparedDecoderDoesNotRetainBuffers() throws IOException {
        byte[] bytes = archive(new Sample("€résumé.txt", "windows-1252", null, true, 0));
        int central = ByteArrayAccess.readIntLittleEndian(bytes, bytes.length - 6);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).position(central).limit(bytes.length - 22)
                .asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
        buffer.mark();
        var decoder = ZipEntryNameDecoder.forCentralDirectory(ZipAutoMetadataDecoder.DEFAULT, buffer);
        assertEquals(central, buffer.position());
        assertEquals(ByteOrder.BIG_ENDIAN, buffer.order());
        buffer.reset();
        Arrays.fill(bytes, (byte) 0);
        assertEquals("é.txt", decoder.decodePath("é.txt".getBytes(Charset.forName("windows-1252")),
                0, new byte[0], ZipLegacyMetadataDecoder.HeaderSource.CENTRAL_DIRECTORY, 20, 20, 0));
    }

    /// A bad Unicode CRC supplies no evidence for other entries.
    @Test
    public void invalidUnicodeCrcIsNotEvidence(@TempDir Path directory) throws IOException {
        byte[] bytes = archive(new Sample("é.txt", "windows-1252", null, false, 0),
                new Sample("€résumé.txt", "windows-1252", null, true, 0));
        int offset = ByteArrayAccess.readIntLittleEndian(bytes, bytes.length - 6);
        offset += 46 + "é.txt".getBytes(Charset.forName("windows-1252")).length;
        int extra = offset + 46 + "€résumé.txt".getBytes(Charset.forName("windows-1252")).length;
        bytes[extra + 5] ^= 1;
        Path file = directory.resolve("crc.zip");
        Files.write(file, bytes);
        try (var fs = ZipArkivoFileSystem.open(file)) {
            String fallback = new String("é.txt".getBytes(Charset.forName("windows-1252")), Charset.forName("IBM437"));
            assertTrue(Files.exists(fs.getPath(fallback)));
        }
    }

    /// Honors custom decoding without any archive analysis or extra callbacks.
    @Test
    public void customDecoderBypassesPreparation() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        ArchiveMetadataDecoder configured = bytes -> {
            calls.incrementAndGet();
            return "custom";
        };
        ByteBuffer malformed = ByteBuffer.wrap(new byte[]{1});
        var decoder = ZipEntryNameDecoder.forCentralDirectory(configured, malformed);
        assertEquals(0, calls.get());
        assertEquals("custom", decoder.decodePath(new byte[]{(byte) 0xff}, 0, new byte[0]));
        assertEquals(1, calls.get());
        assertEquals(0, malformed.position());
    }

    /// Leaves strict Unicode validation and unsafe-path rejection in the regular reader.
    @Test
    public void automaticDetectionDoesNotRepairDeclaredUtf8OrPaths(@TempDir Path directory) throws IOException {
        byte[] bytes = archive(new Sample("é.txt", "windows-1252", null, false, 0));
        int central = ByteArrayAccess.readIntLittleEndian(bytes, bytes.length - 6);
        ByteArrayAccess.writeShortLittleEndian(bytes, 6, (short) ZipEntryNameDecoder.UTF_8_FLAG);
        ByteArrayAccess.writeShortLittleEndian(bytes, central + 8, (short) ZipEntryNameDecoder.UTF_8_FLAG);
        Path file = directory.resolve("invalid.zip");
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> {
            try (var fs = ZipArkivoFileSystem.open(file); var stream = Files.newDirectoryStream(fs.getPath("/"))) {
                stream.iterator().hasNext();
            }
        });
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(bytes))) {
            assertThrows(IOException.class, reader::next);
        }
        Files.write(file, archive(new Sample("../软件安装说明.txt", "GB18030", null, false, 0)));
        assertThrows(IOException.class, () -> {
            try (var fs = ZipArkivoFileSystem.open(file); var stream = Files.newDirectoryStream(fs.getPath("/"))) {
                stream.iterator().hasNext();
            }
        });
    }

    /// Constructs mixed-encoding archives from JDK-generated records without storing binary fixtures.
    private static byte[] archive(Sample... samples) throws IOException {
        ByteArrayOutputStream local = new ByteArrayOutputStream();
        ArrayList<byte[]> centralRecords = new ArrayList<>();
        for (Sample sample : samples) {
            Charset charset = Charset.forName(sample.encoding());
            ByteArrayOutputStream single = new ByteArrayOutputStream();
            try (var output = new ZipOutputStream(single, charset)) {
                ZipEntry entry = new ZipEntry(sample.name());
                entry.setComment(sample.comment());
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(3);
                CRC32 crc = new CRC32();
                crc.update(new byte[]{1, 2, 3});
                entry.setCrc(crc.getValue());
                if (sample.unicode()) {
                    ByteArrayOutputStream extra = new ByteArrayOutputStream();
                    extra.write(unicodeExtra(0x7075, sample.name(), charset));
                    if (sample.comment() != null) extra.write(unicodeExtra(0x6375, sample.comment(), charset));
                    entry.setExtra(extra.toByteArray());
                }
                output.putNextEntry(entry);
                output.write(new byte[]{1, 2, 3});
                output.closeEntry();
            }
            byte[] bytes = single.toByteArray();
            int centralOffset = ByteArrayAccess.readIntLittleEndian(bytes, bytes.length - 6);
            byte[] central = Arrays.copyOfRange(bytes, centralOffset, bytes.length - 22);
            ByteArrayAccess.writeIntLittleEndian(central, 42, local.size());
            ByteArrayAccess.writeShortLittleEndian(central, 4, (short) (sample.creator() << 8 | 20));
            // Exercise unmarked UTF-8 too; explicit Unicode behavior is tested separately.
            ByteArrayAccess.writeShortLittleEndian(central, 8, (short) 0);
            ByteArrayAccess.writeShortLittleEndian(bytes, 6, (short) 0);
            centralRecords.add(central);
            local.write(bytes, 0, centralOffset);
        }
        int centralOffset = local.size();
        for (byte[] central : centralRecords) local.write(central);
        ByteBuffer end = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        end.putInt(0x06054b50).putShort((short) 0).putShort((short) 0)
                .putShort((short) samples.length).putShort((short) samples.length)
                .putInt(local.size() - centralOffset).putInt(centralOffset).putShort((short) 0);
        local.write(end.array());
        return local.toByteArray();
    }

    /// Encodes a CRC-bound Info-ZIP Unicode counterpart independently of the production reader.
    private static byte[] unicodeExtra(int id, String value, Charset charset) {
        CRC32 crc = new CRC32();
        crc.update(value.getBytes(charset));
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(9 + utf8.length).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) id).putShort((short) (5 + utf8.length)).put((byte) 1)
                .putInt((int) crc.getValue()).put(utf8).array();
    }

    /// Describes one independently encoded entry.
    /// @param name the expected decoded name
    /// @param encoding the JDK output charset
    /// @param comment the optional entry comment
    /// @param unicode whether matching Unicode extra fields are included
    /// @param creator the ZIP creator-system identifier
    @NotNullByDefault
    private record Sample(String name, String encoding, @Nullable String comment, boolean unicode, int creator) {
    }
}
