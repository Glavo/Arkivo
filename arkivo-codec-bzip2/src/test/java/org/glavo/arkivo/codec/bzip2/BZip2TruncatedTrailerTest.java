// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies the COMPRESS-253 truncated BZip2 trailer behavior ported from the Python test suite.
@NotNullByDefault
final class BZip2TruncatedTrailerTest {
    /// The decoded payload adapted from Apache Commons Compress's `PythonTruncatedBzip2Test`.
    ///
    /// Copyright 2002-2025 The Apache Software Foundation; licensed under Apache-2.0.
    private static final byte @Unmodifiable [] TEXT = """
            root:x:0:0:root:/root:/bin/bash
            bin:x:1:1:bin:/bin:
            daemon:x:2:2:daemon:/sbin:
            adm:x:3:4:adm:/var/adm:
            lp:x:4:7:lp:/var/spool/lpd:
            sync:x:5:0:sync:/sbin:/bin/sync
            shutdown:x:6:0:shutdown:/sbin:/sbin/shutdown
            halt:x:7:0:halt:/sbin:/sbin/halt
            mail:x:8:12:mail:/var/spool/mail:
            news:x:9:13:news:/var/spool/news:
            uucp:x:10:14:uucp:/var/spool/uucp:
            operator:x:11:0:operator:/root:
            games:x:12:100:games:/usr/games:
            gopher:x:13:30:gopher:/usr/lib/gopher-data:
            ftp:x:14:50:FTP User:/var/ftp:/bin/bash
            nobody:x:65534:65534:Nobody:/home:
            postfix:x:100:101:postfix:/var/spool/postfix:
            niemeyer:x:500:500::/home/niemeyer:/bin/bash
            postgres:x:101:102:PostgreSQL Server:/var/lib/pgsql:/bin/bash
            mysql:x:102:103:MySQL server:/var/lib/mysql:/bin/bash
            www:x:103:104::/var/www:/bin/false
            """.getBytes(StandardCharsets.UTF_8);

    /// Verifies an exact-size read returns the payload before the missing trailer is reported.
    @Test
    void reportsMissingTrailerAfterExactPayloadRead() throws IOException {
        byte @Unmodifiable [] truncated = truncateTrailer(compress(), 10);
        try (InputStream input = new BZip2Codec().newInputStream(new ByteArrayInputStream(truncated))) {
            byte[] decoded = new byte[TEXT.length];
            assertEquals(TEXT.length, input.read(decoded));
            assertArrayEquals(TEXT, decoded);
            assertThrows(IOException.class, input::read);
        }
    }

    /// Verifies an oversized initial read may return available output before reporting the missing end marker.
    @Test
    void reportsMissingTrailerAfterOversizedReadReturnsAvailableOutput() throws IOException {
        byte @Unmodifiable [] truncated = truncateTrailer(compress(), 10);
        try (InputStream input = new BZip2Codec().newInputStream(new ByteArrayInputStream(truncated))) {
            byte[] decoded = new byte[8192];
            assertEquals(TEXT.length, input.read(decoded));
            assertArrayEquals(TEXT, Arrays.copyOf(decoded, TEXT.length));
            assertThrows(IOException.class, input::read);
        }
    }

    /// Verifies every selected trailer truncation fails across small and large caller buffers.
    @ParameterizedTest(name = "removed={0}, buffer={1}")
    @MethodSource("truncationCases")
    void rejectsTruncatedTrailer(
            int removedBytes,
            int bufferSize,
            byte @Unmodifiable [] truncated
    ) throws IOException {
        try (InputStream input = new BZip2Codec().newInputStream(new ByteArrayInputStream(truncated))) {
            byte[] buffer = new byte[bufferSize];
            assertThrows(IOException.class, () -> {
                while (true) {
                    int read = input.read(buffer);
                    if (read < 0) {
                        return;
                    }
                    if (read == 0) {
                        throw new IOException("BZip2 stream made no progress");
                    }
                }
            }, "removed trailer bytes: " + removedBytes + ", buffer size: " + bufferSize);
        }
    }

    /// Supplies trailer truncation depths and caller buffer sizes around output boundaries.
    private static Stream<Arguments> truncationCases() throws IOException {
        byte @Unmodifiable [] compressed = compress();
        return Stream.of(1, 4, 6, 10).flatMap(removedBytes ->
                Stream.of(1, 7, 257, 8192).map(bufferSize -> Arguments.of(
                        removedBytes,
                        bufferSize,
                        truncateTrailer(compressed, removedBytes)
                ))
        );
    }

    /// Compresses the regression payload into one complete BZip2 member.
    private static byte @Unmodifiable [] compress() throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (OutputStream output = new BZip2Codec().newOutputStream(compressed)) {
            output.write(TEXT);
        }
        return compressed.toByteArray();
    }

    /// Removes the requested number of bytes from the member trailer.
    private static byte @Unmodifiable [] truncateTrailer(
            byte @Unmodifiable [] compressed,
            int removedBytes
    ) {
        if (removedBytes <= 0 || removedBytes > 10) {
            throw new IllegalArgumentException("removedBytes must be between 1 and 10");
        }
        return Arrays.copyOf(compressed, compressed.length - removedBytes);
    }
}
