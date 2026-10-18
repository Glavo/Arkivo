// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies RAR4 and RAR5 decoder capability calculations and session lifecycle transitions.
@NotNullByDefault
final class RarDecoderSessionTest {
    /// Bytes needed by a RAR5 entry outside its dictionary and optional filters.
    private static final int RAR5_WORKSPACE = Rar5LzDecoder.FIXED_MEMORY_SIZE
            + Rar5BitInput.BUFFER_SIZE + Rar5OutputPipeline.OUTPUT_BUFFER_SIZE;

    /// Rejects each allocation boundary before reading packed bytes and makes subsequent failures independent.
    @Test
    void rejectsRar5MemoryBeforeReadingInput() {
        int window = 1 << 17;
        for (long required : new long[]{Rar5LzDecoder.FIXED_MEMORY_SIZE,
                Rar5LzDecoder.FIXED_MEMORY_SIZE + window,
                Rar5LzDecoder.FIXED_MEMORY_SIZE + window + Rar5BitInput.BUFFER_SIZE,
                RAR5_WORKSPACE + window}) {
            Rar5Decoder.Session session = Rar5Decoder.newSession(-1, required - 1);
            try {
                for (int attempt = 0; attempt < 2; attempt++) {
                    ByteArrayInputStream input = new ByteArrayInputStream(new byte[]{1, 2, 3});
                    DecompressionMemoryLimitException failure = assertThrows(DecompressionMemoryLimitException.class,
                            () -> session.decode(input, OutputStream.nullOutputStream(), 0, 0, false, false, 1));
                    assertEquals(required - 1, failure.maximum());
                    assertEquals(required, failure.actual());
                    assertEquals(3, input.available());
                    IOException continuation = assertThrows(IOException.class,
                            () -> session.decode(input, OutputStream.nullOutputStream(), 0, 0, false, true, 1));
                    assertTrue(continuation.getMessage().contains("missing its preceding decompression history"));
                    assertEquals(3, input.available());
                }
            } finally {
                session.release();
                session.release();
            }
        }
        assertThrows(IllegalArgumentException.class, () -> Rar5Decoder.newSession(-1, -2));
    }

