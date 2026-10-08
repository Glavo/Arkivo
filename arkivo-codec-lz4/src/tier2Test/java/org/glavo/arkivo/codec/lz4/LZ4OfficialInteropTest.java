// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import net.jpountz.xxhash.XXHashFactory;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Checks official LZ4 fixtures and frame boundaries, with optional bidirectional native CLI verification.
@NotNullByDefault
final class LZ4OfficialInteropTest {
    /// Isolated files used only by the explicitly configured command-line reference.
    @TempDir
    private Path temporaryDirectory;

    /// Checks the official skippable sample without consuming the following frame's magic.
    @ParameterizedTest
    @ValueSource(ints = {1, 7, 38})
    void readsOfficialSkippableFrame(int inputChunk) throws IOException {
        byte[] frame = Files.readAllBytes(resource("tests/goldenSamples/skip.bin"));
        assertEquals(38, frame.length);
        assertArrayEquals(new byte[0], decodeChunks(new LZ4Codec(), frame, inputChunk, 1));
    }

    /// Preserves linked history across empty raw blocks with every block/content checksum combination.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void emptyBlocksDoNotEndFramesOrDiscardHistory(int checksums) throws IOException {
        byte[] text = officialText();
        byte[] frame = linkedFrame(text, checksums);
        byte[] expected = new byte[text.length * 2 + 5];
        System.arraycopy(text, 0, expected, 0, text.length);
        System.arraycopy(text, 0, expected, text.length, text.length);
        Arrays.fill(expected, text.length * 2, expected.length, (byte) '!');
        LZ4Codec codec = new LZ4Codec().withMaximumOutputSize(expected.length);
        assertArrayEquals(expected, decodeChunks(codec, frame, 1, 7));
        assertArrayEquals(expected, decodeStream(codec, frame, expected.length));

