// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.tukaani.xz.LZMAInputStream;
import org.tukaani.xz.LZMA2InputStream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/// Opens raw LZMA streams stored in 7z folders.
@NotNullByDefault
final class SevenZipLZMADecoder {
    /// The 7z Copy method ID.
    static final byte[] COPY_METHOD_ID = new byte[]{0x00};

    /// The 7z LZMA method ID.
    static final byte[] LZMA_METHOD_ID = new byte[]{0x03, 0x01, 0x01};

    /// The 7z LZMA2 method ID.
    static final byte[] LZMA2_METHOD_ID = new byte[]{0x21};

    /// Creates no instances.
    private SevenZipLZMADecoder() {
    }

    /// Returns whether the method ID identifies the Copy method.
    static boolean isCopy(byte[] methodId) {
        return Arrays.equals(methodId, COPY_METHOD_ID);
    }

    /// Returns whether the method ID identifies the LZMA method.
    static boolean isLZMA(byte[] methodId) {
        return Arrays.equals(methodId, LZMA_METHOD_ID);
    }

    /// Returns whether the method ID identifies the LZMA2 method.
    static boolean isLZMA2(byte[] methodId) {
        return Arrays.equals(methodId, LZMA2_METHOD_ID);
    }

    /// Opens a raw LZMA decoder for 7z coder properties.
    static InputStream openLZMA(InputStream input, long uncompressedSize, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(properties, "properties");
        if (properties.length != 5) {
            throw new IOException("7z LZMA coder properties must contain five bytes");
        }
        int dictionarySize = ByteBuffer.wrap(properties, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return new LZMAInputStream(input, uncompressedSize, properties[0], dictionarySize);
    }

    /// Opens a raw LZMA2 decoder for 7z coder properties.
    static InputStream openLZMA2(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(properties, "properties");
        if (properties.length != 1) {
            throw new IOException("7z LZMA2 coder properties must contain one byte");
        }
        int property = Byte.toUnsignedInt(properties[0]);
        if (property > 37) {
            throw new IOException("Unsupported 7z LZMA2 dictionary property");
        }
        int dictionarySize = (2 | (property & 1)) << ((property >>> 1) + 11);
        return new LZMA2InputStream(input, dictionarySize);
    }
}
