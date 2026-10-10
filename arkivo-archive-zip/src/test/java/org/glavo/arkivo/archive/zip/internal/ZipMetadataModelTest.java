// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
import org.glavo.arkivo.archive.zip.ZipLegacyMetadataDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.BufferedInputStream;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Objects;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/// Tests the learned decoder's structural contracts with synthetic coefficients, independently of language accuracy.
@NotNullByDefault
public final class ZipMetadataModelTest {
    /// Hashing and repetition caps are deterministic across buffer storage types and positions.
    @Test
    void featuresRespectFieldBoundariesAndBufferState() {
        byte[] raw = "é漢字.txt".getBytes(StandardCharsets.UTF_8);
        var expected = ZipMetadataModel.extract(ByteBuffer.wrap(raw));
        for (boolean direct : new boolean[]{false, true}) {
            ByteBuffer buffer = direct ? ByteBuffer.allocateDirect(raw.length + 4) : ByteBuffer.allocate(raw.length + 4);
            buffer.position(2).put(raw).flip().position(2);
            buffer = buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
            buffer.mark();
            var actual = ZipMetadataModel.extract(buffer);
            assertArrayEquals(expected.indices(), actual.indices());
            assertEquals(expected.fingerprint(), actual.fingerprint());
            assertEquals(2, buffer.position());
            assertEquals(raw.length + 2, buffer.limit());
            assertEquals(ByteOrder.LITTLE_ENDIAN, buffer.order());
            buffer.reset();
        }
        assertEquals(0, ZipMetadataModel.extract(ByteBuffer.wrap("name001.txt".getBytes(StandardCharsets.US_ASCII))).indices().length);
        int[] repeated = ZipMetadataModel.extract(ByteBuffer.wrap("é".repeat(100).getBytes(StandardCharsets.UTF_8))).indices();
        for (int index = 2; index < repeated.length; index++) assertFalse(repeated[index] == repeated[index - 2]);
        assertNotEquals(ZipMetadataModel.extract(ByteBuffer.wrap(new byte[]{(byte) 0xe9})).fingerprint(),
                ZipMetadataModel.extract(ByteBuffer.wrap(new byte[]{(byte) 0xe9, (byte) 0xe9})).fingerprint());
    }

    /// Numbered filename variants cannot multiply otherwise identical non-ASCII evidence.
    @Test
    void fingerprintsIgnoreAsciiSuffixVariations() {
        var first = ZipMetadataModel.extract(ByteBuffer.wrap("照片001.jpg".getBytes(StandardCharsets.UTF_8)));
        var second = ZipMetadataModel.extract(ByteBuffer.wrap("照片999.txt".getBytes(StandardCharsets.UTF_8)));
        assertEquals(first.fingerprint(), second.fingerprint());
    }

    /// A high-scoring but invalid encoding is rejected even when the invalid bytes are outside sampled windows.
    @Test
    void strictValidationCoversTheWholeField() {
        var model = biased("UTF-8", "IBM437", 0);
        byte[] bytes = new byte[2048];
        Arrays.fill(bytes, (byte) 'x');
        bytes[0] = (byte) 0xc3;
        bytes[1] = (byte) 0xa9;
        bytes[1024] = (byte) 0xff;
        assertEquals(new String(bytes, Charset.forName("IBM437")), model.decode(ByteBuffer.wrap(bytes), null).text());
    }

    /// Equivalent outputs do not cause false rejection despite a large margin between distinct outputs.
    @Test
    void equivalentCharsetsAreOneOutput() {
        var model = biased("IBM437", "IBM850", 2);
        byte[] raw = {(byte) 0x82};
        var decision = model.decode(ByteBuffer.wrap(raw), null);
        assertEquals("é", decision.text());
        assertFalse(decision.fallback());
        long mask = new ZipMetadataModel.Workspace(raw.length).matching(ByteBuffer.wrap(raw), "é");
        assertNotEquals(0, mask & 1L << ZipMetadataModel.CP437);
        assertNotEquals(0, mask & 1L << ZipMetadataModel.ENCODINGS.indexOf("IBM850"));
    }

    /// Unmarked UTF-8 validity alone cannot override another candidate's calibrated score.
    @Test
    void utf8IsACandidateNotAnOverride() {
        byte[] raw = "é".getBytes(StandardCharsets.UTF_8);
        var model = biased("windows-1252", "UTF-8", 0);
        assertEquals("Ã©", model.decode(ByteBuffer.wrap(raw), null).text());
        assertEquals("é", model.withParameters(new ZipMetadataModel.Parameters(1, 0, 0)).decode(ByteBuffer.wrap(raw), null).text());
    }

