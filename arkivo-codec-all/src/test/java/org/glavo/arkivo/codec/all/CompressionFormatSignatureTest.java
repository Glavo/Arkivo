// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.bzip2.BZip2Format;
import org.glavo.arkivo.codec.compress.UnixCompressFormat;
import org.glavo.arkivo.codec.deflate.Deflate64Format;
import org.glavo.arkivo.codec.deflate.DeflateFormat;
import org.glavo.arkivo.codec.deflate.GzipFormat;
import org.glavo.arkivo.codec.deflate.ZlibFormat;
import org.glavo.arkivo.codec.lz4.LZ4BlockFormat;
import org.glavo.arkivo.codec.lz4.LZ4Format;
import org.glavo.arkivo.codec.lzma.LZMA2Format;
import org.glavo.arkivo.codec.lzma.LZMAFormat;
import org.glavo.arkivo.codec.lzma.RawLZMAFormat;
import org.glavo.arkivo.codec.lzip.LzipFormat;
import org.glavo.arkivo.codec.ppmd.PPMdFormat;
import org.glavo.arkivo.codec.xz.XZFormat;
import org.glavo.arkivo.codec.zstd.ZstdFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies exact compression signature boundaries and read-only buffer behavior for official formats.
@NotNullByDefault
final class CompressionFormatSignatureTest {
    /// Verifies BZip2 matching accepts only the exact marker and declared block-size range.
    @Test
    void recognizesBZip2HeaderBoundaries() {
        BZip2Format format = BZip2Format.instance();
        for (int blockSize = '1'; blockSize <= '9'; blockSize++) {
            assertMatches(format, new byte[]{'B', 'Z', 'h', (byte) blockSize}, true);
        }

        assertMatches(format, new byte[]{'B', 'Z', 'h', '0'}, false);
        assertMatches(format, new byte[]{'B', 'Z', 'h', ':'}, false);
        assertMatches(format, new byte[]{'C', 'Z', 'h', '1'}, false);
        assertMatches(format, new byte[]{'B', 'Y', 'h', '1'}, false);
        assertMatches(format, new byte[]{'B', 'Z', 'i', '1'}, false);
        assertTruncatedPrefixesRejected(format, new byte[]{'B', 'Z', 'h', '1'});
    }

    /// Verifies fixed signatures use all and only their documented magic bytes.
    @Test
    void recognizesExactFixedSignatures() {
        assertExactSignature(UnixCompressFormat.instance(), new byte[]{0x1f, (byte) 0x9d});
        assertExactSignature(GzipFormat.instance(), new byte[]{0x1f, (byte) 0x8b});
        assertExactSignature(LZ4Format.instance(), new byte[]{0x04, 0x22, 0x4d, 0x18});
        assertExactSignature(
                LzipFormat.instance(),
                "LZIP".getBytes(StandardCharsets.US_ASCII)
        );
        assertExactSignature(
                XZFormat.instance(),
                new byte[]{(byte) 0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00}
        );
        assertExactSignature(
                ZstdFormat.instance(),
                new byte[]{0x28, (byte) 0xb5, 0x2f, (byte) 0xfd}
        );
    }

    /// Verifies every valid zlib window code while rejecting invalid methods, windows, and header checksums.
    @Test
    void validatesCompleteZlibHeader() {
        ZlibFormat format = ZlibFormat.instance();
        for (int windowCode = 0; windowCode <= 7; windowCode++) {
            assertMatches(format, zlibHeader((windowCode << 4) | 8), true);
        }

        assertMatches(format, zlibHeader(0x79), false);
        assertMatches(format, zlibHeader(0x88), false);
        assertMatches(format, new byte[]{0x78, 0x00}, false);
        assertTruncatedPrefixesRejected(format, new byte[]{0x78, 0x01});
    }

    /// Verifies LZ4 legacy and skippable magic values at both inclusive range boundaries.
    @Test
    void recognizesLZ4MagicFamilies() {
        LZ4Format format = LZ4Format.instance();
        assertMatches(format, new byte[]{0x02, 0x21, 0x4c, 0x18}, true);
        assertMatches(format, new byte[]{0x50, 0x2a, 0x4d, 0x18}, true);
        assertMatches(format, new byte[]{0x5f, 0x2a, 0x4d, 0x18}, true);
        assertMatches(format, new byte[]{0x4f, 0x2a, 0x4d, 0x18}, false);
        assertMatches(format, new byte[]{0x60, 0x2a, 0x4d, 0x18}, false);

        assertFalse(LZ4Format.isSkippableFrameMagic(LZ4Format.FIRST_SKIPPABLE_FRAME_MAGIC - 1));
        assertTrue(LZ4Format.isSkippableFrameMagic(LZ4Format.FIRST_SKIPPABLE_FRAME_MAGIC));
        assertTrue(LZ4Format.isSkippableFrameMagic(LZ4Format.LAST_SKIPPABLE_FRAME_MAGIC));
        assertFalse(LZ4Format.isSkippableFrameMagic(LZ4Format.LAST_SKIPPABLE_FRAME_MAGIC + 1));
    }

