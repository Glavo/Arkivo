// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.compress;

import org.apache.commons.compress.compressors.z.ZCompressorInputStream;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
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
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Exchanges bounded Unix compress streams with the optional native ncompress reference.
///
/// Set `ARKIVO_NCOMPRESS_EXECUTABLE` to a reference built with 16-bit dictionary support.
/// Set `ARKIVO_REQUIRE_NCOMPRESS=true` to fail instead of skipping when it is unavailable.
@NotNullByDefault
final class NcompressInteropTest {
    /// Per-invocation input, output, and diagnostic files for the reference process.
    @TempDir
    private Path temporaryDirectory;

    /// Pins the source input and the upstream regression that requires working nine-bit compression.
    @Test
    void verifiesReferenceSource() throws IOException, NoSuchAlgorithmException {
        byte[] source = Files.readAllBytes(resource("compress.c"));
        assertEquals("29c5a78005921a7881d8c83ea711e826c8c87f354b4a2f47f8c474117f6646d8",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)));
        assertTrue(Files.readString(resource("tests/runtests.sh")).contains("Check nine bits on large input"));
        assertTrue(Files.readString(resource("LICENSE.txt")).contains("public domain"));
        assertTrue(Files.isRegularFile(resource("UNLICENSE")));
    }

    /// Rejects a configured command that does not identify itself as a 16-bit ncompress implementation.
    @Test
    void verifiesNativeReferenceIdentity() throws IOException {
        Path output = temporaryDirectory.resolve("version.txt");
        run(executable(), List.of("-V"), output);
        String version = Files.readString(output) + Files.readString(temporaryDirectory.resolve("ncompress.log"));
        assertTrue(version.contains("(N)compress"), version);
        assertTrue(version.contains("BITS=16"), version);
    }

    /// Covers every supported dictionary width with empty, literal, source, repetitive, and changing data.
    private static Stream<Arguments> configurations() {
        return IntStream.rangeClosed(9, 16).boxed().flatMap(width ->
                Stream.of("empty", "literal", "source", "repeated", "random", "changing")
                        .map(kind -> Arguments.of(width, kind)));
    }

    /// Checks native output before using it as an oracle, then checks both Arkivo block-mode settings.
    @ParameterizedTest(name = "width={0}, input={1}")
    @MethodSource("configurations")
    void exchangesStreams(int width, String kind) throws IOException {
        String executable = executable();
        byte[] content = content(kind);
        Path input = temporaryDirectory.resolve("input.bin");
        Path nativeFrame = temporaryDirectory.resolve("native.Z");
        Path decoded = temporaryDirectory.resolve("decoded.bin");
        Files.write(input, content);
        run(executable, List.of("-f", "-c", "-b", Integer.toString(width), input.toString()), nativeFrame);
        run(executable, List.of("-d", "-c", nativeFrame.toString()), decoded);
        assertArrayEquals(content, Files.readAllBytes(decoded), "ncompress must decode its own output");
        byte[] frame = Files.readAllBytes(nativeFrame);
        assertHeader(frame, width, true);
        WireStatistics statistics = scanCodes(frame);
        if (kind.equals("changing")) {
            assertEquals(width, statistics.maximumWidth(), "input must reach the configured code width");
            assertTrue(statistics.clearCodes() > 0, "input must trigger native adaptive dictionary clearing");
        }

        UnixCompressCodec codec = new UnixCompressCodec().withMaximumOutputSize(content.length);
        try (var stream = codec.newInputStream(new ByteArrayInputStream(frame))) {
            assertArrayEquals(content, stream.readNBytes(content.length + 1));
            assertEquals(-1, stream.read());
        }
        // Commons is a second decoder, independent of both Arkivo and the native compressor.
        try (var stream = new ZCompressorInputStream(new ByteArrayInputStream(frame))) {
            assertArrayEquals(content, stream.readNBytes(content.length + 1));
            assertEquals(-1, stream.read());
        }
        try (var decoder = codec.newDecoder()) {
            for (int layout = 0; layout < 3; layout++) {
                int inputChunk = layout == 0 ? 1 : layout == 1 ? 17 : 8192;
                int outputChunk = layout == 0 ? 7 : layout == 1 ? 1 : 4093;
                assertArrayEquals(content, decodeChunks(decoder, frame, inputChunk, outputChunk, layout, content.length));
                decoder.reset();
            }
        }

        for (boolean blockMode : new boolean[]{false, true}) {
            UnixCompressCodec encoderCodec = new UnixCompressCodec(width, blockMode);
            Path arkivoFrame = temporaryDirectory.resolve("arkivo.Z");
            ByteBuffer compressed = encoderCodec.compress(ByteBuffer.wrap(content));
            byte[] bytes = new byte[compressed.remaining()];
            compressed.get(bytes);
            assertHeader(bytes, width, blockMode);
            Files.write(arkivoFrame, bytes);
            run(executable, List.of("-d", "-c", arkivoFrame.toString()), decoded);
            assertArrayEquals(content, Files.readAllBytes(decoded), "blockMode=" + blockMode);
        }
    }

    /// Reads LZW code boundaries without expanding dictionary entries, counting actual CLEAR operations.
    private static WireStatistics scanCodes(byte[] frame) {
        int maximum = frame[2] & 31;
        int width = 9;
        int greatestWidth = width;
        int next = 257;
        int clears = 0;
        int bit = 24;
        int groupStart = bit;
        boolean previous = false;
        while (bit + width <= frame.length * 8) {
            int code = 0;
            for (int i = 0; i < width; i++) {
                code |= ((frame[(bit + i) >>> 3] >>> ((bit + i) & 7)) & 1) << i;
            }
            bit += width;
            if (code == 256) {
                clears++;
                bit = alignCodeGroup(bit, groupStart, width);
                groupStart = bit;
                width = 9;
                next = 257;
                previous = false;
            } else {
                assertTrue(code <= (previous ? next : 255), "invalid reference LZW code");
                if (previous && next < (1 << maximum)) {
                    next++;
                    if (next == (1 << width) && width < maximum) {
                        bit = alignCodeGroup(bit, groupStart, width);
                        groupStart = bit;
                        width++;
                        greatestWidth = Math.max(greatestWidth, width);
                    }
                }
                previous = true;
            }
        }
        return new WireStatistics(greatestWidth, clears);
    }

    /// Rounds an old-width code position up to the next group of eight codes.
    private static int alignCodeGroup(int bit, int start, int width) {
        int groupBits = width * 8;
        return start + ((bit - start + groupBits - 1) / groupBits) * groupBits;
    }

    /// Records framing coverage without using the production decoder's dictionary state.
    ///
    /// @param maximumWidth largest code width reached
    /// @param clearCodes number of adaptive dictionary clear codes
    @NotNullByDefault
    private record WireStatistics(int maximumWidth, int clearCodes) {
    }

    /// Decodes padded heap, direct, or read-only inputs, poisoning each fully consumed storage region.
    private static byte[] decodeChunks(CompressionDecoder decoder, byte[] frame, int inputChunk,
                                       int outputChunk, int layout, int expectedSize) throws IOException {
        ByteBuffer storage = layout == 1 ? ByteBuffer.allocateDirect(inputChunk + 4)
                : ByteBuffer.allocate(inputChunk + 4);
        ByteBuffer source = ByteBuffer.allocate(0);
        ByteBuffer target = ByteBuffer.allocateDirect(outputChunk + 2);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int supplied = 0;
        for (long calls = 0; calls < 2L * (frame.length + expectedSize) + 64; calls++) {
            if (!source.hasRemaining() && supplied < frame.length) {
                storage.clear();
                int size = Math.min(inputChunk, frame.length - supplied);
                storage.position(2).put(frame, supplied, size).flip().position(2);
                source = layout == 2 ? storage.asReadOnlyBuffer() : storage.duplicate();
                supplied += size;
            }
            target.clear().put(0, (byte) 0x5a).put(outputChunk + 1, (byte) 0x5a);
            target.position(1).limit(outputChunk + 1);
            int before = source.position();
            int limit = source.limit();
            CodecOutcome outcome = supplied == frame.length ? decoder.finish(source, target)
                    : decoder.decode(source, target);
            assertEquals(limit, source.limit());
            assertEquals(outputChunk + 1, target.limit());
            int count = target.position() - 1;
            target.flip().position(1);
            while (target.hasRemaining()) output.write(target.get());
            assertTrue(output.size() <= expectedSize, "unexpected expansion");
            target.clear();
            assertEquals((byte) 0x5a, target.get(0));
            assertEquals((byte) 0x5a, target.get(outputChunk + 1));
            if (!source.hasRemaining()) {
                for (int index = 2; index < limit; index++) storage.put(index, (byte) 0xa5);
            }
            if (outcome == CodecOutcome.FINISHED) {
                assertEquals(frame.length, supplied);
                assertEquals(0, source.remaining());
                return output.toByteArray();
            }
            assertTrue(source.position() > before || count > 0, "decoder made no progress");
            if (outcome == CodecOutcome.NEEDS_INPUT) {
                assertEquals(0, source.remaining());
                assertTrue(supplied < frame.length, "decoder requested input after physical EOF");
            } else {
                assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
                assertEquals(outputChunk, count);
            }
        }
        throw new AssertionError("Unix compress decoder did not terminate");
    }

    /// Generates deterministic data, including a change in compressibility after the dictionary fills.
    private static byte[] content(String kind) throws IOException {
        switch (kind) {
            case "empty": return new byte[0];
            case "literal": return new byte[]{(byte) 0xff};
            case "source": return Files.readAllBytes(resource("compress.c"));
            case "repeated": {
                byte[] bytes = new byte[65_537];
                Arrays.fill(bytes, (byte) 'A');
                return bytes;
            }
            case "random": {
                byte[] bytes = new byte[196_613];
                new Random(0x5a_4c5a57L).nextBytes(bytes);
                return bytes;
            }
            case "changing": {
                byte[] bytes = new byte[786_449];
                new Random(0x5a_434c52L).nextBytes(bytes);
                // Seed repeat phrases before even a nine-bit dictionary fills with random pairs.
                Arrays.fill(bytes, 0, 16_384, (byte) 'A');
                Arrays.fill(bytes, 262_144, 524_288, (byte) 'A');
                return bytes;
            }
            default: throw new AssertionError(kind);
        }
    }

    /// Checks the signature, maximum width, and block-mode flag independently of Arkivo's parser.
    private static void assertHeader(byte[] frame, int width, boolean blockMode) {
        assertTrue(frame.length >= 3);
        assertEquals(0x1f, Byte.toUnsignedInt(frame[0]));
        assertEquals(0x9d, Byte.toUnsignedInt(frame[1]));
        assertEquals(width | (blockMode ? 0x80 : 0), Byte.toUnsignedInt(frame[2]));
    }

    /// Requires an explicitly configured reference when strict interoperability verification is enabled.
    private static String executable() {
        @Nullable String configured = System.getenv("ARKIVO_NCOMPRESS_EXECUTABLE");
        boolean available = configured != null && Files.isRegularFile(Path.of(configured));
        if (Boolean.parseBoolean(System.getenv("ARKIVO_REQUIRE_NCOMPRESS"))) {
            assertTrue(available, "Set ARKIVO_NCOMPRESS_EXECUTABLE to the required ncompress executable");
        }
        assumeTrue(available, "ARKIVO_NCOMPRESS_EXECUTABLE does not name an ncompress executable");
        return Objects.requireNonNull(configured);
    }

    /// Runs the reference without shell interpretation, redirecting both streams to avoid pipe deadlocks.
    private void run(String executable, List<String> arguments, Path output) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(arguments);
        Path error = temporaryDirectory.resolve("ncompress.log");
        Process process = new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(error.toFile()).start();
        try {
            process.getOutputStream().close();
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "ncompress timed out: " + arguments);
            assertEquals(0, process.exitValue(), () -> {
                try {
                    return arguments + ": " + Files.readString(error);
                } catch (IOException failure) {
                    return arguments + ": " + failure;
                }
            });
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running ncompress", failure);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    /// Resolves an unmodified file from the checksum-verified reference source download.
    private static Path resource(String name) {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.ncompress.testDataDirectory"),
                "ncompress test data is not configured")).resolve(name);
    }
}
