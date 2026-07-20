// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.glavo.arkivo.checksum.ChecksumValue;
import org.glavo.arkivo.checksum.Checksums;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

/// Calculates one XZ block integrity-check type.
@NotNullByDefault
final class XZCheck {
    /// The selected XZ check type.
    private final int type;

    /// The encoded check size.
    private final int size;

    /// The active checksum state, or `null` for the None check.
    private final @Nullable ChecksumAccumulator accumulator;

    /// Creates the selected check implementation.
    private XZCheck(
            int type,
            int size,
            @Nullable ChecksumAccumulator accumulator
    ) {
        this.type = type;
        this.size = size;
        this.accumulator = accumulator;
    }

    /// Creates a supported XZ integrity check.
    static XZCheck create(int type) throws IOException {
        return switch (type) {
            case XZSupport.CHECK_NONE -> new XZCheck(type, 0, null);
            case XZSupport.CHECK_CRC32 -> new XZCheck(type, Integer.BYTES, Checksums.CRC32.newAccumulator());
            case XZSupport.CHECK_CRC64 ->
                    new XZCheck(type, Long.BYTES, CRC64XZAlgorithm.INSTANCE.newAccumulator());
            case XZSupport.CHECK_SHA256 ->
                    new XZCheck(type, Checksums.SHA256.checksumSize(), Checksums.SHA256.newAccumulator());
            default -> throw new IOException("Unsupported XZ integrity check type: " + type);
        };
    }

    /// Returns the format-defined field size for any valid XZ Check ID.
    static int sizeOf(int type) throws IOException {
        return switch (type) {
            case 0 -> 0;
            case 1, 2, 3 -> 4;
            case 4, 5, 6 -> 8;
            case 7, 8, 9 -> 16;
            case 10, 11, 12 -> 32;
            case 13, 14, 15 -> 64;
            default -> throw new IOException("Invalid XZ integrity check type: " + type);
        };
    }

    /// Returns the encoded check size.
    int size() {
        return size;
    }

    /// Updates this check with uncompressed bytes.
    void update(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        ChecksumAccumulator selected = accumulator;
        if (selected != null) {
            selected.update(bytes, offset, length);
        }
    }

    /// Finishes and resets this check, returning its XZ byte representation.
    byte[] finish() {
        byte[] result = new byte[size];
        ChecksumAccumulator selected = accumulator;
        if (selected == null) {
            return result;
        }
        switch (type) {
            case XZSupport.CHECK_CRC32, XZSupport.CHECK_CRC64 -> XZSupport.putLittleEndian(
                    result,
                    0,
                    ((ChecksumAccumulator.UpTo64Bits) selected).finishLong(),
                    size
            );
            case XZSupport.CHECK_SHA256 -> {
                ChecksumValue value = selected.finish();
                byte[] digest = value.toByteArray();
                System.arraycopy(digest, 0, result, 0, result.length);
            }
            default -> throw new AssertionError(type);
        }
        selected.reset();
        return result;
    }
}