    /// Verifies Zstandard matching accepts the complete skippable-magic range and rejects adjacent values.
    @Test
    void recognizesZstandardSkippableFrames() {
        ZstdFormat format = ZstdFormat.instance();
        assertMatches(format, new byte[]{0x50, 0x2a, 0x4d, 0x18}, true);
        assertMatches(format, new byte[]{0x5f, 0x2a, 0x4d, 0x18}, true);
        assertMatches(format, new byte[]{0x4f, 0x2a, 0x4d, 0x18}, false);
        assertMatches(format, new byte[]{0x60, 0x2a, 0x4d, 0x18}, false);
    }

    /// Verifies formats without reliable signatures never claim arbitrary prefixes.
    @Test
    void leavesHeaderlessFormatsExplicitOnly() {
        List<CompressionFormat> formats = List.of(
                DeflateFormat.instance(),
                Deflate64Format.instance(),
                LZ4BlockFormat.instance(),
                LZMAFormat.instance(),
                RawLZMAFormat.instance(),
                LZMA2Format.instance(),
                PPMdFormat.instance()
        );

        for (CompressionFormat format : formats) {
            assertEquals(0, format.probeSize(), format.name());
            assertMatches(format, new byte[]{0x28, (byte) 0xb5, 0x2f, (byte) 0xfd}, false);
            assertMatches(format, new byte[0], false);
        }
    }

    /// Verifies every official descriptor rejects a null prefix.
    @Test
    void rejectsNullPrefixes() {
        for (CompressionFormat format : CompressionFormats.installed()) {
            assertThrows(
                    NullPointerException.class,
                    () -> format.matches((ByteBuffer) null),
                    format.name()
            );
        }
    }

    /// Verifies an exact signature, all shorter prefixes, and one corruption at every byte position.
    private static void assertExactSignature(
            CompressionFormat format,
            byte @Unmodifiable [] signature
    ) {
        assertMatches(format, signature, true);
        assertTruncatedPrefixesRejected(format, signature);
        for (int index = 0; index < signature.length; index++) {
            byte[] corrupted = signature.clone();
            corrupted[index] ^= (byte) 0x80;
            assertMatches(format, corrupted, false);
        }
    }

    /// Verifies every proper prefix of a signature is rejected.
    private static void assertTruncatedPrefixesRejected(
            CompressionFormat format,
            byte @Unmodifiable [] signature
    ) {
        for (int length = 0; length < signature.length; length++) {
            assertMatches(format, Arrays.copyOf(signature, length), false);
        }
    }

    /// Invokes one matcher through a guarded direct read-only view and verifies all caller-visible state is retained.
    private static void assertMatches(
            CompressionFormat format,
            byte @Unmodifiable [] prefix,
            boolean expected
    ) {
        ByteBuffer storage = ByteBuffer.allocateDirect(prefix.length + 7);
        storage.position(3);
        storage.put(prefix);

        ByteBuffer view = storage.asReadOnlyBuffer();
        view.position(3);
        view.limit(3 + prefix.length);
        view.order(ByteOrder.LITTLE_ENDIAN);
        view.mark();

        String context = format.name() + ' ' + Arrays.toString(prefix);
        assertEquals(expected, format.matches(view), context);
        assertEquals(3, view.position(), context);
        assertEquals(3 + prefix.length, view.limit(), context);
        assertEquals(ByteOrder.LITTLE_ENDIAN, view.order(), context);
        view.reset();
        assertEquals(3, view.position(), context + " mark");
    }

    /// Creates a zlib header with a valid FCHECK value for the supplied CMF byte.
    private static byte @Unmodifiable [] zlibHeader(int compressionMethodAndFlags) {
        int flags = Math.floorMod(-(compressionMethodAndFlags << Byte.SIZE), 31);
        return new byte[]{(byte) compressionMethodAndFlags, (byte) flags};
    }
}