    /// Ties between different texts fall back without replacement decoding or probabilistic claims.
    @Test
    void tiesAndEmptyInput() {
        var model = new ZipMetadataModel(new short[ZipMetadataModel.FEATURES * ZipMetadataModel.ENCODINGS.size()],
                new float[ZipMetadataModel.ENCODINGS.size()], new ZipMetadataModel.Parameters(0, 0, 0));
        assertTrue(model.decode(ByteBuffer.wrap(new byte[]{(byte) 0xe9}), null).fallback());
        assertEquals("", model.decode(ByteBuffer.allocate(0), null).text());
        assertTrue(model.weightBytes() <= 4 * 1024 * 1024);
    }

    /// Canonical text round-trips exactly and rejects unknown, reordered, duplicate, and non-finite model data.
    @Test
    void textModelValidation() throws IOException {
        var model = biased("UTF-8", "IBM437", 0);
        StringWriter first = new StringWriter();
        model.write(first);
        var restored = ZipMetadataModel.read(new StringReader(first.toString()));
        StringWriter second = new StringWriter();
        restored.write(second);
        assertEquals(first.toString(), second.toString());
        assertThrows(IOException.class, () -> ZipMetadataModel.read(new StringReader("unknown\n")));
        assertThrows(IOException.class, () -> ZipMetadataModel.read(new StringReader(first + "0\t1\n0\t2\n")));
        assertThrows(IOException.class, () -> ZipMetadataModel.read(new StringReader(first.toString().replace("UTF-8\t0.0", "UTF-8\tNaN"))));
        assertThrows(IllegalArgumentException.class, () -> new ZipMetadataModel.Parameters(0, -1, 0));
    }

    /// Shared model invocations have no call-history or concurrent decoding state.
    @Test
    void concurrentIndependentInvocations() {
        var decoder = new ZipLearnedMetadataDecoder(biased("UTF-8", "IBM437", 0));
        IntStream.range(0, 500).parallel().forEach(index -> {
            String expected = index % 2 == 0 ? "世界" : "résumé";
            assertEquals(expected, decoder.decode(ByteBuffer.wrap(expected.getBytes(StandardCharsets.UTF_8))));
        });
    }

    /// Full-directory sampling and duplicate handling are independent of record order and input lifetime.
    @ParameterizedTest
    @ValueSource(ints = {1, 256, 600, 4200})
    void deterministicSamplingAndOwnership(int count) throws IOException {
        var model = biased("IBM437", "windows-1252", 0).withParameters(new ZipMetadataModel.Parameters(0, 0, 4));
        List<ZipAutoMetadataDecoderTest.Sample> samples = new ArrayList<>();
        for (int index = 0; index < count; index++) samples.add(new ZipAutoMetadataDecoderTest.Sample("é" + index + ".txt", "windows-1252", null, true, 0));
        samples.add(new ZipAutoMetadataDecoderTest.Sample("état.txt", "windows-1252", null, true, 0));
        samples.add(new ZipAutoMetadataDecoderTest.Sample("à.txt", "windows-1252", null, false, 0));
        byte[] archive = ZipAutoMetadataDecoderTest.archive(samples.toArray(ZipAutoMetadataDecoderTest.Sample[]::new));
        ByteBuffer buffer = central(archive);
        buffer.mark();
        var prepared = new ZipLearnedMetadataDecoder(model).prepare(buffer);
        String expected = prepared.decode(context("à.txt".getBytes(Charset.forName("windows-1252")), 0, false));
        assertEquals(0, buffer.position());
        buffer.reset();
        Arrays.fill(archive, (byte) 0);
        assertEquals(expected, prepared.decode(context("à.txt".getBytes(Charset.forName("windows-1252")), 0, false)));
        Collections.shuffle(samples, new Random(42));
        var reordered = new ZipLearnedMetadataDecoder(model).prepare(central(ZipAutoMetadataDecoderTest.archive(samples.toArray(ZipAutoMetadataDecoderTest.Sample[]::new))));
        assertEquals(expected, reordered.decode(context("à.txt".getBytes(Charset.forName("windows-1252")), 0, false)));
    }

