// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies decoded archive size enforcement, probe boundaries, and channel ownership.
@NotNullByDefault
final class ArchiveSizeLimitChannelTest {
    /// Verifies a read stops at the first byte beyond the configured maximum.
    @Test
    void readsOnlyOneProbeByteBeyondLimit() throws IOException {
        ReadableByteChannel delegate = Channels.newChannel(new ByteArrayInputStream(
                new byte[]{10, 11, 12, 13, 14, 15, 16}
        ));
        ReadableByteChannel limited = ArchiveSizeLimitChannel.wrap(delegate, 3L);
        ByteBuffer target = ByteBuffer.allocate(12);
        target.position(2);
        target.limit(10);

        ArkivoReadLimitException failure = assertThrows(
                ArkivoReadLimitException.class,
                () -> limited.read(target)
        );

        assertEquals(ArkivoReadLimitKind.DECODED_ARCHIVE_SIZE, failure.kind());
        assertEquals(3L, failure.maximum());
        assertEquals(4L, failure.actual());
        assertEquals(6, target.position());
        assertEquals(10, target.limit());
        assertEquals(10, target.get(2));
        assertEquals(13, target.get(5));
        assertSame(failure, assertThrows(ArkivoReadLimitException.class, () -> limited.read(target)));

        limited.close();
        assertFalse(delegate.isOpen());
    }

    /// Verifies exact-limit reads succeed and an empty target does not trigger a probe.
    @Test
    void permitsExactLimitAndDefersProbeForEmptyTarget() throws IOException {
        ReadableByteChannel delegate = Channels.newChannel(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));
        try (ReadableByteChannel limited = ArchiveSizeLimitChannel.wrap(delegate, 3L)) {
            ByteBuffer exact = ByteBuffer.allocate(3);
            assertEquals(3, limited.read(exact));
            assertEquals(0, limited.read(ByteBuffer.allocate(0)));

            ByteBuffer probe = ByteBuffer.allocate(8);
            ArkivoReadLimitException failure = assertThrows(
                    ArkivoReadLimitException.class,
                    () -> limited.read(probe)
            );
            assertEquals(4L, failure.actual());
            assertEquals(1, probe.position());
            assertEquals(8, probe.limit());
        }
    }

    /// Verifies disabled limits preserve channel identity and enabled wrappers preserve interruptibility.
    @Test
    void preservesIdentityAndInterruptibility() throws IOException {
        ReadableByteChannel delegate = Channels.newChannel(new ByteArrayInputStream(new byte[]{1}));

        assertSame(delegate, ArchiveSizeLimitChannel.wrap(delegate, -1L));

        ReadableByteChannel limited = ArchiveSizeLimitChannel.wrap(delegate, 0L);
        assertInstanceOf(InterruptibleChannel.class, delegate);
        assertInstanceOf(InterruptibleChannel.class, limited);
        limited.close();
        assertFalse(delegate.isOpen());
    }

    /// Verifies the concrete limiter rejects a negative maximum.
    @Test
    void rejectsNegativeMaximum() throws IOException {
        ReadableByteChannel delegate = Channels.newChannel(new ByteArrayInputStream(new byte[0]));
        try (delegate) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ArchiveSizeLimitChannel(delegate, -1L)
            );
        }
    }
}
