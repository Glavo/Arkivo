// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.tukaani.xz.ArrayCache;
import org.tukaani.xz.ARMOptions;
import org.tukaani.xz.ARMThumbOptions;
import org.tukaani.xz.DeltaOptions;
import org.tukaani.xz.IA64Options;
import org.tukaani.xz.LZMAInputStream;
import org.tukaani.xz.LZMA2InputStream;
import org.tukaani.xz.PowerPCOptions;
import org.tukaani.xz.SPARCOptions;
import org.tukaani.xz.X86Options;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/// Opens supported coder streams stored in 7z folders.
@NotNullByDefault
final class SevenZipLZMADecoder {
    /// The 7z Copy method ID.
    static final byte[] COPY_METHOD_ID = new byte[]{0x00};

    /// The 7z LZMA method ID.
    static final byte[] LZMA_METHOD_ID = new byte[]{0x03, 0x01, 0x01};

    /// The 7z LZMA2 method ID.
    static final byte[] LZMA2_METHOD_ID = new byte[]{0x21};

    /// The 7z Delta filter method ID.
    static final byte[] DELTA_METHOD_ID = new byte[]{0x03};

    /// The 7z x86 BCJ filter method ID.
    static final byte[] X86_FILTER_METHOD_ID = new byte[]{0x03, 0x03, 0x01, 0x03};

    /// The 7z PowerPC BCJ filter method ID.
    static final byte[] POWERPC_FILTER_METHOD_ID = new byte[]{0x03, 0x03, 0x02, 0x05};

    /// The 7z IA-64 BCJ filter method ID.
    static final byte[] IA64_FILTER_METHOD_ID = new byte[]{0x03, 0x03, 0x04, 0x01};

    /// The 7z ARM BCJ filter method ID.
    static final byte[] ARM_FILTER_METHOD_ID = new byte[]{0x03, 0x03, 0x05, 0x01};

    /// The 7z ARM-Thumb BCJ filter method ID.
    static final byte[] ARM_THUMB_FILTER_METHOD_ID = new byte[]{0x03, 0x03, 0x07, 0x01};

    /// The 7z SPARC BCJ filter method ID.
    static final byte[] SPARC_FILTER_METHOD_ID = new byte[]{0x03, 0x03, 0x08, 0x05};

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

    /// Returns whether the method ID identifies the Delta filter.
    static boolean isDelta(byte[] methodId) {
        return Arrays.equals(methodId, DELTA_METHOD_ID);
    }

    /// Returns whether the method ID identifies the x86 BCJ filter.
    static boolean isX86Filter(byte[] methodId) {
        return Arrays.equals(methodId, X86_FILTER_METHOD_ID);
    }

    /// Returns whether the method ID identifies the PowerPC BCJ filter.
    static boolean isPowerPCFilter(byte[] methodId) {
        return Arrays.equals(methodId, POWERPC_FILTER_METHOD_ID);
    }

    /// Returns whether the method ID identifies the IA-64 BCJ filter.
    static boolean isIA64Filter(byte[] methodId) {
        return Arrays.equals(methodId, IA64_FILTER_METHOD_ID);
    }

    /// Returns whether the method ID identifies the ARM BCJ filter.
    static boolean isARMFilter(byte[] methodId) {
        return Arrays.equals(methodId, ARM_FILTER_METHOD_ID);
    }

    /// Returns whether the method ID identifies the ARM-Thumb BCJ filter.
    static boolean isARMThumbFilter(byte[] methodId) {
        return Arrays.equals(methodId, ARM_THUMB_FILTER_METHOD_ID);
    }

    /// Returns whether the method ID identifies the SPARC BCJ filter.
    static boolean isSPARCFilter(byte[] methodId) {
        return Arrays.equals(methodId, SPARC_FILTER_METHOD_ID);
    }

    /// Returns whether the method ID identifies a supported BCJ filter.
    static boolean isBcjFilter(byte[] methodId) {
        return isX86Filter(methodId)
                || isPowerPCFilter(methodId)
                || isIA64Filter(methodId)
                || isARMFilter(methodId)
                || isARMThumbFilter(methodId)
                || isSPARCFilter(methodId);
    }

    /// Returns whether the method ID identifies a supported decoder.
    static boolean isSupported(byte[] methodId) {
        return isCopy(methodId) || isLZMA(methodId) || isLZMA2(methodId) || isDelta(methodId) || isBcjFilter(methodId);
    }

