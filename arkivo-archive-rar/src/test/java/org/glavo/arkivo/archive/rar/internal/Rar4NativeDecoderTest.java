// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies synthetic legacy RAR bit streams against Arkivo's native decoders.
@NotNullByDefault
public final class Rar4NativeDecoderTest {
    /// Rolls back each construction stage without consuming another component's reservation.
    @ParameterizedTest
    @ValueSource(longs = {101_184, 101_376, 101_568, 101_760, 101_952})
    void rollsBackRar3Construction(long required) throws IOException {
        RarDecoderMemoryBudget memory = new RarDecoderMemoryBudget(17 + required - 1);
        memory.reserve(17);
        DecompressionMemoryLimitException failure = assertThrows(DecompressionMemoryLimitException.class,
                () -> new Rar4Lz29Decoder(1 << 16, memory));
        assertEquals(17 + required, failure.requiredMemorySize());
        assertEquals(17, memory.used());
        memory.release(17);
    }

    /// Enforces the LZ staging peak and retains only dictionary, PPMd fixed state, and live Huffman tables.
    @ParameterizedTest
    @ValueSource(longs = {233_031, 233_032})
    void enforcesRar3LiteralMemory(long maximum) throws IOException {
        RarDecoderMemoryBudget memory = new RarDecoderMemoryBudget(maximum);
        Rar4Lz29Decoder decoder = new Rar4Lz29Decoder(1 << 16, memory);
        assertEquals(101_952, memory.used());
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (maximum == 233_031) {
                    DecompressionMemoryLimitException failure = assertThrows(DecompressionMemoryLimitException.class,
                            () -> decoder.decode(new ByteArrayInputStream(rar3LiteralStream('A', true)), output, 1, false));
                    assertEquals(233_032, failure.requiredMemorySize());
                    assertEquals(0, output.size());
                } else {
                    decoder.decode(new ByteArrayInputStream(rar3LiteralStream('A', true)), output, 1, false);
                    assertArrayEquals(new byte[]{'A'}, output.toByteArray());
                }
                assertEquals(101_960, memory.used());
                decoder.invalidate();
                assertEquals(101_952, memory.used());
            }
        } finally {
            decoder.release();
            decoder.release();
        }
        assertEquals(0, memory.used());
    }

    /// Charges both arenas during PPMd growth and retains capacity when a later block requests less memory.
    @ParameterizedTest
    @ValueSource(longs = {3_313_251, 3_313_252})
    void enforcesRar3PpmGrowthPeak(long maximum) throws IOException {
        byte[] small = {(byte) 0xa1, 0, 0x40, (byte) 0xfd, (byte) 0xc8, 0x3f, 0, 0};
        byte[] large = small.clone();
        large[1] = 1;
        RarDecoderMemoryBudget memory = new RarDecoderMemoryBudget(maximum);
        Rar4Lz29Decoder decoder = new Rar4Lz29Decoder(1 << 16, memory);
        try {
            decoder.decode(new ByteArrayInputStream(small), OutputStream.nullOutputStream(), 3, false);
            assertEquals(1_150_548, memory.used());
            if (maximum == 3_313_251) {
                DecompressionMemoryLimitException failure = assertThrows(DecompressionMemoryLimitException.class,
                        () -> decoder.decode(new ByteArrayInputStream(large), OutputStream.nullOutputStream(), 3, true));
                assertEquals(3_313_252, failure.requiredMemorySize());
                assertEquals(1_150_548, memory.used());
            } else {
                decoder.decode(new ByteArrayInputStream(large), OutputStream.nullOutputStream(), 3, true);
                assertEquals(2_199_120, memory.used());
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            decoder.decode(new ByteArrayInputStream(small), output, 3, false);
            assertArrayEquals(new byte[]{'A', 'A', 'A'}, output.toByteArray());
            assertEquals(maximum == 3_313_251 ? 1_150_548 : 2_199_120, memory.used());
        } finally {
            decoder.release();
        }
        assertEquals(0, memory.used());
    }

    /// Applies one budget to RAR3 VM execution and recovers across failures and decoder-family switches.
    @Test
    void enforcesRar3FilterMemoryAndSessionCleanup() throws Exception {
        Rar4Decoder.Session session = Rar4Decoder.newSession(1 << 16, 495_250);
        var memoryField = Rar4Decoder.Session.class.getDeclaredField("memory");
        memoryField.setAccessible(true);
        RarDecoderMemoryBudget memory = (RarDecoderMemoryBudget) memoryField.get(session);
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                session.decode(new ByteArrayInputStream(rar3FilteredStream(5)), output, 29, 1 << 16, 3, false);
                assertArrayEquals(new byte[]{'A', 'A', 'A'}, output.toByteArray());
                session.decode(new ByteArrayInputStream(rar3LiteralStream('B', false)), output, 36, 1 << 16, 1, false);
                session.decode(new ByteArrayInputStream(new byte[]{0x40}), output, 29, 1 << 16, 1, true);
                assertArrayEquals(new byte[]{'A', 'A', 'A', 'B', 'B'}, output.toByteArray());
                assertThrows(IOException.class, () -> session.decode(new ByteArrayInputStream(rar3FilteredStream(5)),
                        new OutputStream() {
                            /// Rejects the fully transformed block to exercise nested workspace cleanup.
                            @Override
                            public void write(int value) throws IOException {
                                throw new IOException("test output failure");
                            }
                        }, 29, 1 << 16, 3, false));
                assertEquals(0, memory.used());
                session.decode(new ByteArrayInputStream(rar20LiteralStream(1)), output, 20, 1 << 16, 1, false);
                session.decode(new ByteArrayInputStream(new byte[0]), output, 15, 1 << 16, 0, false);
            }
        } finally {
            session.release();
            session.release();
        }
        assertEquals(0, memory.used());
        Rar4Decoder.Session rejected = Rar4Decoder.newSession(1 << 16, 495_249);
        try {
            DecompressionMemoryLimitException failure = assertThrows(DecompressionMemoryLimitException.class,
                    () -> rejected.decode(new ByteArrayInputStream(rar3FilteredStream(5)),
                            OutputStream.nullOutputStream(), 29, 1 << 16, 3, false));
            assertEquals(495_250, failure.requiredMemorySize());
            assertEquals(0, ((RarDecoderMemoryBudget) memoryField.get(rejected)).used());
        } finally {
            rejected.release();
        }
    }

    /// Drops the packed input after success, a truncated range prefix, or a failing output sink.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void detachesRar3PpmInput(int failureMode) throws Exception {
        byte[] packed = {(byte) 0xa1, 0, 0x40, (byte) 0xfd, (byte) 0xc8, 0x3f, 0, 0};
        Rar4Lz29Decoder decoder = new Rar4Lz29Decoder(1 << 16, new RarDecoderMemoryBudget(-1));
        var rangeField = Rar4Lz29Decoder.class.getDeclaredField("ppmRangeDecoder");
        rangeField.setAccessible(true);
        Object rangeDecoder = rangeField.get(decoder);
        var inputField = Rar3PPMdRangeDecoder.class.getDeclaredField("input");
        inputField.setAccessible(true);
        try {
            ByteArrayInputStream input = new ByteArrayInputStream(
                    failureMode == 1 ? Arrays.copyOf(packed, 4) : packed) {
                /// Detects accidental closure of borrowed compressed input.
                @Override
                public void close() {
                    throw new AssertionError("Borrowed RAR input was closed");
                }
            };
            OutputStream output = failureMode < 2 ? new ByteArrayOutputStream() : new OutputStream() {
                /// Fails during emission after the PPMd range input has been attached.
                @Override
                public void write(int value) throws IOException {
                    if (failureMode == 2) throw new IOException("test output failure");
                    throw new IllegalStateException("test output failure");
                }
            };
            if (failureMode == 0) {
                decoder.decode(input, output, 3, false);
                assertArrayEquals(new byte[]{'A', 'A', 'A'}, ((ByteArrayOutputStream) output).toByteArray());
            } else if (failureMode < 3) {
                assertThrows(IOException.class, () -> decoder.decode(input, output, 3, false));
            } else {
                assertThrows(IllegalStateException.class, () -> decoder.decode(input, output, 3, false));
            }
            assertNull(inputField.get(rangeDecoder));

            if (failureMode == 0) {
                ByteArrayOutputStream continuation = new ByteArrayOutputStream();
                decoder.decode(new ByteArrayInputStream(new byte[]{(byte) 0x81, 0, 0, 0, 0, 0, 0}),
                        continuation, 1, true);
                assertArrayEquals(new byte[]{'A'}, continuation.toByteArray());
                assertNull(inputField.get(rangeDecoder));
            }
            decoder.invalidate();
            ByteArrayOutputStream recovered = new ByteArrayOutputStream();
            decoder.decode(new ByteArrayInputStream(packed), recovered, 3, false);
            assertArrayEquals(new byte[]{'A', 'A', 'A'}, recovered.toByteArray());
            assertNull(inputField.get(rangeDecoder));
        } finally {
            decoder.release();
        }
        assertNull(inputField.get(rangeDecoder));
    }

    /// Recovers the session budget after rejection, output failure, and a change of decoder family.
    @Test
    void recoversLegacySessionMemoryBudget() throws IOException {
        Rar4Decoder.Session session = Rar4Decoder.newSession(1 << 18, 203_792);
        byte[] packed = rar20LiteralStream(1);
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                ByteArrayInputStream oversizedInput = new ByteArrayInputStream(packed);
                DecompressionMemoryLimitException failure = assertThrows(DecompressionMemoryLimitException.class,
                        () -> session.decode(oversizedInput, OutputStream.nullOutputStream(), 15, 1 << 17, 0, false));
                assertEquals(269_328, failure.actual());
                assertEquals(packed.length, oversizedInput.available());
                assertThrows(IOException.class,
                        () -> session.decode(oversizedInput, OutputStream.nullOutputStream(), 15, 1 << 16, 0, true));
                assertEquals(packed.length, oversizedInput.available());
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                session.decode(new ByteArrayInputStream(packed), output, 20, 1 << 16, 1, false);
                assertArrayEquals(new byte[]{'A'}, output.toByteArray());
                session.decode(new ByteArrayInputStream(new byte[]{0}), output, 26, 1 << 16, 1, true);
                assertArrayEquals(new byte[]{'A', 'A'}, output.toByteArray());
                assertThrows(IOException.class, () -> session.decode(new ByteArrayInputStream(packed),
                        new OutputStream() {
                            /// Rejects decoded output to exercise session failure cleanup.
                            @Override
                            public void write(int value) throws IOException {
                                throw new IOException("test output failure");
                            }
                        }, 20, 1 << 16, 1, false));
                assertEquals(0, session.decode(new ByteArrayInputStream(new byte[0]),
                        OutputStream.nullOutputStream(), 15, 1 << 16, 0, false));
            }
        } finally {
            session.release();
            session.release();
        }
        assertThrows(IllegalArgumentException.class, () -> Rar4Decoder.newSession(1 << 16, -2));
    }

    /// Rejects incomplete engine construction without retaining reservations for partially allocated tables.
    @Test
    void rollsBackLegacyEngineConstruction() throws IOException {
        RarDecoderMemoryBudget rar15 = new RarDecoderMemoryBudget(72_719);
        assertThrows(DecompressionMemoryLimitException.class, () -> new Rar4Lz15Decoder(1 << 16, rar15));
        assertEquals(0, rar15.used());
        for (int tables = 0; tables <= 7; tables++) {
            int maximum = (1 << 16) + 1364 + tables * 192 - 1;
            RarDecoderMemoryBudget memory = new RarDecoderMemoryBudget(maximum);
            assertThrows(DecompressionMemoryLimitException.class, () -> new Rar4Lz20Decoder(1 << 16, memory));
            assertEquals(0, memory.used());
        }
    }

    /// Checks the exact RAR 1.5 workspace ceiling, reuse, failure cleanup, and repeated release.
    @Test
    void accountsForRar15Workspace() throws IOException {
        BitWriter writer = new BitWriter();
        writer.write(0b00001, 5);
        writer.write(0xe5, 8);
        byte[] packed = writer.toByteArray();
        for (int maximum : new int[]{203_791, 203_792}) {
            RarDecoderMemoryBudget memory = new RarDecoderMemoryBudget(maximum);
            Rar4Lz15Decoder decoder = new Rar4Lz15Decoder(1 << 16, memory);
            assertEquals(72_720, memory.used());
            try {
                for (int attempt = 0; attempt < 3; attempt++) {
                    ByteArrayInputStream input = new ByteArrayInputStream(packed);
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    if (maximum < 203_792) {
                        DecompressionMemoryLimitException failure = assertThrows(DecompressionMemoryLimitException.class,
                                () -> decoder.decode(input, output, 1, false));
                        assertEquals(203_792, failure.actual());
                        assertEquals(packed.length, input.available());
                    } else {
                        decoder.decode(input, output, 1, false);
                        assertArrayEquals(new byte[]{'A'}, output.toByteArray());
                        assertThrows(IOException.class, () -> decoder.decode(new ByteArrayInputStream(packed),
                                new OutputStream() {
                                    /// Fails when decoded bytes are flushed.
                                    @Override
                                    public void write(int value) throws IOException {
                                        throw new IOException("test output failure");
                                    }
                                }, 1, false));
                    }
                    assertEquals(72_720, memory.used());
                }
            } finally {
                decoder.release();
                decoder.release();
            }
            assertEquals(0, memory.used());
        }
    }

    /// Counts RAR 2.x table-building scratch space when a new table is read after output staging exists.
    @Test
    void accountsForRar20TableRebuildPeak() throws IOException {
        BitWriter writer = new BitWriter();
        writeRar20LiteralTable(writer);
        writer.write(0, 1);
        writer.write(1, 1);
        writeRar20LiteralTable(writer);
        writer.write(0, 1);
        for (boolean rebuild : new boolean[]{false, true}) {
            byte[] packed = rebuild ? writer.toByteArray() : rar20LiteralStream(1);
            int peak = rebuild ? 200_639 : 199_324;
            for (int maximum : new int[]{peak - 1, peak}) {
                RarDecoderMemoryBudget memory = new RarDecoderMemoryBudget(maximum);
                Rar4Lz20Decoder decoder = new Rar4Lz20Decoder(1 << 16, memory);
                assertEquals(68_244, memory.used());
                try {
                    for (int attempt = 0; attempt < 3; attempt++) {
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        if (maximum < peak) {
                            DecompressionMemoryLimitException failure = assertThrows(DecompressionMemoryLimitException.class,
                                    () -> decoder.decode(new ByteArrayInputStream(packed), output, rebuild ? 2 : 1, false));
                            assertEquals(peak, failure.actual());
                        } else {
                            decoder.decode(new ByteArrayInputStream(packed), output, rebuild ? 2 : 1, false);
                            assertArrayEquals(rebuild ? new byte[]{'A', 'A'} : new byte[]{'A'}, output.toByteArray());
                        }
                        assertEquals(68_252, memory.used());
                    }
                } finally {
                    decoder.release();
                    decoder.release();
                }
                assertEquals(0, memory.used());
            }
        }
    }

    /// Keeps inactive ordinary and audio Huffman tables charged while switching modes within one entry.
    @Test
    void accountsForRar20InactiveTables() throws IOException {
        BitWriter writer = new BitWriter();
        writeRar20LiteralTable(writer);
        writer.write(0, 1);
        writer.write(1, 1);
        writeRar20AudioTable(writer);
        writer.write(0, 1);
        writer.write(1, 1);
        writeRar20LiteralTable(writer);
        writer.write(0, 1);
        byte[] packed = writer.toByteArray();
        for (int maximum : new int[]{200_646, 200_647}) {
            RarDecoderMemoryBudget memory = new RarDecoderMemoryBudget(maximum);
            Rar4Lz20Decoder decoder = new Rar4Lz20Decoder(1 << 16, memory);
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (maximum < 200_647) {
                    DecompressionMemoryLimitException failure = assertThrows(DecompressionMemoryLimitException.class,
                            () -> decoder.decode(new ByteArrayInputStream(packed), output, 3, false));
                    assertEquals(200_647, failure.actual());
                } else {
                    decoder.decode(new ByteArrayInputStream(packed), output, 3, false);
                    assertArrayEquals(new byte[]{'A', 0, 'A'}, output.toByteArray());
                }
                assertEquals(68_260, memory.used());
                decoder.invalidate();
                assertEquals(68_244, memory.used());
                decoder.decode(new ByteArrayInputStream(rar20AudioStream(1)), OutputStream.nullOutputStream(), 1, false);
                assertEquals(68_252, memory.used());
            } finally {
                decoder.release();
            }
            assertEquals(0, memory.used());
        }
    }

    /// Initializes each legacy engine when the first decoded entry carries the solid flag.
    @Test
    void initializesFirstSolidEntryWithDeclaredWindow() throws IOException {
        BitWriter rar15 = new BitWriter();
        rar15.write(0b00001, 5);
        rar15.write(0xe5, 8);
        for (int version : new int[]{15, 20, 29}) {
            byte[] packed = switch (version) {
                case 15 -> rar15.toByteArray();
                case 20 -> rar20LiteralStream(1);
                default -> rar3LiteralStream('A', true);
            };
            Rar4Decoder.Session session = Rar4Decoder.newSession(1 << 16, ArchiveReadLimits.UNLIMITED_SIZE);
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                session.decode(new ByteArrayInputStream(packed), output, version, 1 << 16, 1, true);
                assertArrayEquals(new byte[]{'A'}, output.toByteArray());
            } finally {
                session.release();
            }
        }
    }

    /// Wraps the smallest declared window repeatedly and retains it for a solid continuation.
    @Test
    void wrapsSmallRar20Dictionary() throws IOException {
        int length = (1 << 17) + 7;
        byte[] expected = new byte[length];
        Arrays.fill(expected, (byte) 'A');
        Rar4Decoder.Session session = Rar4Decoder.newSession(1 << 16, ArchiveReadLimits.UNLIMITED_SIZE);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            session.decode(new ByteArrayInputStream(rar20LiteralStream(length)), output, 20, 1 << 16, length, false);
            assertArrayEquals(expected, output.toByteArray());
            output.reset();
            session.decode(new ByteArrayInputStream(repeatedZeroBits(3)), output, 20, 1 << 16, 3, true);
            assertArrayEquals(new byte[]{'A', 'A', 'A'}, output.toByteArray());
        } finally {
            session.release();
        }
    }

    /// Rejects a match beyond the declared history even when enough preceding bytes were emitted.
    @Test
    void enforcesDeclaredRar3MatchWindow() throws IOException {
        int distance = 262_145;
        byte[] packed = rar3HighDistanceStream(distance);
        for (int window : new int[]{1 << 18, 1 << 19}) {
            Rar4Decoder.Session session = Rar4Decoder.newSession(window, ArchiveReadLimits.UNLIMITED_SIZE);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                if (window < distance) {
                    IOException failure = assertThrows(IOException.class,
                            () -> session.decode(new ByteArrayInputStream(packed), output, 29, window,
                                    distance + 260, false));
                    assertEquals("RAR4 match distance exceeds available dictionary history", failure.getMessage());
                } else {
                    session.decode(new ByteArrayInputStream(packed), output, 29, window, distance + 260, false);
                    byte[] expected = new byte[distance + 260];
                    Arrays.fill(expected, (byte) 'A');
                    assertArrayEquals(expected, output.toByteArray());
                }
            } finally {
                session.release();
            }
        }
    }

    /// Retains the active solid window when a continuation declares a smaller size.
    @Test
    void retainsRar3HistoryAcrossSmallerWindowDeclarations() throws IOException {
        Rar4Decoder.Session session = Rar4Decoder.newSession(1 << 19, ArchiveReadLimits.UNLIMITED_SIZE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BitWriter continuation = new BitWriter();
        continuation.write(0b11, 2);
        continuation.write(0b1_1111, 5);
        continuation.write(0, 1);
        continuation.write(0, 12);
        continuation.write(0, 1);
        continuation.write(0b10, 2);
        continuation.write(0, 2);
        byte[] match = continuation.toByteArray();
        try {
            session.decode(new ByteArrayInputStream(rar3HighDistanceStream(262_145)), output, 29,
                    1 << 19, 262_405, false);
            output.reset();
            session.decode(new ByteArrayInputStream(match), output, 29, 1 << 16, 260, true);
            byte[] expected = new byte[260];
            Arrays.fill(expected, (byte) 'A');
            assertArrayEquals(expected, output.toByteArray());

            // A non-solid entry discards both the larger window and its entropy tables.
            session.decode(new ByteArrayInputStream(rar3LiteralStream('B', true)), output, 29, 1 << 16, 1, false);
            assertThrows(IOException.class,
                    () -> session.decode(new ByteArrayInputStream(match), output, 29, 1 << 16, 260, true));
        } finally {
            session.release();
        }
    }

    /// Decodes one literal through the adaptive RAR 1.5 flag and character models.
    @Test
    public void decodesRar15AdaptiveLiteral() throws IOException {
        BitWriter writer = new BitWriter();
        writer.write(0b00001, 5);
        writer.write(0xe5, 8);

        Rar4Decoder.Session session = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long actualCrc32 = session.decode(
                new ByteArrayInputStream(writer.toByteArray()),
                output,
                15,
                1 << 22,
                1,
                false
        );
        assertArrayEquals("A".getBytes(StandardCharsets.US_ASCII), output.toByteArray());
        assertEquals(crc32(output.toByteArray()), actualCrc32);
        session.release();
    }

    /// Decodes long, short, and repeated matches while retaining the RAR 1.5 solid dictionary.
    @Test
    public void decodesRar15MatchesAcrossSolidEntries() throws IOException {
        Rar4Decoder.Session session = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);

        BitWriter longMatchWriter = new BitWriter();
        longMatchWriter.write(0xab, 8);
        longMatchWriter.write(0xe5, 8);
        longMatchWriter.write(0, 16);
        longMatchWriter.write(0, 4);
        longMatchWriter.write(1, 7);
        longMatchWriter.write(0, 16);
        byte[] expectedLongMatch = new byte[12];
        Arrays.fill(expectedLongMatch, (byte) 'A');
        ByteArrayOutputStream longMatchOutput = new ByteArrayOutputStream();
        long actualLongMatchCrc32 = session.decode(
                new ByteArrayInputStream(longMatchWriter.toByteArray()),
                longMatchOutput,
                15,
                1 << 22,
                expectedLongMatch.length,
                false
        );
        assertArrayEquals(expectedLongMatch, longMatchOutput.toByteArray());
        assertEquals(crc32(expectedLongMatch), actualLongMatchCrc32);

        BitWriter shortMatchWriter = new BitWriter();
        shortMatchWriter.write(0xab, 8);
        shortMatchWriter.write(0, 1);
        shortMatchWriter.write(0, 5);
        shortMatchWriter.write(0, 16);
        byte[] expectedShortMatch = {'A', 'A'};
        ByteArrayOutputStream shortMatchOutput = new ByteArrayOutputStream();
        long actualShortMatchCrc32 = session.decode(
                new ByteArrayInputStream(shortMatchWriter.toByteArray()),
                shortMatchOutput,
                15,
                1 << 22,
                expectedShortMatch.length,
                true
        );
        assertArrayEquals(expectedShortMatch, shortMatchOutput.toByteArray());
        assertEquals(crc32(expectedShortMatch), actualShortMatchCrc32);

        BitWriter repeatedMatchWriter = new BitWriter();
        repeatedMatchWriter.write(1, 5);
        repeatedMatchWriter.write(0b1100, 4);
        repeatedMatchWriter.write(0, 16);
        ByteArrayOutputStream repeatedMatchOutput = new ByteArrayOutputStream();
        long actualRepeatedMatchCrc32 = session.decode(
                new ByteArrayInputStream(repeatedMatchWriter.toByteArray()),
                repeatedMatchOutput,
                15,
                1 << 22,
                expectedShortMatch.length,
                true
        );
        assertArrayEquals(expectedShortMatch, repeatedMatchOutput.toByteArray());
        assertEquals(crc32(expectedShortMatch), actualRepeatedMatchCrc32);

        BitWriter recentDistanceWriter = new BitWriter();
        recentDistanceWriter.write(0, 5);
        recentDistanceWriter.write(0b1000, 4);
        recentDistanceWriter.write(0, 2);
        recentDistanceWriter.write(0, 16);
        ByteArrayOutputStream recentDistanceOutput = new ByteArrayOutputStream();
        long actualRecentDistanceCrc32 = session.decode(
                new ByteArrayInputStream(recentDistanceWriter.toByteArray()),
                recentDistanceOutput,
                15,
                1 << 22,
                expectedShortMatch.length,
                true
        );
        assertArrayEquals(expectedShortMatch, recentDistanceOutput.toByteArray());
        assertEquals(crc32(expectedShortMatch), actualRecentDistanceCrc32);
        session.release();
    }

    /// Rejects RAR 1.5 match codes that reference unavailable dictionary history.
    @Test
    public void rejectsRar15MatchesWithoutHistory() {
        Rar4Decoder.Session session = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);

        BitWriter repeatedMatchWriter = new BitWriter();
        repeatedMatchWriter.write(0, 5);
        repeatedMatchWriter.write(0b1100, 4);
        repeatedMatchWriter.write(0, 16);
        IOException repeatedMatchFailure = assertThrows(
                IOException.class,
                () -> session.decode(
                        new ByteArrayInputStream(repeatedMatchWriter.toByteArray()),
                        new ByteArrayOutputStream(),
                        15,
                        1 << 22,
                        1L,
                        false
                )
        );
        assertEquals("RAR1.5 stream repeats an unavailable match", repeatedMatchFailure.getMessage());

        BitWriter shortMatchWriter = new BitWriter();
        shortMatchWriter.write(0, 5);
        shortMatchWriter.write(0, 1);
        shortMatchWriter.write(0, 5);
        shortMatchWriter.write(0, 16);
        IOException shortMatchFailure = assertThrows(
                IOException.class,
                () -> session.decode(
                        new ByteArrayInputStream(shortMatchWriter.toByteArray()),
                        new ByteArrayOutputStream(),
                        15,
                        1 << 22,
                        2L,
                        false
                )
        );
        assertEquals(
                "RAR1.5 match distance exceeds available dictionary history",
                shortMatchFailure.getMessage()
        );
        session.release();
    }

    /// Decodes a RAR 2.x literal table and reuses it for a solid continuation.
    @Test
    public void decodesRar20LiteralsAndSolidContinuation() throws IOException {
        Rar4Decoder.Session session = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        ByteArrayOutputStream firstOutput = new ByteArrayOutputStream();
        long firstCrc32 = session.decode(
                new ByteArrayInputStream(rar20LiteralStream(6)),
                firstOutput,
                20,
                1 << 22,
                6,
                false
        );
        assertArrayEquals("AAAAAA".getBytes(StandardCharsets.US_ASCII), firstOutput.toByteArray());
        assertEquals(crc32(firstOutput.toByteArray()), firstCrc32);

        ByteArrayOutputStream secondOutput = new ByteArrayOutputStream();
        long secondCrc32 = session.decode(
                new ByteArrayInputStream(repeatedZeroBits(3)),
                secondOutput,
                20,
                1 << 22,
                3,
                true
        );
        assertArrayEquals("AAA".getBytes(StandardCharsets.US_ASCII), secondOutput.toByteArray());
        assertEquals(crc32(secondOutput.toByteArray()), secondCrc32);
        session.release();
    }

    /// Decodes RAR 2.x long and short distance matches from one synthesized Huffman table.
    @Test
    public void decodesRar20LongAndShortMatches() throws IOException {
        byte[] expected = new byte[6];
        Arrays.fill(expected, (byte) 'A');
        Rar4Decoder.Session session = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long actualCrc32 = session.decode(
                new ByteArrayInputStream(rar20MatchStream()),
                output,
                20,
                1 << 22,
                expected.length,
                false
        );
        assertArrayEquals(expected, output.toByteArray());
        assertEquals(crc32(expected), actualCrc32);
        session.release();
    }

    /// Decodes RAR 2.x repeated and recent-distance matches after establishing dictionary history.
    @Test
    public void decodesRar20RepeatedAndRecentMatches() throws IOException {
        byte[] expected = new byte[9];
        Arrays.fill(expected, (byte) 'A');
        Rar4Decoder.Session session = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long actualCrc32 = session.decode(
                new ByteArrayInputStream(rar20RepeatedMatchStream()),
                output,
                20,
                1 << 22,
                expected.length,
                false
        );
        assertArrayEquals(expected, output.toByteArray());
        assertEquals(crc32(expected), actualCrc32);
        session.release();
    }

    /// Decodes RAR 3.x long and short distance matches from one synthesized Huffman table.
    @Test
    public void decodesRar3LongAndShortMatches() throws IOException {
        byte[] expected = new byte[6];
        Arrays.fill(expected, (byte) 'A');
        Rar4Decoder.Session session = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long actualCrc32 = session.decode(
                new ByteArrayInputStream(rar3MatchStream()),
                output,
                29,
                1 << 22,
                expected.length,
                false
        );
        assertArrayEquals(expected, output.toByteArray());
        assertEquals(crc32(expected), actualCrc32);
        session.release();
    }

    /// Decodes RAR 3.x repeated and recent-distance matches after establishing dictionary history.
    @Test
    public void decodesRar3RepeatedAndRecentMatches() throws IOException {
        byte[] expected = new byte[9];
        Arrays.fill(expected, (byte) 'A');
        Rar4Decoder.Session session = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long actualCrc32 = session.decode(
                new ByteArrayInputStream(rar3RepeatedMatchStream()),
                output,
                29,
                1 << 22,
                expected.length,
                false
        );
        assertArrayEquals(expected, output.toByteArray());
        assertEquals(crc32(expected), actualCrc32);
        session.release();
    }

    /// Decodes replacement Huffman tables after an in-file RAR 3.x block boundary.
    @Test
    public void decodesRar3ReplacementTables() throws IOException {
        byte[] expected = {'A', 'B'};
        Rar4Decoder.Session session = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        long actualCrc32 = session.decode(
                new ByteArrayInputStream(rar3ReplacementTableStream()),
                output,
                29,
                1 << 22,
                expected.length,
                false
        );

        assertArrayEquals(expected, output.toByteArray());
        assertEquals(crc32(expected), actualCrc32);
        session.release();
    }

    /// Uses the preceding file's end marker to select replacement or retained tables in a solid sequence.
    @Test
    public void selectsRar3SolidTablesAfterDeclaredOutputIsComplete() throws IOException {
        Rar4Decoder.Session session = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            session.decode(new ByteArrayInputStream(rar3LiteralStream('A', true)), output, 29, 1 << 22, 1, false);
            session.decode(new ByteArrayInputStream(rar3LiteralStream('B', false)), output, 29, 1 << 22, 1, true);
            BitWriter continuation = new BitWriter();
            continuation.write(0, 1);
            continuation.write(1, 1);
            continuation.write(0, 2);
            session.decode(new ByteArrayInputStream(continuation.toByteArray()), output, 29, 1 << 22, 1, true);
            assertArrayEquals(new byte[]{'A', 'B', 'B'}, output.toByteArray());
        } finally {
            session.release();
        }
    }

    /// Rejects every byte truncation, including a missing marker after the last literal.
    @Test
    public void rejectsTruncatedRar3LiteralAndEndMarker() {
        byte[] packed = rar3LiteralStream('A', true);
        for (int length = 0; length < packed.length; length++) {
            Rar4Decoder.Session session = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
            byte[] truncated = Arrays.copyOf(packed, length);
            try {
                assertThrows(IOException.class, () -> session.decode(
                        new ByteArrayInputStream(truncated), new ByteArrayOutputStream(), 29, 1 << 22, 1, false),
                        "Truncated length: " + length);
            } finally {
                session.release();
            }
        }
    }

    /// Builds one complete literal file with an explicit next-file table selection.
    private static byte[] rar3LiteralStream(int literal, boolean nextFileUsesNewTables) {
        BitWriter writer = new BitWriter();
        writer.write(0, 2);
        writeRar3LevelAlphabet(writer);
        writeZeroRun(writer, literal);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 255 - literal);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 147);
        writer.write(0, 1);
        writer.write(1, 1);
        writer.write(0, 1);
        writer.write(nextFileUsesNewTables ? 1 : 0, 1);
        return writer.toByteArray();
    }

    /// Decodes RAR 3.x VM-filtered blocks through every filter-descriptor length encoding.
    @Test
    public void decodesRar3FilterDescriptorLengths() throws IOException {
        byte[] expected = {'A', 'A', 'A'};

        for (int payloadSize : new int[]{5, 7, 8}) {
            Rar4Decoder.Session session = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            long actualCrc32 = session.decode(
                    new ByteArrayInputStream(rar3FilteredStream(payloadSize)),
                    output,
                    29,
                    1 << 22,
                    expected.length,
                    false
            );

            assertArrayEquals(expected, output.toByteArray());
            assertEquals(crc32(expected), actualCrc32);
            session.release();
        }
    }

    /// Decodes consecutive RAR 3.x long matches that reuse the compact low-distance symbol.
    @Test
    public void decodesRar3RepeatedLowDistance() throws IOException {
        byte[] expected = new byte[39];
        Arrays.fill(expected, (byte) 'A');
        Rar4Decoder.Session session = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        long actualCrc32 = session.decode(
                new ByteArrayInputStream(rar3RepeatedLowDistanceStream()),
                output,
                29,
                1 << 22,
                expected.length,
                false
        );

        assertArrayEquals(expected, output.toByteArray());
        assertEquals(crc32(expected), actualCrc32);
        session.release();
    }

    /// Selects the oldest RAR 3.x recent distance after populating all four queue positions.
    @Test
    public void decodesRar3OldestRecentDistance() throws IOException {
        byte[] expected = new byte[30];
        Arrays.fill(expected, (byte) 'A');
        Rar4Decoder.Session session = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        long actualCrc32 = session.decode(
                new ByteArrayInputStream(rar3OldestRecentDistanceStream()),
                output,
                29,
                1 << 22,
                expected.length,
                false
        );

        assertArrayEquals(expected, output.toByteArray());
        assertEquals(crc32(expected), actualCrc32);
        session.release();
    }

    /// Decodes the largest RAR 3.x length slot across both long-distance length-adjustment thresholds.
    @Test
    public void decodesRar3MaximumLengthAtHighDistance() throws IOException {
        int distance = 262_145;
        int adjustedMatchLength = 260;
        byte[] expected = new byte[distance + adjustedMatchLength];
        Arrays.fill(expected, (byte) 'A');
        Rar4Decoder.Session session = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        long actualCrc32 = session.decode(
                new ByteArrayInputStream(rar3HighDistanceStream(distance)),
                output,
                29,
                1 << 22,
                expected.length,
                false
        );

        assertArrayEquals(expected, output.toByteArray());
        assertEquals(crc32(expected), actualCrc32);
        session.release();
    }

    /// Decodes zero deltas through a one-channel RAR 2.x adaptive audio table.
    @Test
    public void decodesRar20AudioDeltas() throws IOException {
        int sampleCount = 64;
        Rar4Decoder.Session session = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long actualCrc32 = session.decode(
                new ByteArrayInputStream(rar20AudioStream(sampleCount)),
                output,
                20,
                1 << 22,
                sampleCount,
                false
        );
        assertArrayEquals(new byte[sampleCount], output.toByteArray());
        assertEquals(crc32(output.toByteArray()), actualCrc32);
        session.release();
    }

    /// Decodes repeated symbols through root updates and a newly created binary RAR3 PPMd context.
    @Test
    public void decodesRar3PpmContextSuccessor() throws IOException {
        byte[] packed = {
                (byte) 0xa1,
                0,
                0x40, (byte) 0xfd, (byte) 0xc8, 0x3f,
                0, 0
        };
        Rar4Decoder.Session session = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long actualCrc32 = session.decode(
                new ByteArrayInputStream(packed),
                output,
                29,
                1 << 22,
                3,
                false
        );
        assertArrayEquals(new byte[]{'A', 'A', 'A'}, output.toByteArray());
        assertEquals(crc32(output.toByteArray()), actualCrc32);

        byte[] continuation = {(byte) 0x81, 0, 0, 0, 0, 0, 0};
        ByteArrayOutputStream continuationOutput = new ByteArrayOutputStream();
        long continuationCrc32 = session.decode(
                new ByteArrayInputStream(continuation),
                continuationOutput,
                29,
                1 << 22,
                1,
                true
        );
        assertArrayEquals(new byte[]{'A'}, continuationOutput.toByteArray());
        assertEquals(crc32(continuationOutput.toByteArray()), continuationCrc32);
        session.release();
    }

    /// Returns the unsigned CRC32 of the supplied bytes.
    private static long crc32(byte[] bytes) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes);
        return crc32.getValue();
    }

    /// Builds a normal RAR 2.x table containing one literal and one table marker.
    private static byte[] rar20LiteralStream(int literalCount) {
        BitWriter writer = new BitWriter();
        writeRar20LiteralTable(writer);
        for (int index = 0; index < literalCount; index++) {
            writer.write(0, 1);
        }
        return writer.toByteArray();
    }

    /// Writes a normal-mode table containing literal A and the table-change marker.
    private static void writeRar20LiteralTable(BitWriter writer) {
        writer.write(0, 1);
        writer.write(0, 1);
        writeLevelAlphabet(writer);
        writeZeroRun(writer, 65);
        writeLevelOne(writer);
        writeZeroRun(writer, 138);
        writeZeroRun(writer, 65);
        writeLevelOne(writer);
        writeZeroRun(writer, 104);
    }

    /// Builds a normal RAR 2.x table and payload containing one literal followed by long and short matches.
    private static byte[] rar20MatchStream() {
        BitWriter writer = new BitWriter();
        writer.write(0, 1);
        writer.write(0, 1);
        writeThreeSymbolLevelAlphabet(writer);
        writeZeroRun(writer, 65);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 195);
        writeThreeSymbolLevelValue(writer, 2);
        for (int index = 0; index < 8; index++) {
            writeThreeSymbolLevelValue(writer, 0);
        }
        writeThreeSymbolLevelValue(writer, 2);
        writeZeroRun(writer, 27);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 75);

        writer.write(0, 1);
        writer.write(0b11, 2);
        writer.write(0, 1);
        writer.write(0b10, 2);
        writer.write(0, 2);
        return writer.toByteArray();
    }

    /// Builds a RAR 2.x payload that repeats its last match and then selects the newest stored distance.
    private static byte[] rar20RepeatedMatchStream() {
        BitWriter writer = new BitWriter();
        writer.write(0, 1);
        writer.write(0, 1);
        writeThreeSymbolLevelAlphabet(writer);
        writeZeroRun(writer, 65);
        writeThreeSymbolLevelValue(writer, 2);
        writeZeroRun(writer, 190);
        writeThreeSymbolLevelValue(writer, 2);
        writeThreeSymbolLevelValue(writer, 2);
        writeZeroRun(writer, 12);
        writeThreeSymbolLevelValue(writer, 2);
        writeZeroRun(writer, 27);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 47);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 27);

        writer.write(0b00, 2);
        writer.write(0b11, 2);
        writer.write(0, 1);
        writer.write(0b01, 2);
        writer.write(0b10, 2);
        writer.write(0, 1);
        writer.write(0, 16);
        return writer.toByteArray();
    }

    /// Builds a normal RAR 3.x table and payload containing one literal followed by long and short matches.
    private static byte[] rar3MatchStream() {
        BitWriter writer = new BitWriter();
        writer.write(0, 1);
        writer.write(0, 1);
        writeRar3LevelAlphabet(writer);
        writeZeroRun(writer, 65);
        writeThreeSymbolLevelValue(writer, 2);
        writeZeroRun(writer, 190);
        writeThreeSymbolLevelValue(writer, 2);
        for (int index = 0; index < 6; index++) {
            writeThreeSymbolLevelValue(writer, 0);
        }
        writeThreeSymbolLevelValue(writer, 2);
        for (int index = 0; index < 7; index++) {
            writeThreeSymbolLevelValue(writer, 0);
        }
        writeThreeSymbolLevelValue(writer, 2);
        writeZeroRun(writer, 27);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 104);

        writer.write(0, 2);
        writer.write(0b11, 2);
        writer.write(0, 1);
        writer.write(0b10, 2);
        writer.write(0, 2);
        writer.write(0b01, 2);
        writer.write(0, 2);
        return writer.toByteArray();
    }

    /// Builds a RAR 3.x payload that repeats its last match and then selects the newest stored distance.
    private static byte[] rar3RepeatedMatchStream() {
        BitWriter writer = new BitWriter();
        writer.write(0, 1);
        writer.write(0, 1);
        writeRar3FourSymbolLevelAlphabet(writer);
        writeZeroRun(writer, 65);
        writeFourSymbolLevelValue(writer, 3);
        writeZeroRun(writer, 190);
        writeFourSymbolLevelValue(writer, 3);
        writeFourSymbolLevelValue(writer, 0);
        writeFourSymbolLevelValue(writer, 3);
        writeFourSymbolLevelValue(writer, 3);
        writeZeroRun(writer, 11);
        writeFourSymbolLevelValue(writer, 3);
        writeZeroRun(writer, 27);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 76);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 27);

        writer.write(0b000, 3);
        writer.write(0b100, 3);
        writer.write(0, 1);
        writer.write(0b010, 3);
        writer.write(0b011, 3);
        writer.write(0, 1);
        writer.write(0b001, 3);
        writer.write(0, 2);
        return writer.toByteArray();
    }

    /// Builds two RAR 3.x Huffman blocks whose literals use distinct replacement tables.
    private static byte[] rar3ReplacementTableStream() {
        BitWriter writer = new BitWriter();
        writer.write(0, 1);
        writer.write(0, 1);
        writeRar3LevelAlphabet(writer);
        writeZeroRun(writer, 65);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 190);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 147);

        writer.write(0, 1);
        writer.write(1, 1);
        writer.write(1, 1);
        writer.alignToByte();

        writer.write(0, 1);
        writer.write(0, 1);
        writeRar3LevelAlphabet(writer);
        writeZeroRun(writer, 66);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 189);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 147);
        writer.write(0, 1);
        writer.write(1, 1);
        writer.write(0, 2);
        return writer.toByteArray();
    }

    /// Builds one literal block transformed by a no-op RAR3 VM program in the requested descriptor envelope.
    private static byte[] rar3FilteredStream(int payloadSize) {
        byte[] program = {0x5c, 0x5c};
        BitWriter payloadWriter = new BitWriter();
        payloadWriter.writeEncodedUint32(0);
        payloadWriter.writeEncodedUint32(3);
        payloadWriter.writeEncodedUint32(program.length);
        payloadWriter.writeBytes(program);
        byte[] encodedPayload = payloadWriter.toByteArray();
        if (payloadSize < encodedPayload.length) {
            throw new IllegalArgumentException("Synthetic RAR3 filter payload is too small");
        }
        byte[] payload = Arrays.copyOf(encodedPayload, payloadSize);

        BitWriter writer = new BitWriter();
        writer.write(0, 1);
        writer.write(0, 1);
        writeRar3LevelAlphabet(writer);
        writeZeroRun(writer, 65);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 190);
        writeThreeSymbolLevelValue(writer, 2);
        writeThreeSymbolLevelValue(writer, 2);
        writeZeroRun(writer, 146);

        writer.write(0b11, 2);
        if (payloadSize <= 6) {
            writer.write(0x20 | payloadSize - 1, 8);
        } else if (payloadSize == 7) {
            writer.write(0x26, 8);
            writer.write(0, 8);
        } else if (payloadSize == 8) {
            writer.write(0x27, 8);
            writer.write(payloadSize, 16);
        } else {
            throw new IllegalArgumentException("Unsupported synthetic RAR3 filter payload size");
        }
        writer.writeBytes(payload);
        writer.write(0, 3);
        writer.write(0b10, 2);
        writer.write(0, 2);
        return writer.toByteArray();
    }

    /// Builds two high-distance matches whose second low-distance component is implicitly repeated.
    private static byte[] rar3RepeatedLowDistanceStream() {
        BitWriter writer = new BitWriter();
        writer.write(0, 1);
        writer.write(0, 1);
        writeRar3LevelAlphabet(writer);
        writeZeroRun(writer, 65);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 190);
        writeThreeSymbolLevelValue(writer, 2);
        writeZeroRun(writer, 14);
        writeThreeSymbolLevelValue(writer, 2);
        writeZeroRun(writer, 37);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 65);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 28);

        writeZeroBits(writer, 33);
        writer.write(0b11, 2);
        writer.write(0, 1);
        writer.write(0, 1);
        writer.write(0b11, 2);
        writer.write(0, 1);
        writer.write(0b10, 2);
        writer.write(0, 2);
        return writer.toByteArray();
    }

    /// Builds four short matches and then selects the oldest stored distance with a recent-match symbol.
    private static byte[] rar3OldestRecentDistanceStream() {
        BitWriter writer = new BitWriter();
        writer.write(0, 1);
        writer.write(0, 1);
        writeRar3FourSymbolLevelAlphabet(writer);
        writeZeroRun(writer, 65);
        writeFourSymbolLevelValue(writer, 3);
        writeZeroRun(writer, 190);
        writeFourSymbolLevelValue(writer, 3);
        for (int index = 0; index < 5; index++) {
            writeFourSymbolLevelValue(writer, 0);
        }
        for (int symbol = 262; symbol <= 266; symbol++) {
            writeFourSymbolLevelValue(writer, 3);
        }
        writeZeroRun(writer, 109);
        writeFourSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 27);

        writeZeroBits(writer, 60);
        writer.write(0b011, 3);
        writer.write(0, 2);
        writer.write(0b100, 3);
        writer.write(0, 2);
        writer.write(0b101, 3);
        writer.write(0, 3);
        writer.write(0b110, 3);
        writer.write(0, 4);
        writer.write(0b010, 3);
        writer.write(0, 1);
        writer.write(0b001, 3);
        writer.write(0, 2);
        return writer.toByteArray();
    }

    /// Builds one maximum-length match whose distance requires high and low distance components.
    private static byte[] rar3HighDistanceStream(int distance) {
        if (distance != 262_145) {
            throw new IllegalArgumentException("Synthetic RAR3 distance must select slot 36");
        }
        BitWriter writer = new BitWriter();
        writer.write(0, 1);
        writer.write(0, 1);
        writeRar3LevelAlphabet(writer);
        writeZeroRun(writer, 65);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 190);
        writeThreeSymbolLevelValue(writer, 2);
        writeZeroRun(writer, 41);
        writeThreeSymbolLevelValue(writer, 2);
        writeZeroRun(writer, 36);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 23);
        writeThreeSymbolLevelValue(writer, 1);
        writeZeroRun(writer, 44);

        writeZeroBits(writer, distance);
        writer.write(0b11, 2);
        writer.write(0b1_1111, 5);
        writer.write(0, 1);
        writer.write(0, 12);
        writer.write(0, 1);
        writer.write(0b10, 2);
        writer.write(0, 2);
        return writer.toByteArray();
    }

    /// Builds a one-channel audio table containing a zero delta and table marker.
    private static byte[] rar20AudioStream(int sampleCount) {
        BitWriter writer = new BitWriter();
        writeRar20AudioTable(writer);
        int remaining = sampleCount;
        while (remaining > 0) {
            int count = Math.min(remaining, 31);
            writer.write(0, count);
            remaining -= count;
        }
        return writer.toByteArray();
    }

    /// Writes a one-channel audio table containing zero delta and the table-change marker.
    private static void writeRar20AudioTable(BitWriter writer) {
        writer.write(1, 1);
        writer.write(0, 1);
        writer.write(0, 2);
        writeLevelAlphabet(writer);
        writeLevelOne(writer);
        writeZeroRun(writer, 138);
        writeZeroRun(writer, 117);
        writeLevelOne(writer);
    }

    /// Returns a byte-aligned sequence containing the requested number of zero bits.
    private static byte[] repeatedZeroBits(int count) {
        BitWriter writer = new BitWriter();
        writeZeroBits(writer, count);
        return writer.toByteArray();
    }

    /// Writes the canonical level alphabet used by both synthetic table descriptions.
    private static void writeLevelAlphabet(BitWriter writer) {
        for (int symbol = 0; symbol < 19; symbol++) {
            int length = symbol == 0 || symbol == 1 ? 2 : symbol == 18 ? 1 : 0;
            writer.write(length, 4);
        }
    }

    /// Writes a level alphabet that can describe code lengths zero, one, and two.
    private static void writeThreeSymbolLevelAlphabet(BitWriter writer) {
        for (int symbol = 0; symbol < 19; symbol++) {
            int length = symbol <= 2 ? 3 : symbol == 18 ? 1 : 0;
            writer.write(length, 4);
        }
    }

    /// Writes the equivalent RAR 3.x level alphabet whose long zero-run symbol is nineteen.
    private static void writeRar3LevelAlphabet(BitWriter writer) {
        for (int symbol = 0; symbol < 20; symbol++) {
            int length = symbol <= 2 ? 3 : symbol == 19 ? 1 : 0;
            writer.write(length, 4);
        }
    }

    /// Writes a RAR 3.x level alphabet that can describe code lengths zero through three.
    private static void writeRar3FourSymbolLevelAlphabet(BitWriter writer) {
        for (int symbol = 0; symbol < 20; symbol++) {
            int length = symbol <= 3 ? 3 : symbol == 19 ? 1 : 0;
            writer.write(length, 4);
        }
    }

    /// Writes one direct zero, one, or two code-length symbol from the three-symbol level alphabet.
    private static void writeThreeSymbolLevelValue(BitWriter writer, int value) {
        if (value < 0 || value > 2) {
            throw new IllegalArgumentException("Synthetic RAR level value is out of range");
        }
        writer.write(0b100 + value, 3);
    }

    /// Writes one direct code-length symbol from the four-symbol RAR 3.x level alphabet.
    private static void writeFourSymbolLevelValue(BitWriter writer, int value) {
        if (value < 0 || value > 3) {
            throw new IllegalArgumentException("Synthetic RAR3 level value is out of range");
        }
        writer.write(0b100 + value, 3);
    }

    /// Writes the code-length symbol representing a literal table length of one.
    private static void writeLevelOne(BitWriter writer) {
        writer.write(0b11, 2);
    }

    /// Writes one or more long zero-run symbols.
    private static void writeZeroRun(BitWriter writer, int count) {
        int remaining = count;
        while (remaining != 0) {
            int current = Math.min(remaining, 138);
            int tail = remaining - current;
            if (tail > 0 && tail < 11) {
                current -= 11 - tail;
            }
            if (current < 11) {
                throw new IllegalArgumentException("Synthetic RAR zero run is too short");
            }
            writer.write(0, 1);
            writer.write(current - 11, 7);
            remaining -= current;
        }
    }

    /// Writes an arbitrary number of zero bits using fields accepted by [BitWriter].
    private static void writeZeroBits(BitWriter writer, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Synthetic zero-bit count must not be negative");
        }
        int remaining = count;
        while (remaining > 0) {
            int current = Math.min(remaining, 31);
            writer.write(0, current);
            remaining -= current;
        }
    }

    /// Writes most-significant-bit-first fields into a byte array.
    @NotNullByDefault
    private static final class BitWriter {
        /// Completed bytes.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        /// The partially assembled current byte.
        private int currentByte;

        /// The number of high bits already assigned in {@link #currentByte}.
        private int currentBitCount;

        /// Creates an empty bit writer.
        private BitWriter() {
        }

        /// Writes the requested low bits from most to least significant.
        private void write(int value, int count) {
            if (count < 0 || count > 31) {
                throw new IllegalArgumentException("Invalid synthetic bit count");
            }
            for (int bit = count - 1; bit >= 0; bit--) {
                currentByte = currentByte << 1 | value >>> bit & 1;
                currentBitCount++;
                if (currentBitCount == 8) {
                    output.write(currentByte);
                    currentByte = 0;
                    currentBitCount = 0;
                }
            }
        }

        /// Writes one RAR3 variable-width unsigned integer using its shortest representation.
        private void writeEncodedUint32(int value) {
            if (value >= 0 && value <= 0x0f) {
                write(0, 2);
                write(value, 4);
            } else if (value >= 0 && value <= 0xff) {
                write(1, 2);
                write(value, 8);
            } else if (value >= 0 && value <= 0xffff) {
                write(2, 2);
                write(value, 16);
            } else {
                write(3, 2);
                write(value >>> 16, 16);
                write(value, 16);
            }
        }

        /// Writes complete bytes without inserting alignment padding.
        private void writeBytes(byte[] values) {
            for (byte value : values) {
                write(value & 0xff, Byte.SIZE);
            }
        }

        /// Pads the current partial byte with zero bits.
        private void alignToByte() {
            if (currentBitCount != 0) {
                write(0, 8 - currentBitCount);
            }
        }

        /// Returns all bits with zero padding in the final low-order positions.
        private byte[] toByteArray() {
            if (currentBitCount != 0) {
                output.write(currentByte << (8 - currentBitCount));
                currentByte = 0;
                currentBitCount = 0;
            }
            return output.toByteArray();
        }
    }
}
