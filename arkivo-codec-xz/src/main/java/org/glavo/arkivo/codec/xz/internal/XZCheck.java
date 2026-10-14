// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.zip.CRC32;

/// Calculates one XZ block integrity-check type.
@NotNullByDefault
final class XZCheck {
    /// The encoded check size.
    private final int size;

    /// The CRC-32 state, or `null` for other check types.
    private final @Nullable CRC32 checksum;

    /// The CRC-64/XZ state, or `null` for other check types.
    private final @Nullable CRC64XZ crc64;

    /// The digest state, or `null` for other check types.
    private final @Nullable MessageDigest digest;

    /// Creates the selected check implementation.
    private XZCheck(
            int size,
            @Nullable CRC32 checksum,
            @Nullable CRC64XZ crc64,
            @Nullable MessageDigest digest
    ) {
        this.size = size;
        this.checksum = checksum;
        this.crc64 = crc64;
        this.digest = digest;
    }

    /// Creates a supported XZ integrity check.
    static XZCheck create(int type) throws IOException {
        return switch (type) {
            case XZSupport.CHECK_NONE -> new XZCheck(0, null, null, null);
            case XZSupport.CHECK_CRC32 -> new XZCheck(Integer.BYTES, new CRC32(), null, null);
            case XZSupport.CHECK_CRC64 ->
                    new XZCheck(Long.BYTES, null, new CRC64XZ(), null);
            case XZSupport.CHECK_SHA256 -> {
                try {
                    yield new XZCheck(32, null, null, MessageDigest.getInstance("SHA-256"));
                } catch (NoSuchAlgorithmException exception) {
                    throw new AssertionError("SHA-256 is unavailable", exception);
                }
            }
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
        if (checksum != null) {
            checksum.update(bytes, offset, length);
        } else if (crc64 != null) {
            crc64.update(bytes, offset, length);
        } else if (digest != null) {
            digest.update(bytes, offset, length);
        }
    }

    /// Finishes and resets this check, returning its XZ byte representation.
    byte[] finish() {
        if (digest != null) {
            return digest.digest();
        }
        byte[] result = new byte[size];
        if (checksum != null) {
            XZSupport.putLittleEndian(result, 0, checksum.getValue(), size);
            checksum.reset();
        } else if (crc64 != null) {
            XZSupport.putLittleEndian(result, 0, crc64.finishLong(), size);
            crc64.reset();
        }
        return result;
    }
}