    /// Opens the decoder pipeline for a 7z folder method.
    static InputStream openFolder(
            InputStream input,
            SevenZipFolderMethod method,
            long finalOutputLimit
    ) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(method, "method");
        InputStream current = input;
        for (int index = 0; index < method.coderCount(); index++) {
            byte[] methodId = method.methodId(index);
            byte[] properties = method.properties(index);
            long outputLimit = index + 1 == method.coderCount() ? finalOutputLimit : method.unpackSize(index);
            if (isCopy(methodId)) {
                continue;
            }
            if (isLZMA(methodId)) {
                current = openLZMA(current, outputLimit, properties);
            } else if (isLZMA2(methodId)) {
                current = openLZMA2(current, properties);
            } else if (isDelta(methodId)) {
                current = openDelta(current, properties);
            } else if (isBcjFilter(methodId)) {
                current = openBcjFilter(current, methodId, properties);
            } else {
                throw new UnsupportedOperationException("Unsupported 7z coder method: " + Arrays.toString(methodId));
            }
        }
        return current;
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

    /// Opens a raw Delta filter decoder for 7z coder properties.
    static InputStream openDelta(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(properties, "properties");
        if (properties.length != 1) {
            throw new IOException("7z Delta coder properties must contain one byte");
        }
        int distance = Byte.toUnsignedInt(properties[0]) + 1;
        return new DeltaOptions(distance).getInputStream(input, ArrayCache.getDummyCache());
    }

    /// Opens a raw BCJ filter decoder for 7z coder properties.
    static InputStream openBcjFilter(InputStream input, byte[] methodId, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(methodId, "methodId");
        if (isX86Filter(methodId)) {
            return openX86Filter(input, properties);
        }
        if (isPowerPCFilter(methodId)) {
            return openPowerPCFilter(input, properties);
        }
        if (isIA64Filter(methodId)) {
            return openIA64Filter(input, properties);
        }
        if (isARMFilter(methodId)) {
            return openARMFilter(input, properties);
        }
        if (isARMThumbFilter(methodId)) {
            return openARMThumbFilter(input, properties);
        }
        if (isSPARCFilter(methodId)) {
            return openSPARCFilter(input, properties);
        }
        throw new UnsupportedOperationException("Unsupported 7z coder method: " + Arrays.toString(methodId));
    }

    /// Opens a raw x86 BCJ filter decoder for 7z coder properties.
    static InputStream openX86Filter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        X86Options options = new X86Options();
        options.setStartOffset(bcjStartOffset(properties, "7z x86 BCJ coder"));
        return options.getInputStream(input, ArrayCache.getDummyCache());
    }

    /// Opens a raw PowerPC BCJ filter decoder for 7z coder properties.
    static InputStream openPowerPCFilter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        PowerPCOptions options = new PowerPCOptions();
        options.setStartOffset(bcjStartOffset(properties, "7z PowerPC BCJ coder"));
        return options.getInputStream(input, ArrayCache.getDummyCache());
    }

    /// Opens a raw IA-64 BCJ filter decoder for 7z coder properties.
    static InputStream openIA64Filter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        IA64Options options = new IA64Options();
        options.setStartOffset(bcjStartOffset(properties, "7z IA-64 BCJ coder"));
        return options.getInputStream(input, ArrayCache.getDummyCache());
    }

    /// Opens a raw ARM BCJ filter decoder for 7z coder properties.
    static InputStream openARMFilter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        ARMOptions options = new ARMOptions();
        options.setStartOffset(bcjStartOffset(properties, "7z ARM BCJ coder"));
        return options.getInputStream(input, ArrayCache.getDummyCache());
    }

    /// Opens a raw ARM-Thumb BCJ filter decoder for 7z coder properties.
    static InputStream openARMThumbFilter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        ARMThumbOptions options = new ARMThumbOptions();
        options.setStartOffset(bcjStartOffset(properties, "7z ARM-Thumb BCJ coder"));
        return options.getInputStream(input, ArrayCache.getDummyCache());
    }

    /// Opens a raw SPARC BCJ filter decoder for 7z coder properties.
    static InputStream openSPARCFilter(InputStream input, byte[] properties) throws IOException {
        Objects.requireNonNull(input, "input");
        SPARCOptions options = new SPARCOptions();
        options.setStartOffset(bcjStartOffset(properties, "7z SPARC BCJ coder"));
        return options.getInputStream(input, ArrayCache.getDummyCache());
    }

    /// Reads BCJ filter properties as an optional little-endian start offset.
    private static int bcjStartOffset(byte[] properties, String description) throws IOException {
        Objects.requireNonNull(properties, "properties");
        if (properties.length == 0) {
            return 0;
        }
        if (properties.length != 4) {
            throw new IOException(description + " properties must contain zero or four bytes");
        }
        return ByteBuffer.wrap(properties).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
