// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.xz.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;

/// Calculates one XZ block integrity-check type.
@NotNullByDefault
final class XzCheck {
    /// The selected XZ check type.
    private final int type;

    /// The encoded check size.
    private final int size;

    /// The CRC-32 state when type 1 is selected.
    private final @Nullable CRC32 crc32;

    /// The CRC-64 state when type 4 is selected.
    private final @Nullable CRC64 crc64;

    /// The SHA-256 state when type 10 is selected.
    private final @Nullable MessageDigest sha256;

    /// Creates the selected check implementation.
    private XzCheck(
            int type,
            int size,
            @Nullable CRC32 crc32,
            @Nullable CRC64 crc64,
            @Nullable MessageDigest sha256
    ) {
        this.type = type;
        this.size = size;
        this.crc32 = crc32;
        this.crc64 = crc64;
        this.sha256 = sha256;
    }

    /// Creates a supported XZ integrity check.
    static XzCheck create(int type) throws IOException {
        return switch (type) {
            case XzSupport.CHECK_NONE -> new XzCheck(type, 0, null, null, null);
            case XzSupport.CHECK_CRC32 -> new XzCheck(type, Integer.BYTES, new CRC32(), null, null);
            case XzSupport.CHECK_CRC64 -> new XzCheck(type, Long.BYTES, null, new CRC64(), null);
            case XzSupport.CHECK_SHA256 -> new XzCheck(type, 32, null, null, sha256());
            default -> throw new IOException("Unsupported XZ integrity check type: " + type);
        };
    }

    /// Returns the encoded check size.
    int size() {
        return size;
    }

    /// Updates this check with uncompressed bytes.
    void update(byte[] bytes, int offset, int length) {
        switch (type) {
            case XzSupport.CHECK_NONE -> {
            }
            case XzSupport.CHECK_CRC32 -> java.util.Objects.requireNonNull(crc32).update(bytes, offset, length);
            case XzSupport.CHECK_CRC64 -> java.util.Objects.requireNonNull(crc64).update(bytes, offset, length);
            case XzSupport.CHECK_SHA256 -> java.util.Objects.requireNonNull(sha256).update(bytes, offset, length);
            default -> throw new AssertionError(type);
        }
    }

    /// Finishes and resets this check, returning its XZ byte representation.
    byte[] finish() {
        byte[] result = new byte[size];
        switch (type) {
            case XzSupport.CHECK_NONE -> {
            }
            case XzSupport.CHECK_CRC32 -> {
                CRC32 state = java.util.Objects.requireNonNull(crc32);
                XzSupport.putLittleEndian(result, 0, state.getValue(), Integer.BYTES);
                state.reset();
            }
            case XzSupport.CHECK_CRC64 -> {
                CRC64 state = java.util.Objects.requireNonNull(crc64);
                XzSupport.putLittleEndian(result, 0, state.value(), Long.BYTES);
                state.reset();
            }
            case XzSupport.CHECK_SHA256 -> {
                byte[] digest = java.util.Objects.requireNonNull(sha256).digest();
                System.arraycopy(digest, 0, result, 0, result.length);
            }
            default -> throw new AssertionError(type);
        }
        return result;
    }

    /// Returns the mandatory SHA-256 implementation.
    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }
}
