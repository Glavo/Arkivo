// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Arrays;
import java.util.Objects;

/// Identifies a supported 7z coder method.
@NotNullByDefault
public enum SevenZipCoderMethod {
    /// Copies bytes without compression.
    COPY(0x00),

    /// Decodes LZMA-compressed bytes.
    LZMA(0x03, 0x01, 0x01),

    /// Decodes LZMA2-compressed bytes.
    LZMA2(0x21),

    /// Decodes BZip2-compressed bytes.
    BZIP2(0x04, 0x02, 0x02),

    /// Decodes raw Deflate-compressed bytes.
    DEFLATE(0x04, 0x01, 0x08),

    /// Decodes Deflate64-compressed bytes.
    DEFLATE64(0x04, 0x01, 0x09),

    /// Decodes Zstandard-compressed bytes.
    ZSTANDARD(0x04, 0xf7, 0x11, 0x01),

    /// Decodes PPMd7-compressed bytes.
    PPMD(0x03, 0x04, 0x01),

    /// Decrypts 7z AES-256/SHA-256 content.
    AES(0x06, 0xf1, 0x07, 0x01),

    /// Reverses a Delta byte-distance filter.
    DELTA(0x03),

    /// Reverses an x86 branch filter.
    BCJ_X86(0x03, 0x03, 0x01, 0x03),

    /// Merges the four side streams of an x86 BCJ2 graph.
    BCJ2(0x03, 0x03, 0x01, 0x1b),

    /// Reverses a PowerPC branch filter.
    BCJ_POWERPC(0x03, 0x03, 0x02, 0x05),

    /// Reverses an IA-64 branch filter.
    BCJ_IA64(0x03, 0x03, 0x04, 0x01),

    /// Reverses an ARM branch filter.
    BCJ_ARM(0x03, 0x03, 0x05, 0x01),

    /// Reverses an ARM-Thumb branch filter.
    BCJ_ARM_THUMB(0x03, 0x03, 0x07, 0x01),

    /// Reverses a SPARC branch filter.
    BCJ_SPARC(0x03, 0x03, 0x08, 0x05),

    /// Reverses an ARM64 branch filter.
    BCJ_ARM64(0x0a),

    /// Reverses a RISC-V branch filter.
    BCJ_RISCV(0x0b);

    /// The raw 7z method identifier.
    private final byte @Unmodifiable [] methodId;

    /// Creates a coder method from unsigned identifier bytes.
    SevenZipCoderMethod(int... methodId) {
        this.methodId = new byte[methodId.length];
        for (int index = 0; index < methodId.length; index++) {
            this.methodId[index] = (byte) methodId[index];
        }
    }

    /// Returns the supported method matching a raw 7z identifier.
    ///
    /// @param methodId the raw identifier bytes
    /// @return the matching supported method
    /// @throws IllegalArgumentException if the identifier is not recognized
    public static SevenZipCoderMethod fromMethodId(byte[] methodId) {
        Objects.requireNonNull(methodId, "methodId");
        for (SevenZipCoderMethod method : values()) {
            if (Arrays.equals(method.methodId, methodId)) {
                return method;
            }
        }
        throw new IllegalArgumentException("Unsupported 7z coder method ID: " + Arrays.toString(methodId));
    }

    /// Returns a copy of the raw 7z method identifier.
    ///
    /// @return a new array containing the method identifier bytes
    public byte[] methodId() {
        return methodId.clone();
    }
}