        if ((checksums & 1) != 0) {
            // The first empty block has no payload, but still has its own checksum.
            frame[11] ^= 1;
            assertThrows(IOException.class, () -> decodeChunks(codec, frame, 1, 7));
            assertArrayEquals(expected, decodeChunks(codec.withVerifyChecksums(false), frame, 7, 11));
        }
    }

    /// Discards a partially read explicit content size before an empty frame with no content-size field.
    @Test
    void resetDoesNotRetainAnUnfinishedFrameSize() throws IOException {
        byte[] sized = bytes(new LZ4Codec().compress(ByteBuffer.wrap(new byte[9])));
        assertNotEquals(0, sized[4] & 8);
        byte[] empty = emptyFrame();
        try (var decoder = new LZ4Codec().newDecoder()) {
            for (int cut = 0; cut < sized.length; cut++) {
                @UnmodifiableView ByteBuffer source = ByteBuffer.wrap(sized, 0, cut).slice().asReadOnlyBuffer();
                assertEquals(CodecOutcome.NEEDS_INPUT, decoder.decode(source, ByteBuffer.allocate(10)));
                assertEquals(cut, source.position());
                decoder.reset();
                ByteBuffer target = ByteBuffer.allocateDirect(1);
                assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.wrap(empty), target));
                assertEquals(0, target.position());
                decoder.reset();
            }
        }
    }

    /// Returns the explicitly configured reference tool, skipping optional runs when it is unavailable.
    private static String officialExecutable() {
        @Nullable String configured = System.getenv("ARKIVO_LZ4_EXECUTABLE");
        boolean available = configured != null && Files.isRegularFile(Path.of(configured));
        if (Boolean.parseBoolean(System.getenv("ARKIVO_REQUIRE_LZ4"))) {
            assertTrue(available, "Set ARKIVO_LZ4_EXECUTABLE to the required official LZ4 CLI");
        }
        assumeTrue(available, "ARKIVO_LZ4_EXECUTABLE does not name an official LZ4 CLI");
        return Objects.requireNonNull(configured);
    }

    /// Enumerates all block sizes, block dependency modes, and checksum combinations.
    private static Stream<Arguments> frameConfigurations() {
        Stream.Builder<Arguments> configurations = Stream.builder();
        for (LZ4BlockSize blockSize : LZ4BlockSize.values()) {
            for (boolean independent : new boolean[]{false, true}) {
                for (int checksums = 0; checksums < 4; checksums++) {
                    configurations.add(Arguments.of(blockSize, independent, checksums));
                }
            }
        }
        return configurations.build();
    }

    /// Checks both directions against the official CLI with multiple blocks in each frame.
    @ParameterizedTest(name = "{0}, independent={1}, checksums={2}")
    @MethodSource("frameConfigurations")
    void officialFrameInteroperability(LZ4BlockSize blockSize, boolean independent, int checksums) throws IOException {
        String executable = officialExecutable();
        byte[] content = patternedContent(blockSize.byteSize() + 257);
        LZ4Codec codec = new LZ4Codec().withBlockSize(blockSize)
                .withIndependentBlocks(independent).withBlockChecksum((checksums & 1) != 0)
                .withContentChecksum((checksums & 2) != 0);
        List<String> options = new ArrayList<>();
        options.add("-B" + blockSize.descriptorCode());
        options.add(independent ? "-BI" : "-BD");
        options.add((checksums & 1) == 0 ? "-1" : "-9");
        if ((checksums & 1) != 0) options.add("-BX");
        if ((checksums & 2) == 0) options.add("--no-frame-crc");
        if (independent) options.add("--content-size");
        byte[] frame = checkBothDirections(executable, codec, content, options, null);
        int flags = Byte.toUnsignedInt(frame[4]);
        assertEquals(independent, (flags & 0x20) != 0);
        assertEquals((checksums & 1) != 0, (flags & 0x10) != 0);
        assertEquals((checksums & 2) != 0, (flags & 0x04) != 0);
        assertEquals(independent, (flags & 0x08) != 0);
        assertEquals(blockSize.descriptorCode(), (frame[5] >>> 4) & 7);
    }

    /// Checks raw dictionaries around the maximum match distance and retained-history size.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 1024, 65_535, 65_536, 65_537})
    void officialDictionaryInteroperability(int size) throws IOException {
        String executable = officialExecutable();
        byte[] dictionary = new byte[size];
        new Random(0x4c5a_34L + size).nextBytes(dictionary);
        Path dictionaryFile = temporaryDirectory.resolve("dictionary.bin");
        Files.write(dictionaryFile, dictionary);
        byte[] content = new byte[131_073];
        if (size > 0) {
            int retained = Math.min(size, 65_535);
            for (int i = 0; i < content.length; i++) {
                content[i] = dictionary[size - retained + i % retained];
            }
        }
        for (boolean independent : new boolean[]{false, true}) {
            LZ4Codec codec = new LZ4Codec().withBlockSize(LZ4BlockSize.KIB_64)
                    .withIndependentBlocks(independent).withDictionary(LZ4Dictionary.rawContent(dictionary))
                    .withBlockChecksum(true).withContentChecksum(true);
            checkBothDirections(executable, codec, content,
                    List.of("-B4", independent ? "-BI" : "-BD", "-9", "-BX", "--content-size"),
                    dictionaryFile);
        }
    }

    /// Reads native legacy blocks and mixed streams, checking the CLI-compatible ordering in both readers.
    @Test
    void officialLegacyAndConcatenatedFrames() throws IOException {
        String executable = officialExecutable();
        byte[] legacyContent = patternedContent(8 * 1024 * 1024 + 17);
        Path source = temporaryDirectory.resolve("source.bin");
        Path legacy = temporaryDirectory.resolve("legacy.lz4");
        Files.write(source, legacyContent);
        run(executable, List.of("-z", "-l", "-1", source.toString(), legacy.toString()));
        assertArrayEquals(legacyContent, decodeStream(new LZ4Codec(), Files.readAllBytes(legacy), legacyContent.length));

        byte[] text = officialText();
        ByteArrayOutputStream expectedLinked = new ByteArrayOutputStream();
        expectedLinked.writeBytes(text);
        expectedLinked.writeBytes(text);
        expectedLinked.writeBytes(new byte[]{'!', '!', '!', '!', '!'});
        Path mixed = temporaryDirectory.resolve("mixed.lz4");
        Path decoded = temporaryDirectory.resolve("decoded.bin");
        Files.write(mixed, linkedFrame(text, 3));
        run(executable, List.of("-d", mixed.toString(), decoded.toString()));
        assertArrayEquals(expectedLinked.toByteArray(), Files.readAllBytes(decoded));
        Files.write(mixed, emptyFrame());
        run(executable, List.of("-d", mixed.toString(), decoded.toString()));
        assertEquals(0, Files.size(decoded));
        ByteArrayOutputStream concatenated = new ByteArrayOutputStream();
        concatenated.writeBytes(Files.readAllBytes(resource("tests/goldenSamples/skip.bin")));
        concatenated.writeBytes(linkedFrame(text, 3));
        concatenated.writeBytes(emptyFrame());
        Files.write(mixed, concatenated.toByteArray());
        run(executable, List.of("-d", mixed.toString(), decoded.toString()));
        assertArrayEquals(expectedLinked.toByteArray(), Files.readAllBytes(decoded));
        concatenated.writeBytes(Files.readAllBytes(legacy));
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(expectedLinked.toByteArray());
        expected.writeBytes(legacyContent);
        // LZ4 1.10.0's CLI feeds all input after a standard frame to LZ4F, which rejects legacy magic.
        // Each component is checked above; Arkivo must still decode this ordering in full.
        assertArrayEquals(expected.toByteArray(), decodeStream(new LZ4Codec(), concatenated.toByteArray(), expected.size()));

        concatenated.reset();
        concatenated.writeBytes(Files.readAllBytes(legacy));
        concatenated.writeBytes(Files.readAllBytes(resource("tests/goldenSamples/skip.bin")));
        concatenated.writeBytes(linkedFrame(text, 3));
        concatenated.writeBytes(emptyFrame());
        expected.reset();
        expected.writeBytes(legacyContent);
        expected.writeBytes(expectedLinked.toByteArray());
        Files.write(mixed, concatenated.toByteArray());
        run(executable, List.of("-d", mixed.toString(), decoded.toString()));
        assertArrayEquals(expected.toByteArray(), Files.readAllBytes(decoded));
        assertArrayEquals(expected.toByteArray(), decodeStream(new LZ4Codec(), concatenated.toByteArray(), expected.size()));
    }

    /// Verifies native output with streams and fragmented buffers, then verifies Arkivo output with the native reader.
    private byte[] checkBothDirections(String executable, LZ4Codec codec, byte[] content,
                                      List<String> options, @Nullable Path dictionary) throws IOException {
        Path source = temporaryDirectory.resolve("source.bin");
        Path nativeFrame = temporaryDirectory.resolve("native.lz4");
        Path arkivoFrame = temporaryDirectory.resolve("arkivo.lz4");
        Path decoded = temporaryDirectory.resolve("decoded.bin");
        Files.write(source, content);
        List<String> compress = new ArrayList<>(options);
        compress.add("-z");
        if (dictionary != null) {
            compress.add("-D");
            compress.add(dictionary.toString());
        }
        compress.add(source.toString());
        compress.add(nativeFrame.toString());
        run(executable, compress);
        byte[] frame = Files.readAllBytes(nativeFrame);
        LZ4Codec bounded = codec.withMaximumOutputSize(content.length).withMaximumMemorySize(64L * 1024 * 1024);
        assertArrayEquals(content, decodeStream(bounded, frame, content.length), options.toString());
        assertArrayEquals(content, decodeChunks(bounded, frame, 97, 4093), options.toString());
        Files.write(arkivoFrame, bytes(codec.compress(ByteBuffer.wrap(content))));
        List<String> decompress = new ArrayList<>(List.of("-d"));
        if (dictionary != null) {
            decompress.add("-D");
            decompress.add(dictionary.toString());
        }
        decompress.add(arkivoFrame.toString());
        decompress.add(decoded.toString());
        run(executable, decompress);
        assertArrayEquals(content, Files.readAllBytes(decoded), options.toString());
        return frame;
    }

    /// Runs the reference tool without shell interpretation and kills it if the bounded wait fails.
    private void run(String executable, List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of(executable, "-q", "-f"));
        command.addAll(arguments);
        Path log = temporaryDirectory.resolve("lz4.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "LZ4 reference timed out: " + arguments);
            assertEquals(0, process.exitValue(), () -> {
                try {
                    return arguments + ": " + Files.readString(log);
                } catch (IOException failure) {
                    return arguments + ": " + failure;
                }
            });
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running the official LZ4 reference", failure);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    /// Decodes one standard or skippable frame using fresh padded direct input and bounded direct output.
    private static byte[] decodeChunks(LZ4Codec codec, byte[] frame, int inputChunk, int outputChunk) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        @UnmodifiableView ByteBuffer source = ByteBuffer.allocate(0).asReadOnlyBuffer();
        ByteBuffer target = ByteBuffer.allocateDirect(outputChunk + 2);
        int supplied = 0;
        int consumed = 0;
        try (var decoder = codec.newDecoder()) {
            while (true) {
                if (!source.hasRemaining() && supplied < frame.length) {
                    int size = Math.min(inputChunk, frame.length - supplied);
                    ByteBuffer storage = ByteBuffer.allocateDirect(size + 6).order(ByteOrder.LITTLE_ENDIAN);
                    storage.position(2).put(frame, supplied, size);
                    supplied += size;
                    if (supplied == frame.length) storage.putInt((int) LZ4Format.FRAME_MAGIC);
                    source = storage.flip().position(2).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
                }
                target.clear().put(0, (byte) 0x5a).put(outputChunk + 1, (byte) 0x5a);
                target.position(1).limit(outputChunk + 1);
                int before = source.position();
                int limit = source.limit();
                CodecOutcome outcome = supplied == frame.length ? decoder.finish(source, target)
                        : decoder.decode(source, target);
                consumed += source.position() - before;
                assertEquals(limit, source.limit());
                assertEquals(outputChunk + 1, target.limit());
                int count = target.position() - 1;
                byte[] bytes = new byte[count];
                target.flip().position(1).get(bytes);
                decoded.writeBytes(bytes);
                assertTrue(decoded.size() <= 16 * 1024 * 1024, "unexpected expansion");
                target.clear();
                assertEquals((byte) 0x5a, target.get(0));
                assertEquals((byte) 0x5a, target.get(outputChunk + 1));
                if (outcome == CodecOutcome.FINISHED) {
                    assertEquals(frame.length, consumed);
                    assertEquals(4, source.remaining());
                    assertEquals((int) LZ4Format.FRAME_MAGIC, source.getInt());
                    return decoded.toByteArray();
                }
                if (outcome == CodecOutcome.NEEDS_INPUT) {
                    assertTrue(supplied < frame.length);
                    assertEquals(source.limit(), source.position());
                } else {
                    assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
                    assertEquals(outputChunk, count);
                }
            }
        }
    }

    /// Bounds all stream reads independently of frame metadata.
    private static byte[] decodeStream(LZ4Codec codec, byte[] frame, int expectedSize) throws IOException {
        try (var input = codec.newInputStream(new ByteArrayInputStream(frame))) {
            byte[] decoded = input.readNBytes(expectedSize + 1);
            assertEquals(expectedSize, decoded.length);
            assertEquals(-1, input.read());
            return decoded;
        }
    }

    /// Builds a linked frame with raw prefix history, empty blocks, and a dictionary-spanning match.
    private static byte[] linkedFrame(byte[] text, int checksums) {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.writeBytes(header(0x40 | ((checksums & 1) != 0 ? 0x10 : 0) | ((checksums & 2) != 0 ? 4 : 0)));
        appendBlock(frame, new byte[0], true, checksums);
        appendBlock(frame, text, true, checksums);
        appendBlock(frame, new byte[0], true, checksums);
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.write(15);
        byte[] offset = new byte[2];
        ByteArrayAccess.writeShortLittleEndian(offset, 0, (short) text.length);
        block.writeBytes(offset);
        int length = text.length - 19;
        while (length >= 255) {
            block.write(255);
            length -= 255;
        }
        block.write(length);
        block.write(0x50);
        block.writeBytes(new byte[]{'!', '!', '!', '!', '!'});
        appendBlock(frame, block.toByteArray(), false, checksums);
        appendBlock(frame, new byte[0], true, checksums);
        frame.writeBytes(new byte[4]);
        if ((checksums & 2) != 0) {
            ByteArrayOutputStream plain = new ByteArrayOutputStream();
            plain.writeBytes(text);
            plain.writeBytes(text);
            plain.writeBytes(new byte[]{'!', '!', '!', '!', '!'});
            appendInt(frame, hash(plain.toByteArray()));
        }
        return frame.toByteArray();
    }

    /// Appends one physical block and its optional independent checksum.
    private static void appendBlock(ByteArrayOutputStream frame, byte[] content, boolean raw, int checksums) {
        appendInt(frame, content.length | (raw ? Integer.MIN_VALUE : 0));
        frame.writeBytes(content);
        if ((checksums & 1) != 0) appendInt(frame, hash(content));
    }

    /// Appends a little-endian wire integer using the shared byte-access helper.
    private static void appendInt(ByteArrayOutputStream output, int value) {
        byte[] bytes = new byte[4];
        ByteArrayAccess.writeIntLittleEndian(bytes, 0, value);
        output.writeBytes(bytes);
    }

    /// Creates an independent empty frame without content-size or checksum fields.
    private static byte[] emptyFrame() {
        return Arrays.copyOf(header(0x60), 11);
    }

    /// Creates a 64 KiB frame descriptor with a reference-computed header checksum.
    private static byte[] header(int flags) {
        byte[] header = new byte[7];
        ByteArrayAccess.writeIntLittleEndian(header, 0, (int) LZ4Format.FRAME_MAGIC);
        header[4] = (byte) flags;
        header[5] = 0x40;
        header[6] = (byte) (XXHashFactory.safeInstance().hash32().hash(header, 4, 2, 0) >>> 8);
        return header;
    }

    /// Computes the reference XXH32 value without using Arkivo's checksum implementation.
    private static int hash(byte[] bytes) {
        return XXHashFactory.safeInstance().hash32().hash(bytes, 0, bytes.length, 0);
    }

    /// Reads the unchanged upstream text embedded in the partial-decompression test.
    private static byte[] officialText() throws IOException {
        String source = Files.readString(resource("tests/decompress-partial.c"));
        var declaration = Pattern.compile("(?s)const char source\\[\\] =\\s*(.*?);").matcher(source);
        assertTrue(declaration.find(), "missing upstream source declaration");
        var literals = Pattern.compile("\"([^\"]*)\"").matcher(declaration.group(1));
        StringBuilder text = new StringBuilder();
        while (literals.find()) text.append(literals.group(1).replace("\\n", "\n"));
        byte[] bytes = text.toString().getBytes(StandardCharsets.US_ASCII);
        assertEquals(1313, bytes.length);
        try {
            assertEquals("3e0f9e8fede008f39ee3bb34b4ff016e7f3c05a130a1c74abb2532b7607d0c30",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
        return bytes;
    }

    /// Generates compressible multi-block data with a nonconstant 32 KiB repeating prefix.
    private static byte[] patternedContent(int size) {
        byte[] block = new byte[32 * 1024];
        new Random(0x4c5a_3400L).nextBytes(block);
        byte[] content = new byte[size];
        for (int i = 0; i < size; i += block.length) {
            System.arraycopy(block, 0, content, i, Math.min(block.length, size - i));
        }
        return content;
    }

    /// Copies the remaining bytes of a one-shot compression result.
    private static byte[] bytes(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    /// Resolves a file from the version-pinned Gradle download.
    private static Path resource(String name) {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.lz4.testDataDirectory"),
                "official LZ4 test data is not configured")).resolve(name);
    }
}
