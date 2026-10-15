// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
import org.glavo.arkivo.archive.zip.ZipLegacyMetadataDecoder;
import org.glavo.arkivo.encoding.detector.internal.EncodingDetector;
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
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Tests automatic decoding with independently encoded ZIP records and bounded archive evidence.
@NotNullByDefault
public final class ZipAutoMetadataDecoderTest {
    /// Array decoding preserves every ASCII value, including controls, without modifying the source.
    @Test
    public void asciiArrayMatchesBufferDecoding() throws IOException {
        byte[] raw = new byte[128];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) i;
        String expected = new String(raw, StandardCharsets.US_ASCII);
        ArchiveMetadataDecoder decoder = ZipArchiveOptions.DEFAULT_LEGACY_METADATA_DECODER;
        assertFalse(decoder instanceof ZipLegacyMetadataDecoder);
        assertEquals(expected, decoder.decode(raw));
        assertEquals(expected, decoder.decode(ByteBuffer.wrap(raw).asReadOnlyBuffer()));
        assertArrayEquals(expected.getBytes(StandardCharsets.US_ASCII), raw);
        assertEquals("", decoder.decode(new byte[0]));
        assertThrows(NullPointerException.class, () -> decoder.decode((byte[]) null));
    }

    /// High bytes at either end bypass the ASCII shortcut, including a long all-ASCII prefix.
    @Test
    public void arrayAndBufferInferenceAgree() throws IOException {
        for (int length : new int[]{1, 7, 4097}) {
            for (int position : new int[]{0, length - 1}) {
                byte[] raw = new byte[length];
                Arrays.fill(raw, (byte) 'a');
                raw[position] = (byte) 0xff;
                byte[] original = raw.clone();
                assertEquals(ZipAutoMetadataDecoder.DEFAULT.decode(ByteBuffer.wrap(raw)),
                        ZipAutoMetadataDecoder.DEFAULT.decode(raw));
                assertArrayEquals(original, raw);
            }
        }
    }

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

    /// Long ASCII prefixes leave non-ASCII evidence available to both streaming and indexed readers.
    @ParameterizedTest
    @CsvSource({"UTF-8, 日本語こんにちは世界.txt", "GB18030, 软件安装说明.txt",
            "windows-31j, 日本語こんにちは世界.txt"})
    public void longNamesInBothReaders(String encoding, String suffix, @TempDir Path directory) throws IOException {
        Path file = directory.resolve("long-names.zip");
        for (String separator : List.of("", "/")) {
            String name = "a".repeat(5000) + separator + suffix;
            byte[] bytes = archive(new Sample(name, encoding, null, false, 3));
            Files.write(file, bytes);
            try (var fs = ZipArkivoFileSystem.open(file)) {
                assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath(name)));
            }
            try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(bytes))) {
                assertTrue(reader.next());
                assertEquals(name, reader.readAttributes().path());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
                }
                assertFalse(reader.next());
            }
        }
    }

    /// Keeps inputs without usable pair evidence on the deterministic ZIP fallback.
    @ParameterizedTest
    @ValueSource(strings = {"82", "E9", "FF", "C3", "81FF"})
    public void ambiguousBytesUseCp437(String hex) throws IOException {
        byte[] raw = java.util.HexFormat.of().parseHex(hex);
        assertEquals(new String(raw, Charset.forName("IBM437")),
                ZipArchiveOptions.DEFAULT_LEGACY_METADATA_DECODER.decode(raw));
    }

    /// Applies ASCII bypass only after checking authoritative Unicode, without bypassing user callbacks.
    @Test
    public void asciiPreservesDeclarationPriority() throws IOException {
        byte[] raw = "??.txt".getBytes(StandardCharsets.US_ASCII);
        byte[] extra = unicodeExtra(0x7075, "中文.txt", StandardCharsets.US_ASCII);
        var decoder = new ZipEntryNameDecoder(ZipAutoMetadataDecoder.DEFAULT);
        assertEquals("中文.txt", decoder.decodePath(raw, 0, extra));
        extra[extra.length - 1] = (byte) 0xFF;
        assertThrows(IOException.class, () -> decoder.decodePath(raw, 0, extra));
        AtomicInteger calls = new AtomicInteger();
        var custom = new ZipEntryNameDecoder(bytes -> {
            calls.incrementAndGet();
            return "custom.txt";
        });
        assertEquals("custom.txt", custom.decodePath(raw, 0, new byte[0]));
        assertEquals(1, calls.get());
    }

    /// Uses documented OEM/ANSI branches without assigning a language or a Windows family to Unix producers.
    @ParameterizedTest
    @CsvSource({
            "-1, 0, UNKNOWN, NONE", "0x0314, 0, CENTRAL_DIRECTORY, NONE",
            "20, 0, CENTRAL_DIRECTORY, OEM", "25, 0, CENTRAL_DIRECTORY, OEM",
            "25, 65536, CENTRAL_DIRECTORY, ANSI", "26, 0, LOCAL_FILE_HEADER, ANSI",
            "40, 65536, CENTRAL_DIRECTORY, ANSI", "0x0614, 0, CENTRAL_DIRECTORY, OEM",
            "0x0A32, 0, CENTRAL_DIRECTORY, OEM", "0x0B32, 0, CENTRAL_DIRECTORY, OEM",
            "0x0A14, 0, CENTRAL_DIRECTORY, ANSI"
    })
    public void producerCodePageFamilies(int madeBy, long attributes, ZipLegacyMetadataDecoder.HeaderSource source,
                                        EncodingDetector.Preference expected) {
        assertEquals(expected, ZipLegacyCodePages.preference(madeBy, attributes, source));
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
        String independent;
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive(target)))) {
            assertTrue(reader.next());
            independent = reader.readAttributes().path();
        }
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertTrue(reader.next());
            assertEquals(independent, reader.readAttributes().path());
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
            assertArrayEquals(new byte[]{(byte) 0xe9}, attributes.rawComment());
            Files.write(fs.getPath("new.txt"), new byte[]{4});
        }
        try (var fs = ZipArkivoFileSystem.open(file)) {
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath("é.txt")));
            assertArrayEquals(new byte[]{(byte) 0xe9}, Files.readAttributes(fs.getPath("é.txt"), ZipArkivoEntryAttributes.class).rawComment());
            assertArrayEquals(new byte[]{4}, Files.readAllBytes(fs.getPath("new.txt")));
        }
    }

    /// Shares the common legacy name encoding across creator systems.
    @Test
    public void archiveChoiceCrossesCreators(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("separate.zip");
        Files.write(file, archive(
                new Sample("€résumé.txt", "windows-1252", null, true, 0),
                new Sample("é.txt", "windows-1252", "é", false, 0),
                new Sample("other-é.txt", "windows-1252", null, false, 3)));
        try (var fs = ZipArkivoFileSystem.open(file)) {
            assertTrue(Files.exists(fs.getPath("é.txt")));
            assertArrayEquals(new byte[]{(byte) 0xe9}, Files.readAttributes(fs.getPath("é.txt"), ZipArkivoEntryAttributes.class).rawComment());
            assertTrue(Files.exists(fs.getPath("other-é.txt")));
        }
    }

    /// Uses the shared encoding for short names even when repeated samples add no new features.
    @Test
    public void jointEvidenceAndDuplicatePrefixes(@TempDir Path directory) throws IOException {
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
            assertTrue(Files.exists(fs.getPath("中.txt")));
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

    /// A Unicode counterpart shared by several code pages does not settle their other differing mappings.
    @Test
    public void ambiguousAnchorDoesNotSelectArbitraryCharset() throws IOException {
        byte[] anchor = centralBytes(new Sample("é", "windows-1252", null, true, 3));
        byte[] undecided = centralBytes(new Sample("£", "windows-1252", null, false, 3));
        for (boolean reversed : new boolean[]{false, true}) {
            var central = ByteBuffer.allocate(anchor.length + undecided.length);
            central.put(reversed ? undecided : anchor).put(reversed ? anchor : undecided).flip();
            var decoded = new HashMap<Integer, String>();
            assertEquals(ZipAutoMetadataDecoder.CP437, ZipNameEncodingInference.select(central, decoded));
            assertTrue(decoded.isEmpty());
            assertEquals(0, central.position());
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

    /// Does not maintain directory-specific guesses when legacy bytes have conflicting interpretations.
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
            try (var roots = Files.list(fs.getPath("/"))) {
                assertEquals(1, roots.filter(Files::isDirectory).count());
            }
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

    /// Mixed undeclared encodings have no recovery guarantee, but every field uses the same frozen choice.
    @Test
    public void mixedBytesDoNotTriggerPerEntryOverrides(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("mixed.zip");
        Sample[] samples = {
                new Sample("软件安装说明.txt", "GB18030", null, false, 0),
                new Sample("图片视频下载.txt", "GB18030", null, false, 0),
                new Sample("用户数据备份.txt", "GB18030", null, false, 0),
                new Sample("こんにちは世界.txt", "windows-31j", null, false, 0),
                new Sample("é-世界.txt", "UTF-8", null, false, 0)
        };
        byte[] bytes = archive(samples);
        int start = ByteArrayAccess.readIntLittleEndian(bytes, bytes.length - 6);
        Charset charset = ZipNameEncodingInference.select(
                ByteBuffer.wrap(bytes).position(start).limit(bytes.length - 22), new HashMap<>());
        assertNotNull(charset);
        Files.write(file, bytes);
        try (var fs = ZipArkivoFileSystem.open(file)) {
            for (Sample sample : samples) {
                String expected = charset.newDecoder().decode(ByteBuffer.wrap(
                        sample.name().getBytes(Charset.forName(sample.encoding())))).toString();
                assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath(expected)));
            }
        }
    }

    /// Samples by content rather than stopping at the field-count boundary in physical record order.
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
        String expected = "é.txt";
        try (var fs = ZipArkivoFileSystem.open(file)) {
            assertTrue(Files.exists(fs.getPath(expected)));
            assertTrue(Files.exists(fs.getPath("€résumé.txt")));
        }
        // Corrupt the last central record; sampling must not bypass structural validation.
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

    /// Finds late Unicode evidence without spending the sample budget on preceding ASCII entries.
    @Test
    public void evidenceAfterAsciiPrefix(@TempDir Path directory) throws IOException {
        ArrayList<Sample> samples = new ArrayList<>();
        samples.add(new Sample("é.txt", "windows-1252", null, false, 0));
        for (int index = 1; index < 4096; index++) {
            samples.add(new Sample(index + ".txt", "UTF-8", null, false, 0));
        }
        samples.add(new Sample("€résumé.txt", "windows-1252", null, true, 0));
        Path file = directory.resolve("record-budget.zip");
        Files.write(file, archive(samples.toArray(Sample[]::new)));
        try (var fs = ZipArkivoFileSystem.open(file)) {
            assertTrue(Files.exists(fs.getPath("é.txt")));
        }
    }

    /// Does not retain unrelated extra fields or charge them against the raw-name sampling budget.
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
                ByteBuffer.wrap(central.toByteArray())).decoder();
        byte[] raw = "é.txt".getBytes(Charset.forName("windows-1252"));
        String expected = "é.txt";
        assertEquals(expected, decoder.decodePath(raw, 0, new byte[0],
                ZipLegacyMetadataDecoder.HeaderSource.CENTRAL_DIRECTORY, 20, 20, 0));
    }

    /// Keeps selection stable under entry reordering across both field-count and raw-byte sampling limits.
    @ParameterizedTest
    @ValueSource(ints = {0, 768})
    public void samplingIsIndependentOfRecordOrder(int padding) throws IOException {
        var records = new ArrayList<byte[]>();
        for (int index = 0; index < 300; index++) {
            records.add(centralBytes(new Sample("a".repeat(padding) + index + "/软件安装说明.txt",
                    "GB18030", null, false, 0)));
        }
        records.add(centralBytes(new Sample("图片视频下载.txt", "GB18030", null, false, 0)));
        records.add(centralBytes(new Sample("€résumé.txt", "windows-1252", null, true, 3)));
        var queries = List.of(new Sample("中.txt", "GB18030", null, false, 0),
                new Sample("é.txt", "windows-1252", null, false, 3),
                new Sample("こんにちは世界.txt", "windows-31j", null, false, 0));
        var expected = new ArrayList<String>();
        for (int round = 0; round < 3; round++) {
            Collections.shuffle(records, new Random(437 + round));
            var central = new ByteArrayOutputStream();
            for (var record : records) central.write(record);
            var decoder = ZipEntryNameDecoder.forCentralDirectory(ZipAutoMetadataDecoder.DEFAULT,
                    ByteBuffer.wrap(central.toByteArray())).decoder();
            var actual = new ArrayList<String>();
            for (var query : queries) actual.add(decoder.decodePath(query.name().getBytes(Charset.forName(query.encoding())),
                    0, new byte[0], ZipLegacyMetadataDecoder.HeaderSource.CENTRAL_DIRECTORY, 20, query.creator() << 8 | 20, 0));
            if (round == 0) expected.addAll(actual);
            else assertEquals(expected, actual);
        }
    }

    /// An invalid field outside the statistical sample still vetoes an otherwise valid common encoding.
    @Test
    public void unsampledFieldStillRequiresStrictDecoding() throws IOException {
        var central = new ByteArrayOutputStream();
        central.write(centralBytes(new Sample("日本語😀", "UTF-8", null, true, 3)));
        long greatestHash = 0;
        for (int index = 0; index < ZipNameEncodingInference.MAX_SAMPLES; index++) {
            String name = "日本語😀-" + index + ".txt";
            long hash = sampleHash(name.getBytes(StandardCharsets.UTF_8));
            if (Long.compareUnsigned(hash, greatestHash) > 0) greatestHash = hash;
            central.write(centralBytes(new Sample(name, "UTF-8", null, false, 3)));
        }
        var fields = new HashMap<Integer, String>();
        assertEquals(StandardCharsets.UTF_8, ZipNameEncodingInference.select(
                ByteBuffer.wrap(central.toByteArray()), fields));
        assertEquals(ZipNameEncodingInference.MAX_SAMPLES, fields.size());

        // The additional field sorts after every retained sample, so it supplies no statistical evidence.
        var charset = Charset.forName("windows-1252");
        String rejected = "";
        for (int index = 0; index < 100000; index++) {
            String name = "ÿ-" + index + ".txt";
            if (Long.compareUnsigned(sampleHash(name.getBytes(charset)), greatestHash) > 0) {
                rejected = name;
                break;
            }
        }
        assertFalse(rejected.isEmpty(), "A deterministically excluded field is required");
        central.write(centralBytes(new Sample(rejected, charset.name(), null, false, 3)));
        assertEquals(ZipAutoMetadataDecoder.CP437, ZipNameEncodingInference.select(
                ByteBuffer.wrap(central.toByteArray()), fields));
        assertTrue(fields.isEmpty());
    }

    /// Computes the unsigned sample-order hash for independently constructed raw names.
    private static long sampleHash(byte[] bytes) {
        long hash = 0xcbf29ce484222325L;
        for (byte value : bytes) hash = (hash ^ (value & 255)) * 0x100000001b3L;
        return hash;
    }

    /// Frozen archive choices are validated against every byte of long fields.
    @Test
    public void longFieldsVetoInvalidArchiveChoices() throws IOException {
        var central = new ByteArrayOutputStream();
        central.write(centralBytes(new Sample("日本語😀.txt", "UTF-8", null, true, 0)));
        central.write(centralBytes(new Sample("é.txt", "UTF-8", "é", false, 0)));
        String longName = "a".repeat(5000) + "ÿ.txt";
        central.write(centralBytes(new Sample(longName, "windows-1252", null, false, 0)));
        var decodedFields = new HashMap<Integer, String>();
        decodedFields.put(-1, "stale");
        var prepared = ArchiveMetadataDecoder.forCharset(ZipNameEncodingInference.select(
                ByteBuffer.wrap(central.toByteArray()), decodedFields));
        assertTrue(decodedFields.isEmpty());
        byte[] raw = longName.getBytes(Charset.forName("windows-1252"));
        assertEquals(new String(raw, Charset.forName("IBM437")), prepared.decode(raw));
    }

    /// Transfers validated text once by absolute field offset without retaining borrowed storage.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void preparedTextIsTransferredToIndex(boolean direct) throws IOException {
        byte[] first = centralBytes(new Sample("é.txt", "windows-1252", "é", false, 0));
        byte[] anchor = centralBytes(new Sample("€résumé.txt", "windows-1252", null, true, 0));
        int prefix = 7;
        var storage = direct ? ByteBuffer.allocateDirect(prefix + first.length + anchor.length)
                : ByteBuffer.allocate(prefix + first.length + anchor.length);
        storage.position(prefix).put(first).put(anchor).flip().position(prefix);
        var central = storage.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
        central.mark();
        var prepared = ZipEntryNameDecoder.forCentralDirectory(ZipAutoMetadataDecoder.DEFAULT, central);
        assertEquals(prefix, central.position());
        assertEquals(storage.limit(), central.limit());
        assertEquals(ByteOrder.BIG_ENDIAN, central.order());
        central.reset();
        assertEquals(1, prepared.decodedFields().size());
        int nameOffset = prefix + 46;
        int commentOffset = nameOffset + "é.txt".getBytes(Charset.forName("windows-1252")).length;
        @Nullable String name = prepared.decodedFields().get(nameOffset);
        @Nullable String comment = prepared.decodedFields().get(commentOffset);
        assertEquals("é.txt", name);
        assertNull(comment);
        storage.clear();
        while (storage.hasRemaining()) storage.put((byte) 0);
        assertSame(name, prepared.take(nameOffset));
        assertSame(comment, prepared.take(commentOffset));
        assertNull(prepared.take(nameOffset));
        assertNull(prepared.take(commentOffset));
        assertTrue(prepared.decodedFields().isEmpty());
        assertEquals("é.txt", prepared.decoder().decodePath("é.txt".getBytes(Charset.forName("windows-1252")),
                0, new byte[0]));
    }

    /// Frozen strategies cannot re-detect an inconsistent field supplied after preparation.
    @Test
    public void frozenChoiceFailsStrictly() throws IOException {
        var central = centralBytes(new Sample("日本語😀.txt", "UTF-8", null, true, 0));
        var decoder = ZipEntryNameDecoder.forCentralDirectory(ZipAutoMetadataDecoder.DEFAULT,
                ByteBuffer.wrap(central)).decoder();
        assertThrows(java.nio.charset.CharacterCodingException.class,
                () -> decoder.decodePath(new byte[]{(byte) 0xFF}, 0, new byte[0]));
        assertEquals("é.txt", decoder.decodePath("é.txt".getBytes(StandardCharsets.UTF_8), 0, new byte[0]));
    }

    /// Frozen Unicode and legacy choices preserve ASCII and do not bypass explicit Unicode declarations.
    @ParameterizedTest
    @CsvSource({"UTF-8, 日本語😀.txt", "windows-1252, €résumé.txt", "IBM437, München.txt"})
    public void frozenAsciiPreservesDeclarationPriority(String encoding, String name) throws IOException {
        var central = centralBytes(new Sample(name, encoding, null, true, 0));
        var decoder = ZipEntryNameDecoder.forCentralDirectory(ZipAutoMetadataDecoder.DEFAULT,
                ByteBuffer.wrap(central)).decoder();
        byte[] raw = new byte[128];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) i;
        assertEquals(new String(raw, StandardCharsets.US_ASCII), decoder.decodePath(raw, 0, new byte[0]));
        assertEquals("", decoder.decodePath(new byte[0], 0, new byte[0]));
        byte[] replacement = "??.txt".getBytes(StandardCharsets.US_ASCII);
        byte[] extra = unicodeExtra(0x7075, "中文.txt", StandardCharsets.US_ASCII);
        assertEquals("中文.txt", decoder.decodePath(replacement, 0, extra));
        extra[extra.length - 1] = (byte) 0xff;
        assertThrows(IOException.class, () -> decoder.decodePath(replacement, 0, extra));
        assertThrows(IOException.class,
                () -> decoder.decodePath(new byte[]{(byte) 0xff}, ZipEntryNameDecoder.UTF_8_FLAG, new byte[0]));
    }

    /// Comments do not influence the shared name encoding.
    @Test
    public void commentsDoNotSupplyNameEvidence() throws IOException {
        for (var sample : List.of(
                new Sample("é", "IBM437", "Comentario: Café ñoño ▓▒░", false, 0),
                new Sample("é", "windows-1252", "Ceci est un résumé détaillé de l'installation du logiciel.", false, 0))) {
            var prepared = ArchiveMetadataDecoder.forCharset(ZipNameEncodingInference.select(
                    ByteBuffer.wrap(centralBytes(sample)), new java.util.HashMap<>()));
            byte[] rawName = sample.name().getBytes(Charset.forName(sample.encoding()));
            assertEquals(new String(rawName, Charset.forName("IBM437")), prepared.decode(rawName));
        }
    }

    /// Neither incompatible comment bytes nor their Unicode counterparts constrain the name encoding.
    @Test
    public void ignoresConflictingCommentEvidence(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("comment-conflict.zip");
        Files.write(file, archive(
                new Sample("€résumé.txt", "windows-1252", null, true, 0),
                new Sample("é.txt", "windows-1252", null, false, 0),
                new Sample("plain.txt", "IBM437", "ü", true, 0)));
        try (var fs = ZipArkivoFileSystem.open(file)) {
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath("é.txt")));
            assertArrayEquals(new byte[]{(byte) 0x81},
                    Files.readAttributes(fs.getPath("plain.txt"), ZipArkivoEntryAttributes.class).rawComment());
        }
    }

    /// Controls remain lossless metadata in both streaming inference and the frozen archive strategy.
    @Test
    public void controlCharactersDoNotInvalidateUtf8(@TempDir Path directory) throws IOException {
        var samples = new Sample[]{
                new Sample("日本語\n.txt", "UTF-8", "日本語\t\n", false, 3),
                new Sample("😀.txt", "UTF-8", null, false, 3),
                new Sample("a\n.txt", "UTF-8", null, false, 3)};
        byte[] bytes = archive(samples);
        Path file = directory.resolve("controls.zip");
        Files.write(file, bytes);
        try (var fs = ZipArkivoFileSystem.open(file)) {
            for (var sample : samples) assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath(sample.name())));
        }
        for (var sample : samples) assertEquals(sample.name(), ZipAutoMetadataDecoder.DEFAULT.decode(
                sample.name().getBytes(StandardCharsets.UTF_8)));
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(bytes))) {
            for (var sample : samples) {
                assertTrue(reader.next());
                assertEquals(sample.name(), reader.readAttributes().path());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
                }
            }
            assertFalse(reader.next());
        }
    }

    /// An authoritative counterpart containing controls still constrains undeclared names losslessly.
    @Test
    public void unicodeCounterpartWithControls() throws IOException {
        var central = new ByteArrayOutputStream();
        central.write(centralBytes(new Sample("日本語😀\u0001.txt", "UTF-8", null, true, 3)));
        central.write(centralBytes(new Sample("é\u0085.txt", "UTF-8", null, false, 3)));
        var fields = new HashMap<Integer, String>();
        var prepared = ArchiveMetadataDecoder.forCharset(ZipNameEncodingInference.select(
                ByteBuffer.wrap(central.toByteArray()), fields));
        assertEquals(List.of("é\u0085.txt"), List.copyOf(fields.values()));
        assertEquals("é\u0085.txt", prepared.decode("é\u0085.txt".getBytes(StandardCharsets.UTF_8)));
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
        var decoder = ZipEntryNameDecoder.forCentralDirectory(ZipAutoMetadataDecoder.DEFAULT, buffer).decoder();
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
        byte[] withoutExtra = archive(new Sample("é.txt", "windows-1252", null, false, 0),
                new Sample("€résumé.txt", "windows-1252", null, false, 0));
        Path other = directory.resolve("no-extra.zip");
        Files.write(other, withoutExtra);
        try (var fs = ZipArkivoFileSystem.open(file); var reference = ZipArkivoFileSystem.open(other);
             var actual = Files.list(fs.getPath("/")); var expected = Files.list(reference.getPath("/"))) {
            assertEquals(expected.map(Object::toString).sorted().toList(), actual.map(Object::toString).sorted().toList());
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
        var prepared = ZipEntryNameDecoder.forCentralDirectory(configured, malformed);
        var decoder = prepared.decoder();
        assertTrue(prepared.decodedFields().isEmpty());
        assertEquals(0, calls.get());
        assertEquals("custom", decoder.decodePath(new byte[]{(byte) 0xff}, 0, new byte[0]));
        assertEquals(1, calls.get());
        assertEquals(0, malformed.position());
    }

    /// Wrapping the default decoder delegates individual fields without enabling archive preparation.
    @Test
    public void wrappedDefaultRemainsSingleField() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        ArchiveMetadataDecoder wrapper = bytes -> {
            calls.incrementAndGet();
            return ZipArchiveOptions.DEFAULT_LEGACY_METADATA_DECODER.decode(bytes);
        };
        ByteBuffer malformed = ByteBuffer.wrap(new byte[]{1});
        var prepared = ZipEntryNameDecoder.forCentralDirectory(wrapper, malformed);
        assertTrue(prepared.decodedFields().isEmpty());
        assertEquals(0, calls.get());
        byte[] raw = "日本語😀.txt".getBytes(StandardCharsets.UTF_8);
        assertEquals("日本語😀.txt", prepared.decoder().decodePath(raw, 0, new byte[0]));
        assertEquals(1, calls.get());
        assertEquals(0, malformed.position());
    }

    /// Independent archive preparations and frozen field decoders share no mutable charset state.
    @Test
    public void concurrentArchiveChoicesRemainIndependent() throws IOException {
        byte[] unicode = centralBytes(new Sample("日本語😀.txt", "UTF-8", null, true, 0));
        byte[] legacy = centralBytes(new Sample("€résumé.txt", "windows-1252", null, true, 0));
        var utf8 = ZipEntryNameDecoder.forCentralDirectory(ZipAutoMetadataDecoder.DEFAULT, ByteBuffer.wrap(unicode));
        var windows = ZipEntryNameDecoder.forCentralDirectory(ZipAutoMetadataDecoder.DEFAULT, ByteBuffer.wrap(legacy));
        byte[] utf8Name = "é.txt".getBytes(StandardCharsets.UTF_8);
        byte[] windowsName = "é.txt".getBytes(Charset.forName("windows-1252"));
        IntStream.range(0, 64).parallel().forEach(index -> {
            try {
                byte[] central = index % 2 == 0 ? unicode : legacy;
                byte[] name = index % 2 == 0 ? utf8Name : windowsName;
                var prepared = ZipEntryNameDecoder.forCentralDirectory(ZipAutoMetadataDecoder.DEFAULT,
                        ByteBuffer.wrap(central));
                assertEquals("é.txt", prepared.decoder().decodePath(name, 0, new byte[0]));
                assertEquals("é.txt", utf8.decoder().decodePath(utf8Name, 0, new byte[0]));
                assertEquals("é.txt", windows.decoder().decodePath(windowsName, 0, new byte[0]));
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
        });
    }

    /// Leaves strict Unicode validation, unsafe-path rejection, and duplicate checks in the regular reader.
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
        Files.write(file, archive(new Sample("软件安装说明.txt", "GB18030", null, false, 0),
                new Sample("软件安装说明.txt", "GB18030", null, false, 0)));
        assertThrows(IOException.class, () -> {
            try (var fs = ZipArkivoFileSystem.open(file); var stream = Files.newDirectoryStream(fs.getPath("/"))) {
                stream.iterator().hasNext();
            }
        });
    }

    /// Both reader entry points reject malformed authoritative Unicode without invoking a custom legacy decoder.
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    public void unicodeMetadataPrecedesCustomDecoders(boolean utf8, boolean zipSpecific,
                                                     @TempDir Path directory) throws IOException {
        ArchiveMetadataDecoder decoder = zipSpecific
                ? (ZipLegacyMetadataDecoder) context -> { throw new AssertionError("Legacy decoder must not be invoked"); }
                : bytes -> { throw new AssertionError("Legacy decoder must not be invoked"); };
        var options = ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(decoder);
        byte[] valid = archive(new Sample("legacy.txt", "windows-1252", null, true, 0));
        int centralOffset = ByteArrayAccess.readIntLittleEndian(valid, valid.length - 6);
        short flags = (short) (utf8 ? 0x800 : 0);
        ByteArrayAccess.writeShortLittleEndian(valid, 6, flags);
        ByteArrayAccess.writeShortLittleEndian(valid, centralOffset + 8, flags);
        int localExtra = 30 + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(valid, 26));
        int centralExtra = centralOffset + 46
                + Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(valid, centralOffset + 28));
        assertEquals(0x7075, Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(valid, localExtra)));
        assertEquals(0x7075, Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(valid, centralExtra)));
        Path file = directory.resolve("unicode.zip");
        Files.write(file, valid);
        try (var fs = ZipArkivoFileSystem.open(file, options)) {
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath("legacy.txt")));
        }
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(valid), options)) {
            assertTrue(reader.next());
            assertEquals("legacy.txt", reader.readAttributes().path());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
            }
            assertFalse(reader.next());
        }

        byte[] malformed = valid.clone();
        // Keep the ASCII raw name, field length, version, and name CRC valid; corrupt only Unicode text.
        malformed[localExtra + 9] = (byte) 0xff;
        malformed[centralExtra + 9] = (byte) 0xff;
        Files.write(file, malformed);
        IOException indexedFailure = assertThrows(IOException.class, () -> {
            try (var fs = ZipArkivoFileSystem.open(file, options);
                 var entries = Files.newDirectoryStream(fs.getPath("/"))) {
                entries.iterator().hasNext();
            }
        });
        assertInstanceOf(CharacterCodingException.class, indexedFailure.getCause());
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(malformed), options)) {
            IOException streamingFailure = assertThrows(IOException.class, reader::next);
            assertInstanceOf(CharacterCodingException.class, streamingFailure.getCause());
        }
    }

    /// Constructs mixed-encoding archives from JDK-generated records without storing binary fixtures.
    static byte[] archive(Sample... samples) throws IOException {
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
    record Sample(String name, String encoding, @Nullable String comment, boolean unicode, int creator) {
    }
}