    /// Recovers with a smaller non-solid dictionary after a budget failure and reuses both alphabet versions.
    @Test
    void recoversRar5SessionAfterMemoryFailure() throws IOException {
        byte[] content = {'A', 'B', 'B', 'A'};
        Rar5Decoder.Session session = Rar5Decoder.newSession(-1, RAR5_WORKSPACE + (1 << 17));
        try {
            assertThrows(DecompressionMemoryLimitException.class,
                    () -> session.decode(InputStream.nullInputStream(), OutputStream.nullOutputStream(),
                            2, 0, false, false, content.length));
            for (boolean version7 : new boolean[]{false, true, false}) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                session.decode(new ByteArrayInputStream(literalRar5Block(version7, content)), output,
                        0, 0, version7, false, content.length);
                assertArrayEquals(content, output.toByteArray());
            }
        } finally {
            session.release();
        }
    }

    /// Charges retained capacity, releases entry workspace, and discards old non-solid windows before growing.
    @Test
    void accountsForRetainedRar5Workspace() throws IOException {
        byte[] content = {'A', 'B'};
        int maximumWindow = 1 << 18;
        RarDecoderMemoryBudget memory = new RarDecoderMemoryBudget(RAR5_WORKSPACE + maximumWindow);
        Rar5LzDecoder decoder = new Rar5LzDecoder(memory);
        assertEquals(Rar5LzDecoder.FIXED_MEMORY_SIZE, memory.used());
        try {
            int retainedWindow = 0;
            for (int window : new int[]{1 << 17, maximumWindow, 1 << 16}) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                decoder.decode(new ByteArrayInputStream(literalRar5Block(false, content)), output,
                        window, false, false, content.length);
                assertArrayEquals(content, output.toByteArray());
                retainedWindow = Math.max(retainedWindow, window);
                assertEquals(Rar5LzDecoder.FIXED_MEMORY_SIZE + retainedWindow, memory.used());
            }
            assertThrows(IOException.class, () -> decoder.decode(InputStream.nullInputStream(),
                    OutputStream.nullOutputStream(), 1 << 16, false, false, 1));
            assertEquals(Rar5LzDecoder.FIXED_MEMORY_SIZE + maximumWindow, memory.used());
        } finally {
            decoder.release();
        }
        assertEquals(0, memory.used());
        decoder.release();
        assertEquals(0, memory.used());
        assertThrows(IOException.class, () -> decoder.decode(InputStream.nullInputStream(),
                OutputStream.nullOutputStream(), 1 << 17, false, false, 0));
    }

    /// Leaves reservations unchanged after rejection and detects arithmetic and release errors.
    @Test
    void validatesMemoryReservations() throws IOException {
        RarDecoderMemoryBudget memory = new RarDecoderMemoryBudget(10);
        memory.reserve(10);
        DecompressionMemoryLimitException failure = assertThrows(DecompressionMemoryLimitException.class,
                () -> memory.reserve(1));
        assertEquals(10, failure.maximum());
        assertEquals(11, failure.actual());
        assertEquals(10, memory.used());
        assertThrows(IllegalArgumentException.class, () -> memory.reserve(-1));
        assertThrows(IllegalArgumentException.class, () -> memory.release(-1));
        assertThrows(IllegalArgumentException.class, () -> memory.release(11));
        assertEquals(10, memory.used());
        memory.release(10);
        assertEquals(0, memory.used());
        RarDecoderMemoryBudget unlimited = new RarDecoderMemoryBudget(-1);
        unlimited.reserve(Long.MAX_VALUE);
        assertThrows(IOException.class, () -> unlimited.reserve(1));
        assertEquals(Long.MAX_VALUE, unlimited.used());
        unlimited.release(Long.MAX_VALUE);
        assertEquals(0, unlimited.used());
    }

    /// Encodes one block with literal A/B symbols and empty match alphabets for either RAR5 table layout.
    private static byte[] literalRar5Block(boolean version7, byte[] content) {
        int lengthCount = version7 ? 446 : 430;
        int bitCount = 80 + lengthCount + content.length;
        int payloadSize = (bitCount + 7) / 8;
        byte[] block = new byte[3 + payloadSize];
        int flags = 0xc0 | ((bitCount - 1) & 7);
        block[0] = (byte) flags;
        block[1] = (byte) (0x5a ^ flags ^ payloadSize);
        block[2] = (byte) payloadSize;
        // Level symbols 0 and 1 use one bit each; only literals A and B have nonzero code lengths.
        block[3] = 0x11;
        for (int symbol : new int[]{'A', 'B'}) {
            int bit = 80 + symbol;
            block[3 + bit / 8] |= (byte) (1 << (7 - bit % 8));
        }
        for (int index = 0; index < content.length; index++) {
            if (content[index] != 'A' && content[index] != 'B') {
                throw new IllegalArgumentException("Only A/B literals are supported");
            }
            int bit = 80 + lengthCount + index;
            block[3 + bit / 8] |= (byte) ((content[index] - 'A') << (7 - bit % 8));
        }
        return block;
    }

    /// Rejects oversized windows before consuming packed bytes and invalidates continuation history.
    @Test
    void rejectsRar4WindowsBeforeReadingInput() {
        for (int version : new int[]{15, 20, 26, 29, 36}) {
            for (int power = 16; power <= 22; power++) {
                int window = 1 << power;
                Rar4Decoder.Session session = Rar4Decoder.newSession(window - 1, ArchiveReadLimits.UNLIMITED_SIZE);
                ByteArrayInputStream input = new ByteArrayInputStream(new byte[]{1, 2, 3});
                try {
                    DecompressionWindowLimitException failure = assertThrows(DecompressionWindowLimitException.class,
                            () -> session.decode(input, OutputStream.nullOutputStream(), version, window, 0, false));
                    assertEquals(window - 1, failure.maximum());
                    assertEquals(window, failure.actual());
                    assertEquals(3, input.available());
                    assertThrows(IOException.class,
                            () -> session.decode(input, OutputStream.nullOutputStream(), version, window, 0, true));
                    assertEquals(3, input.available());
                } finally {
                    session.release();
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> Rar4Decoder.newSession(-2, ArchiveReadLimits.UNLIMITED_SIZE));
    }

    /// Keeps a solid window across smaller declarations and permits resizing only after a reset.
    @Test
    void resizesRar4WindowsOnlyForNonSolidEntries() throws IOException {
        Rar4Decoder.Session session = Rar4Decoder.newSession(1 << 18, ArchiveReadLimits.UNLIMITED_SIZE);
        InputStream input = InputStream.nullInputStream();
        OutputStream output = OutputStream.nullOutputStream();
        try {
            assertEquals(0, session.decode(input, output, 15, 1 << 17, 0, true));
            assertEquals(0, session.decode(input, output, 15, 1 << 16, 0, true));
            assertEquals(0, session.decode(input, output, 15, 1 << 17, 0, true));
            assertThrows(IOException.class, () -> session.decode(input, output, 15, 1 << 18, 0, true));
            assertThrows(IOException.class, () -> session.decode(input, output, 15, 1 << 16, 0, true));
            assertEquals(0, session.decode(input, output, 15, 1 << 18, 0, false));
            assertEquals(0, session.decode(input, output, 15, 1 << 16, 0, false));
            assertThrows(IOException.class, () -> session.decode(input, output, 15, 1 << 17, 0, true));
        } finally {
            session.release();
        }
    }

    /// Applies the RAR5 window limit to fractional dictionaries before reading input.
    @Test
    void rejectsRar5WindowsBeforeReadingInput() {
        for (int fraction : new int[]{0, 1, 16, 31}) {
            long window = Rar5Decoder.dictionarySize(1, fraction);
            Rar5Decoder.Session session = Rar5Decoder.newSession(window - 1, ArchiveReadLimits.UNLIMITED_SIZE);
            ByteArrayInputStream input = new ByteArrayInputStream(new byte[]{1, 2, 3});
            try {
                DecompressionWindowLimitException failure = assertThrows(DecompressionWindowLimitException.class,
                        () -> session.decode(input, OutputStream.nullOutputStream(), 1, fraction, true, false, 0));
                assertEquals(window - 1, failure.maximum());
                assertEquals(window, failure.actual());
                assertEquals(3, input.available());
                assertThrows(IOException.class, () -> session.decode(input, OutputStream.nullOutputStream(),
                        0, 0, false, true, 0));
                assertEquals(3, input.available());
            } finally {
                session.release();
            }
        }
        assertThrows(IllegalArgumentException.class, () -> Rar5Decoder.newSession(-2, ArchiveReadLimits.UNLIMITED_SIZE));
    }

    /// Verifies the native RAR4 decoder advertises exactly its implemented extraction versions.
    @Test
    void recognizesRar4ExtractionVersions() {
        assertFalse(Rar4Decoder.supports(14));
        assertTrue(Rar4Decoder.supports(15));
        assertFalse(Rar4Decoder.supports(16));
        assertTrue(Rar4Decoder.supports(20));
        assertTrue(Rar4Decoder.supports(26));
        assertTrue(Rar4Decoder.supports(29));
        assertTrue(Rar4Decoder.supports(36));
        assertFalse(Rar4Decoder.supports(37));
    }

    /// Verifies successful, failed, invalidated, cross-family, and released RAR4 session states.
    @Test
    void validatesRar4SessionLifecycle() throws IOException {
        Rar4Decoder.Session validation = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        assertThrows(
                NullPointerException.class,
                () -> validation.decode(null, OutputStream.nullOutputStream(), 15, 1 << 22, 0L, false)
        );
        assertThrows(
                NullPointerException.class,
                () -> validation.decode(InputStream.nullInputStream(), null, 15, 1 << 22, 0L, false)
        );
        assertThrows(IOException.class, () -> decodeRar4(validation, 14, 0L, false));
        assertThrows(IOException.class, () -> decodeRar4(validation, 15, -1L, false));
        validation.release();

        Rar4Decoder.Session successful = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        assertEquals(0L, decodeRar4(successful, 15, 0L, false));
        assertEquals(0L, decodeRar4(successful, 15, 0L, true));
        assertThrows(IOException.class, () -> decodeRar4(successful, 20, 0L, true));
        successful.release();

        Rar4Decoder.Session failed = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        assertThrows(IOException.class, () -> decodeRar4(failed, 15, 1L, false));
        assertThrows(IOException.class, () -> decodeRar4(failed, 15, 0L, true));
        failed.release();

        Rar4Decoder.Session invalidated = Rar4Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        invalidated.invalidateHistory();
        assertThrows(IOException.class, () -> decodeRar4(invalidated, 15, 0L, true));
        assertEquals(0L, decodeRar4(invalidated, 15, 0L, false));
        invalidated.release();
        invalidated.release();
        invalidated.invalidateHistory();
        assertThrows(IOException.class, () -> decodeRar4(invalidated, 15, 0L, false));
    }

    /// Verifies RAR5 dictionary arithmetic, support limits, and property validation.
    @Test
    void computesRar5DictionarySizes() {
        assertEquals(32L << 12, Rar5Decoder.dictionarySize(0, 0));
        assertEquals(48L << 24, Rar5Decoder.dictionarySize(12, 16));
        assertEquals(63L << 43, Rar5Decoder.dictionarySize(31, 31));
        assertTrue(Rar5Decoder.supportsDictionary(12, 16));
        assertFalse(Rar5Decoder.supportsDictionary(12, 17));
        assertThrows(IllegalArgumentException.class, () -> Rar5Decoder.dictionarySize(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> Rar5Decoder.dictionarySize(32, 0));
        assertThrows(IllegalArgumentException.class, () -> Rar5Decoder.dictionarySize(0, -1));
        assertThrows(IllegalArgumentException.class, () -> Rar5Decoder.dictionarySize(0, 32));
    }

    /// Verifies failed, invalidated, oversized, and released RAR5 session states.
    @Test
    void validatesRar5SessionLifecycle() throws IOException {
        Rar5Decoder.Session validation = Rar5Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        assertThrows(
                NullPointerException.class,
                () -> validation.decode(null, OutputStream.nullOutputStream(), 0, 0, false, false, 0L)
        );
        assertThrows(
                NullPointerException.class,
                () -> validation.decode(InputStream.nullInputStream(), null, 0, 0, false, false, 0L)
        );
        assertThrows(IOException.class, () -> decodeRar5(validation, 12, 17, false, 0L));
        assertThrows(IOException.class, () -> decodeRar5(validation, 0, 0, false, -1L));
        validation.release();

        Rar5Decoder.Session failed = Rar5Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        assertThrows(IOException.class, () -> decodeRar5(failed, 0, 0, false, 0L));
        assertThrows(IOException.class, () -> decodeRar5(failed, 0, 0, true, 0L));
        failed.release();

        Rar5Decoder.Session invalidated = Rar5Decoder.newSession(ArchiveReadLimits.UNLIMITED_SIZE, ArchiveReadLimits.UNLIMITED_SIZE);
        invalidated.invalidateHistory();
        assertThrows(IOException.class, () -> decodeRar5(invalidated, 0, 0, true, 0L));
        invalidated.release();
        invalidated.release();
        invalidated.invalidateHistory();
        assertThrows(IOException.class, () -> decodeRar5(invalidated, 0, 0, false, 0L));
    }

    /// Runs one legacy decoder request against empty borrowed streams.
    private static long decodeRar4(
            Rar4Decoder.Session session,
            int extractionVersion,
            long unpackedSize,
            boolean solid
    ) throws IOException {
        return session.decode(
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream(),
                extractionVersion,
                1 << 22,
                unpackedSize,
                solid
        );
    }

    /// Runs one RAR5 decoder request against empty borrowed streams.
    private static long decodeRar5(
            Rar5Decoder.Session session,
            int dictionaryPower,
            int dictionaryFraction,
            boolean solid,
            long unpackedSize
    ) throws IOException {
        return session.decode(
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream(),
                dictionaryPower,
                dictionaryFraction,
                false,
                solid,
                unpackedSize
        );
    }
}
