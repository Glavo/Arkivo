// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies RAR4 and RAR5 decoder capability calculations and session lifecycle transitions.
@NotNullByDefault
final class RarDecoderSessionTest {
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
        Rar4Decoder.Session validation = Rar4Decoder.newSession();
        assertThrows(
                NullPointerException.class,
                () -> validation.decode(null, OutputStream.nullOutputStream(), 15, 0L, false)
        );
        assertThrows(
                NullPointerException.class,
                () -> validation.decode(InputStream.nullInputStream(), null, 15, 0L, false)
        );
        assertThrows(IOException.class, () -> decodeRar4(validation, 14, 0L, false));
        assertThrows(IOException.class, () -> decodeRar4(validation, 15, -1L, false));
        validation.release();

        Rar4Decoder.Session successful = Rar4Decoder.newSession();
        assertEquals(0L, decodeRar4(successful, 15, 0L, false));
        assertEquals(0L, decodeRar4(successful, 15, 0L, true));
        assertThrows(IOException.class, () -> decodeRar4(successful, 20, 0L, true));
        successful.release();

        Rar4Decoder.Session failed = Rar4Decoder.newSession();
        assertThrows(IOException.class, () -> decodeRar4(failed, 15, 1L, false));
        assertThrows(IOException.class, () -> decodeRar4(failed, 15, 0L, true));
        failed.release();

        Rar4Decoder.Session invalidated = Rar4Decoder.newSession();
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
        Rar5Decoder.Session validation = Rar5Decoder.newSession();
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

        Rar5Decoder.Session failed = Rar5Decoder.newSession();
        assertThrows(IOException.class, () -> decodeRar5(failed, 0, 0, false, 0L));
        assertThrows(IOException.class, () -> decodeRar5(failed, 0, 0, true, 0L));
        failed.release();

        Rar5Decoder.Session invalidated = Rar5Decoder.newSession();
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
