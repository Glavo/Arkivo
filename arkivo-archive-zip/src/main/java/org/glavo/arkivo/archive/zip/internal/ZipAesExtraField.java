// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

import static org.glavo.arkivo.archive.zip.internal.ZipLittleEndian.readUnsignedShort;

/// Stores metadata from the WinZip AES extra field.
@NotNullByDefault
final class ZipAesExtraField {
    /// The vendor version written for AE-1 entries.
    private static final int AE_1_VENDOR_VERSION = 1;

    /// The vendor version written for AE-2 entries.
    private static final int AE_2_VENDOR_VERSION = 2;

    /// The minimum WinZip AES extra field data size.
    private static final int MIN_DATA_SIZE = 7;

    /// The vendor ID used by WinZip AES entries.
    private static final int AES_VENDOR_ID = 'A' | ('E' << 8);

    /// The AES-128 strength code.
    private static final int AES_128_STRENGTH = 1;

    /// The AES-192 strength code.
    private static final int AES_192_STRENGTH = 2;

    /// The AES-256 strength code.
    private static final int AES_256_STRENGTH = 3;

    /// The length of the WinZip AES password verifier.
    private static final int PASSWORD_VERIFIER_SIZE = 2;

    /// The length of the WinZip AES authentication code.
    static final int AUTHENTICATION_CODE_SIZE = 10;

    /// The vendor version field.
    private final int vendorVersion;

    /// The AES encryption method.
    private final ZipEncryption encryption;

    /// The encryption strength code.
    private final int strength;

    /// The actual ZIP compression method.
    private final int compressionMethod;

    /// Creates parsed WinZip AES metadata.
    private ZipAesExtraField(int vendorVersion, ZipEncryption encryption, int strength, int compressionMethod) {
        this.vendorVersion = vendorVersion;
        this.encryption = encryption;
        this.strength = strength;
        this.compressionMethod = compressionMethod;
    }

    /// Reads the first valid WinZip AES extra field, or returns `null` when no valid field is present.
    static @Nullable ZipAesExtraField read(byte[] extraData) {
        try {
            return readValidated(extraData);
        } catch (IOException ignored) {
            return null;
        }
    }

    /// Reads the first valid WinZip AES extra field after validating extra field record boundaries.
    static @Nullable ZipAesExtraField readValidated(byte[] extraData) throws IOException {
        int offset = 0;
        while (offset < extraData.length) {
            ZipExtraFields.Field field = ZipExtraFields.read(extraData, offset);
            if (field.id() == ZipConstants.WINZIP_AES_EXTRA_FIELD_ID) {
                ZipAesExtraField aes = readData(extraData, field.dataOffset(), field.dataSize());
                if (aes != null) {
                    return aes;
                }
            }
            offset = field.nextOffset();
        }
        return null;
    }

    /// Creates WinZip AES metadata for a requested encryption method.
    static ZipAesExtraField forEncryption(ZipEncryption encryption, int compressionMethod) {
        Objects.requireNonNull(encryption, "encryption");
        int strength;
        if (encryption.equals(ZipEncryption.winZipAes128())) {
            strength = AES_128_STRENGTH;
        } else if (encryption.equals(ZipEncryption.winZipAes192())) {
            strength = AES_192_STRENGTH;
        } else if (encryption.equals(ZipEncryption.winZipAes256())) {
            strength = AES_256_STRENGTH;
        } else {
            throw new IllegalArgumentException("encryption is not a WinZip AES method: " + encryption);
        }
        return new ZipAesExtraField(AE_1_VENDOR_VERSION, encryption, strength, compressionMethod);
    }

    /// Returns whether the encryption method is a WinZip AES method.
    static boolean isAesEncryption(ZipEncryption encryption) {
        Objects.requireNonNull(encryption, "encryption");
        return encryption.equals(ZipEncryption.winZipAes128())
                || encryption.equals(ZipEncryption.winZipAes192())
                || encryption.equals(ZipEncryption.winZipAes256());
    }

    /// Appends this WinZip AES extra field to existing extra data.
    byte[] appendTo(byte[] extraData) {
        Objects.requireNonNull(extraData, "extraData");
        byte[] field = encodedField();
        byte[] result = new byte[extraData.length + field.length];
        System.arraycopy(extraData, 0, result, 0, extraData.length);
        System.arraycopy(field, 0, result, extraData.length, field.length);
        return result;
    }

    /// Returns the encryption method described by the given metadata.
    static ZipEncryption encryption(int generalPurposeFlags, int method, byte[] extraData) {
        if ((generalPurposeFlags & ZipConstants.ENCRYPTED_FLAG) == 0) {
            return ZipEncryption.none();
        }

        ZipAesExtraField aes = read(extraData);
        if (aes != null) {
            return aes.encryption;
        }
        if (method == ZipConstants.WINZIP_AES_METHOD) {
            return ZipEncryption.of("winzip-aes-unknown");
        }
        return ZipEncryption.traditional();
    }