    /// Prepared decoders remain scoped to metadata kinds and creators, while streaming calls remain single-field.
    @Test
    void evidenceScopeAndPublicReadingPaths(@TempDir Path directory) throws IOException {
        var model = biased("IBM437", "windows-1252", 0).withParameters(new ZipMetadataModel.Parameters(0, 0, 4));
        var decoder = new ZipLearnedMetadataDecoder(model);
        byte[] archive = ZipAutoMetadataDecoderTest.archive(
                new ZipAutoMetadataDecoderTest.Sample("état.txt", "windows-1252", null, true, 0),
                new ZipAutoMetadataDecoderTest.Sample("à.txt", "windows-1252", null, false, 0));
        byte[] raw = "à.txt".getBytes(Charset.forName("windows-1252"));
        var prepared = decoder.prepare(central(archive));
        String original = decoder.decode(ByteBuffer.wrap(raw));
        assertEquals("à.txt", prepared.decode(context(raw, 0, false)));
        assertEquals(original, prepared.decode(context(raw, 3, false)));
        assertEquals(original, prepared.decode(context(raw, 0, true)));
        var options = ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(decoder);
        Path file = directory.resolve("prepared.zip");
        Files.write(file, archive);
        try (var fs = ZipArkivoFileSystem.open(file, options)) {
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath("à.txt")));
        }
        try (var reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive), options)) {
            assertTrue(reader.next());
            assertEquals("état.txt", reader.readAttributes().path());
            assertTrue(reader.next());
            assertEquals(original, reader.readAttributes().path());
        }
    }

    /// Shared resources load once and malformed binary tables are rejected rather than partially accepted.
    @Test
    void binaryModelIsSharedAndRejectsMalformedResources() throws Exception {
        var model = ZipMetadataModel.bundled();
        assertSame(model, ZipMetadataModel.bundled());
        assertEquals(3_932_280, model.weightBytes());
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var writer = new java.io.OutputStreamWriter(new java.security.DigestOutputStream(
                java.io.OutputStream.nullOutputStream(), digest), StandardCharsets.UTF_8)) {
            model.write(writer);
        }
        var provenance = new java.util.Properties();
        try (var input = Objects.requireNonNull(ZipMetadataModel.class.getResourceAsStream("/META-INF/arkivo/metadata-model/training.properties"))) {
            provenance.load(input);
        }
        assertEquals(provenance.getProperty("modelSha256"), java.util.HexFormat.of().formatHex(digest.digest()));
        assertThrows(IOException.class, () -> ZipMetadataModel.readBinary(new ByteArrayInputStream(new byte[4])));
        try (var stream = Objects.requireNonNull(ZipMetadataModel.class.getResourceAsStream("metadata-model.bin"))) {
            byte[] prefix = stream.readNBytes(20);
            assertThrows(IOException.class, () -> ZipMetadataModel.readBinary(new ByteArrayInputStream(prefix)));
        }
        try (var stream = new BufferedInputStream(new SequenceInputStream(
                Objects.requireNonNull(ZipMetadataModel.class.getResourceAsStream("metadata-model.bin")),
                new ByteArrayInputStream(new byte[]{1})))) {
            assertThrows(IOException.class, () -> ZipMetadataModel.readBinary(stream));
        }
    }

    /// Sampling bounds and long-field exclusion do not depend on record order or the caller's byte order.
    @Test
    void byteBudgetAndOversizedFields() throws IOException {
        var decoder = new ZipLearnedMetadataDecoder(biased("UTF-8", "IBM437", 0));
        List<byte[]> fields = new ArrayList<>();
        for (int index = 0; index < 256; index++) fields.add(("é" + index + "x".repeat(3500)).getBytes(StandardCharsets.UTF_8));
        byte[] target = "é.txt".getBytes(StandardCharsets.UTF_8);
        ByteBuffer records = records(fields);
        var first = decoder.prepare(records);
        assertEquals(0, records.position());
        assertEquals(ByteOrder.LITTLE_ENDIAN, records.order());
        Collections.reverse(fields);
        var reverse = decoder.prepare(records(fields));
        assertEquals(first.decode(context(target, 0, false)), reverse.decode(context(target, 0, false)));
        var longOnly = decoder.prepare(records(List.of(("é".repeat(3000)).getBytes(StandardCharsets.UTF_8))));
        assertEquals(decoder.decode(ByteBuffer.wrap(target)), longOnly.decode(context(target, 0, false)));
        assertThrows(IOException.class, () -> decoder.prepare(ByteBuffer.allocate(45)));
        records.putShort(28, (short) 65535);
        records.limit(46);
        assertThrows(IOException.class, () -> decoder.prepare(records));
    }

    /// Authoritative declarations and unsafe paths are never retried under another encoding.
    @Test
    void malformedUnicodeAndTraversalRemainErrors(@TempDir Path directory) throws IOException {
        var decoder = new ZipEntryNameDecoder(new ZipLearnedMetadataDecoder(biased("IBM437", "UTF-8", 0)));
        byte[] raw = {(byte) 0xff};
        assertThrows(IOException.class, () -> decoder.decodePath(raw, ZipEntryNameDecoder.UTF_8_FLAG, new byte[0]));
        var crc = new java.util.zip.CRC32();
        crc.update(raw);
        byte[] extra = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0x7075)
                .putShort((short) 6).put((byte) 1).putInt((int) crc.getValue()).put((byte) 0xff).array();
        assertThrows(IOException.class, () -> decoder.decodePath(raw, 0, extra));
        Path file = directory.resolve("unsafe.zip");
        Files.write(file, ZipAutoMetadataDecoderTest.archive(new ZipAutoMetadataDecoderTest.Sample("../é.txt", "IBM437", null, false, 0)));
        var options = ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(new ZipLearnedMetadataDecoder());
        assertThrows(IOException.class, () -> {
            try (var fs = ZipArkivoFileSystem.open(file, options); var paths = Files.list(fs.getPath("/"))) { paths.count(); }
        });
    }

    /// Prepared evidence survives update snapshots without changing original record bytes or comments.
    @Test
    void updatesRetainTheFrozenDecoder(@TempDir Path directory) throws IOException {
        var decoder = new ZipLearnedMetadataDecoder(biased("IBM437", "windows-1252", 0)
                .withParameters(new ZipMetadataModel.Parameters(0, 0, 4)));
        var options = ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(decoder);
        Path file = directory.resolve("update.zip");
        Files.write(file, ZipAutoMetadataDecoderTest.archive(
                new ZipAutoMetadataDecoderTest.Sample("état.txt", "windows-1252", "état", true, 0),
                new ZipAutoMetadataDecoderTest.Sample("à.txt", "windows-1252", "à", false, 0)));
        byte[] original;
        try (var fs = ZipArkivoFileSystem.update(file, ZipArchiveOptions.UPDATE_DEFAULTS.withLegacyMetadataDecoder(decoder))) {
            var attributes = Files.readAttributes(fs.getPath("à.txt"), ZipArkivoEntryAttributes.class);
            original = attributes.rawPath();
            assertEquals("à", attributes.comment());
            Files.write(fs.getPath("added.txt"), new byte[]{4});
        }
        try (var fs = ZipArkivoFileSystem.open(file, options)) {
            var attributes = Files.readAttributes(fs.getPath("à.txt"), ZipArkivoEntryAttributes.class);
            assertArrayEquals(original, attributes.rawPath());
            assertEquals("à", attributes.comment());
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath("à.txt")));
        }
    }

    /// Conflicting Unicode counterparts constrain only their own entries, not every occurrence of the raw prefix.
    @Test
    void conflictingUnicodePrefixesDoNotMergeDirectories(@TempDir Path directory) throws IOException {
        var decoder = new ZipLearnedMetadataDecoder(biased("IBM437", "windows-1252", 0)
                .withParameters(new ZipMetadataModel.Parameters(0, 0, 4)));
        var first = new ZipAutoMetadataDecoderTest.Sample("é/état.txt", "windows-1252", null, true, 0);
        var second = new ZipAutoMetadataDecoderTest.Sample("Θ/ΘETA.txt", "IBM437", null, true, 0);
        byte[] archive = ZipAutoMetadataDecoderTest.archive(first, second);
        Path file = directory.resolve("mixed.zip");
        Files.write(file, archive);
        var options = ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(decoder);
        try (var fs = ZipArkivoFileSystem.open(file, options)) {
            assertTrue(Files.isDirectory(fs.getPath("é")));
            assertTrue(Files.isDirectory(fs.getPath("Θ")));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath("é/état.txt")));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(fs.getPath("Θ/ΘETA.txt")));
        }
        byte[] target = "é/à.txt".getBytes(Charset.forName("windows-1252"));
        String result = decoder.prepare(central(archive)).decode(context(target, 0, false));
        var reversed = decoder.prepare(central(ZipAutoMetadataDecoderTest.archive(second, first)));
        assertEquals(result, reversed.decode(context(target, 0, false)));
    }

    /// Unicode evidence beyond a large ASCII prefix is still considered before constructing the index.
    @Test
    void evidenceAfterTheFirst4096RecordsIsNotIgnored() throws IOException {
        var decoder = new ZipLearnedMetadataDecoder(biased("IBM437", "windows-1252", 0)
                .withParameters(new ZipMetadataModel.Parameters(0, 0, 4)));
        ByteBuffer ascii = records(IntStream.range(0, 4200)
                .mapToObj(index -> ("file" + index + ".txt").getBytes(StandardCharsets.US_ASCII)).toList());
        ByteBuffer anchor = central(ZipAutoMetadataDecoderTest.archive(
                new ZipAutoMetadataDecoderTest.Sample("état.txt", "windows-1252", null, true, 0)));
        ByteBuffer directory = ByteBuffer.allocate(ascii.remaining() + anchor.remaining()).put(ascii).put(anchor).flip();
        byte[] target = "à.txt".getBytes(Charset.forName("windows-1252"));
        assertNotEquals("à.txt", decoder.decode(ByteBuffer.wrap(target)));
        assertEquals("à.txt", decoder.prepare(directory).decode(context(target, 0, false)));
    }

    /// Duplicate raw fields with conflicting Unicode counterparts contribute symmetric, bounded evidence.
    @Test
    void conflictingCounterpartsAreOrderIndependent() throws IOException {
        var decoder = new ZipLearnedMetadataDecoder(biased("IBM437", "windows-1252", 0)
                .withParameters(new ZipMetadataModel.Parameters(0, 0, 4)));
        var western = new ZipAutoMetadataDecoderTest.Sample("é.txt", "windows-1252", null, true, 0);
        var oem = new ZipAutoMetadataDecoderTest.Sample("Θ.txt", "IBM437", null, true, 0);
        byte[] target = "à.txt".getBytes(Charset.forName("windows-1252"));
        var first = decoder.prepare(central(ZipAutoMetadataDecoderTest.archive(western, oem)));
        var second = decoder.prepare(central(ZipAutoMetadataDecoderTest.archive(oem, western)));
        assertEquals(first.decode(context(target, 0, false)), second.decode(context(target, 0, false)));
    }

    /// Stable UTF-8 prefix evidence preserves directory interpretation without concatenating decoded fragments.
    @Test
    void sharedDirectoryPrefixes() throws IOException {
        var decoder = new ZipLearnedMetadataDecoder(biased("UTF-8", "IBM437", 0)
                .withParameters(new ZipMetadataModel.Parameters(0, 0, 4)));
        List<String> names = List.of("資料/", "資料/一覧表.txt", "資料/漢字.txt", "資料/😀001.txt");
        var prepared = decoder.prepare(records(names.stream().map(name -> name.getBytes(StandardCharsets.UTF_8)).toList()));
        for (String name : names) assertEquals(name, prepared.decode(context(name.getBytes(StandardCharsets.UTF_8), 0, false)));
    }

    /// Builds central records without local headers because evidence preparation must not read entry content.
    private static ByteBuffer records(List<byte[]> fields) {
        ByteBuffer result = ByteBuffer.allocate(fields.stream().mapToInt(field -> 46 + field.length).sum()).order(ByteOrder.LITTLE_ENDIAN);
        for (byte[] field : fields) {
            int offset = result.position();
            result.putInt(ZipConstants.CENTRAL_DIRECTORY_HEADER_SIGNATURE);
            result.putShort(offset + 4, (short) 20);
            result.putShort(offset + 28, (short) field.length);
            result.position(offset + 46).put(field);
        }
        return result.flip();
    }

    /// Builds fixed coefficients for contract tests without relying on a trained language model.
    private static ZipMetadataModel biased(String first, String second, float margin) {
        float[] biases = new float[ZipMetadataModel.ENCODINGS.size()];
        Arrays.fill(biases, -8);
        biases[ZipMetadataModel.ENCODINGS.indexOf(first)] = 0;
        biases[ZipMetadataModel.ENCODINGS.indexOf(second)] = -0.125f;
        return new ZipMetadataModel(new short[biases.length * ZipMetadataModel.FEATURES], biases, new ZipMetadataModel.Parameters(0, margin, 0));
    }

    /// Selects the central-directory bytes from the independently generated single-volume test archive.
    private static ByteBuffer central(byte[] archive) {
        ByteBuffer bytes = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        int end = archive.length - 22;
        return bytes.slice(bytes.getInt(end + 16), bytes.getInt(end + 12));
    }

    /// Supplies available creator and logical-field metadata without a Unicode declaration.
    private static ZipLegacyMetadataDecoder.Context context(byte[] bytes, int creator, boolean comment) {
        return new ZipLegacyMetadataDecoder.Context(ByteBuffer.wrap(bytes), comment ? ZipLegacyMetadataDecoder.MetadataKind.ENTRY_COMMENT
                : ZipLegacyMetadataDecoder.MetadataKind.ENTRY_NAME, ZipLegacyMetadataDecoder.HeaderSource.CENTRAL_DIRECTORY,
                0, 20, creator << 8 | 20, 0, ByteBuffer.allocate(0));
    }
}