    /// Returns the actual compression method after considering encrypted WinZip AES metadata.
    static int compressionMethod(int generalPurposeFlags, int method, byte[] extraData) {
        ZipAesExtraField aes = (generalPurposeFlags & ZipConstants.ENCRYPTED_FLAG) != 0
                && method == ZipConstants.WINZIP_AES_METHOD ? read(extraData) : null;
        return aes != null ? aes.compressionMethod : method;
    }

    /// Returns whether the method and extra data describe a WinZip AES entry.
    static boolean isAes(int method, byte[] extraData) {
        return method == ZipConstants.WINZIP_AES_METHOD || read(extraData) != null;
    }

    /// Returns the vendor version field.
    int vendorVersion() {
        return vendorVersion;
    }

    /// Returns the AES encryption method.
    ZipEncryption encryption() {
        return encryption;
    }

    /// Returns the AES encryption key length in bytes.
    int keySize() {
        return switch (strength) {
            case AES_128_STRENGTH -> 16;
            case AES_192_STRENGTH -> 24;
            case AES_256_STRENGTH -> 32;
            default -> throw new AssertionError(strength);
        };
    }

    /// Returns the AES salt size in bytes.
    int saltSize() {
        return switch (strength) {
            case AES_128_STRENGTH -> 8;
            case AES_192_STRENGTH -> 12;
            case AES_256_STRENGTH -> 16;
            default -> throw new AssertionError(strength);
        };
    }

    /// Returns the length of the password verifier in bytes.
    int passwordVerifierSize() {
        return PASSWORD_VERIFIER_SIZE;
    }

    /// Returns the length of the authentication code in bytes.
    int authenticationCodeSize() {
        return AUTHENTICATION_CODE_SIZE;
    }

    /// Returns the total WinZip AES overhead stored in the entry body.
    int overheadSize() {
        return saltSize() + PASSWORD_VERIFIER_SIZE + AUTHENTICATION_CODE_SIZE;
    }

    /// Returns the actual ZIP compression method.
    int compressionMethod() {
        return compressionMethod;
    }

    /// Returns whether this field has the same metadata as another parsed WinZip AES field.
    boolean metadataMatches(ZipAesExtraField other) {
        Objects.requireNonNull(other, "other");
        return vendorVersion == other.vendorVersion
                && strength == other.strength
                && compressionMethod == other.compressionMethod;
    }

    /// Encodes this WinZip AES extra field.
    private byte[] encodedField() {
        byte[] field = new byte[4 + MIN_DATA_SIZE];
        writeUnsignedShort(field, 0, ZipConstants.WINZIP_AES_EXTRA_FIELD_ID);
        writeUnsignedShort(field, 2, MIN_DATA_SIZE);
        writeUnsignedShort(field, 4, vendorVersion);
        writeUnsignedShort(field, 6, AES_VENDOR_ID);
        field[8] = (byte) strength;
        writeUnsignedShort(field, 9, compressionMethod);
        return field;
    }

    /// Writes a little-endian unsigned 16-bit value to a byte array.
    private static void writeUnsignedShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    /// Reads WinZip AES extra field payload bytes.
    private static @Nullable ZipAesExtraField readData(byte[] extraData, int dataOffset, int dataSize) {
        if (dataSize < MIN_DATA_SIZE) {
            return null;
        }

        int vendorVersion = readUnsignedShort(extraData, dataOffset);
        if (vendorVersion != AE_1_VENDOR_VERSION && vendorVersion != AE_2_VENDOR_VERSION) {
            return null;
        }
        int vendorId = readUnsignedShort(extraData, dataOffset + 2);
        if (vendorId != AES_VENDOR_ID) {
            return null;
        }

        int strength = Byte.toUnsignedInt(extraData[dataOffset + 4]);
        if (strength != AES_128_STRENGTH && strength != AES_192_STRENGTH && strength != AES_256_STRENGTH) {
            return null;
        }
        ZipEncryption encryption = switch (strength) {
            case AES_128_STRENGTH -> ZipEncryption.winZipAes128();
            case AES_192_STRENGTH -> ZipEncryption.winZipAes192();
            case AES_256_STRENGTH -> ZipEncryption.winZipAes256();
            default -> throw new AssertionError(strength);
        };

        return new ZipAesExtraField(
                vendorVersion,
                encryption,
                strength,
                readUnsignedShort(extraData, dataOffset + 5)
        );
    }
}
