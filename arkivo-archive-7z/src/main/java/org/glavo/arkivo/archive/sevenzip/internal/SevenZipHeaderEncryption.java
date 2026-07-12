// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;

/// Replaces a completed plain 7z next header with an AES-encrypted encoded header.
@NotNullByDefault
final class SevenZipHeaderEncryption implements AutoCloseable {
    /// The fixed 7z file signature bytes.
    private static final byte @Unmodifiable [] SIGNATURE =
            new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c};

    /// The 7z AES-256/SHA-256 coder method ID.
    private static final byte @Unmodifiable [] AES_METHOD_ID =
            new byte[]{0x06, (byte) 0xf1, 0x07, 0x01};

    /// The normal 7z header property ID.
    private static final int NID_HEADER = 0x01;

    /// The end property ID.
    private static final int NID_END = 0x00;

    /// The pack-info property ID.
    private static final int NID_PACK_INFO = 0x06;

    /// The unpack-info property ID.
    private static final int NID_UNPACK_INFO = 0x07;

    /// The size property ID.
    private static final int NID_SIZE = 0x09;

    /// The CRC-32 property ID.
    private static final int NID_CRC = 0x0a;

    /// The folder property ID.
    private static final int NID_FOLDER = 0x0b;

    /// The coder unpack-size property ID.
    private static final int NID_CODERS_UNPACK_SIZE = 0x0c;

    /// The encoded-header property ID.
    private static final int NID_ENCODED_HEADER = 0x17;

    /// The AES key-derivation cycle power used by Arkivo 7z output.
    private static final int AES_CYCLE_POWER = 19;

    /// The AES block and initialization-vector size.
    private static final int AES_BLOCK_SIZE = 16;

    /// The largest number of consecutive zero-byte channel operations tolerated.
    private static final int MAX_ZERO_PROGRESS_ATTEMPTS = 1024;

    /// The derived AES key, cleared when this object closes.
    private final byte[] key;

    /// The random AES initialization vector.
    private final byte @Unmodifiable [] initializationVector;

    /// The serialized 7z AES coder properties.
    private final byte @Unmodifiable [] coderProperties;

    /// Whether the derived key has been cleared.
    private boolean closed;

    /// Creates an initialized header encryptor.
    private SevenZipHeaderEncryption(
            byte[] key,
            byte @Unmodifiable [] initializationVector,
            byte @Unmodifiable [] coderProperties
    ) {
        this.key = Objects.requireNonNull(key, "key");
        this.initializationVector = Objects.requireNonNull(initializationVector, "initializationVector").clone();
        this.coderProperties = Objects.requireNonNull(coderProperties, "coderProperties").clone();
    }

    /// Derives a header-encryption key and generates fresh coder properties.
    static SevenZipHeaderEncryption create(byte @Unmodifiable [] password) throws IOException {
        Objects.requireNonNull(password, "password");
        byte[] initializationVector = new byte[AES_BLOCK_SIZE];
        new SecureRandom().nextBytes(initializationVector);

        byte[] key = SevenZipAesCrypto.deriveKey(AES_CYCLE_POWER, new byte[0], password);
        try {
            byte[] coderProperties = new byte[2 + initializationVector.length];
            coderProperties[0] = (byte) (AES_CYCLE_POWER | 1 << 6);
            coderProperties[1] = (byte) (initializationVector.length - 1);
            System.arraycopy(
                    initializationVector,
                    0,
                    coderProperties,
                    2,
                    initializationVector.length
            );
            return new SevenZipHeaderEncryption(key, initializationVector, coderProperties);
        } catch (RuntimeException | Error exception) {
            Arrays.fill(key, (byte) 0);
            throw exception;
        }
    }

    /// Encrypts the completed plain next header in the requested archive file.
    void applyTo(Path archivePath) throws IOException {
        Objects.requireNonNull(archivePath, "archivePath");
        ensureOpen();
        try (SeekableByteChannel channel = Files.newByteChannel(
                archivePath,
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
        )) {
            applyTo(channel);
        }
    }

    /// Encrypts the completed plain next header in a seekable archive channel.
    void applyTo(SeekableByteChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        ensureOpen();
        SevenZipSignatureHeader signatureHeader = SevenZipHeaderReader.readSignatureHeader(channel);
        long nextHeaderStart = checkedAdd(
                SevenZipSignatureHeader.SIZE,
                signatureHeader.nextHeaderOffset(),
                "Invalid 7z next header offset"
        );
        long nextHeaderEnd = checkedAdd(
                nextHeaderStart,
                signatureHeader.nextHeaderSize(),
                "Invalid 7z next header size"
        );
        if (nextHeaderEnd != channel.size()) {
            throw new IOException("Plain 7z next header must be the final archive data");
        }
        if (signatureHeader.nextHeaderSize() <= 0L) {
            throw new IOException("Cannot encrypt an empty 7z next header");
        }
        if (signatureHeader.nextHeaderSize() > Integer.MAX_VALUE) {
            throw new IOException("7z next header is too large to encrypt");
        }

        byte[] plainHeader = new byte[(int) signatureHeader.nextHeaderSize()];
        channel.position(nextHeaderStart);
        readFully(channel, ByteBuffer.wrap(plainHeader));
        if (Byte.toUnsignedInt(plainHeader[0]) != NID_HEADER) {
            Arrays.fill(plainHeader, (byte) 0);
            throw new IOException("7z next header is already encoded or has an unsupported type");
        }

        byte @Unmodifiable [] encryptedHeader;
        byte @Unmodifiable [] encodedHeader;
        try {
            encryptedHeader = encrypt(plainHeader);
            encodedHeader = encodedHeader(
                    signatureHeader.nextHeaderOffset(),
                    encryptedHeader,
                    plainHeader
            );
        } finally {
            Arrays.fill(plainHeader, (byte) 0);
        }

        long encodedHeaderOffset = checkedAdd(
                signatureHeader.nextHeaderOffset(),
                encryptedHeader.length,
                "Encrypted 7z header offset is too large"
        );
        byte @Unmodifiable [] rewrittenSignatureHeader = signatureHeader(
                signatureHeader.majorVersion(),
                signatureHeader.minorVersion(),
                encodedHeaderOffset,
                encodedHeader
        );

        channel.position(nextHeaderStart);
        writeFully(channel, ByteBuffer.wrap(encryptedHeader));
        writeFully(channel, ByteBuffer.wrap(encodedHeader));
        channel.truncate(checkedAdd(nextHeaderStart, encryptedHeader.length + (long) encodedHeader.length,
                "Encrypted 7z archive is too large"));
        channel.position(0L);
        writeFully(channel, ByteBuffer.wrap(rewrittenSignatureHeader));
    }

    /// Clears the derived AES key.
    @Override
    public void close() {
        if (closed) {
            return;
        }
        Arrays.fill(key, (byte) 0);
        closed = true;
    }

    /// Encrypts and zero-pads one plain next header.
    private byte @Unmodifiable [] encrypt(byte @Unmodifiable [] plainHeader) throws IOException {
        long paddedLength = (plainHeader.length + (long) AES_BLOCK_SIZE - 1L) / AES_BLOCK_SIZE * AES_BLOCK_SIZE;
        if (paddedLength > Integer.MAX_VALUE) {
            throw new IOException("Padded 7z next header is too large to encrypt");
        }
        byte[] paddedHeader = Arrays.copyOf(plainHeader, (int) paddedLength);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(initializationVector)
            );
            return cipher.doFinal(paddedHeader);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Failed to encrypt 7z header", exception);
        } finally {
            Arrays.fill(paddedHeader, (byte) 0);
        }
    }

    /// Builds a `kEncodedHeader` descriptor for one AES packed stream.
    private byte @Unmodifiable [] encodedHeader(
            long packPosition,
            byte @Unmodifiable [] encryptedHeader,
            byte @Unmodifiable [] plainHeader
    ) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(NID_ENCODED_HEADER);

        output.write(NID_PACK_INFO);
        writeUint64(output, packPosition);
        writeUint64(output, 1L);
        output.write(NID_SIZE);
        writeUint64(output, encryptedHeader.length);
        output.write(NID_CRC);
        output.write(1);
        writeIntLittleEndian(output, crc32(encryptedHeader));
        output.write(NID_END);

        output.write(NID_UNPACK_INFO);
        output.write(NID_FOLDER);
        writeUint64(output, 1L);
        output.write(0);
        writeUint64(output, 1L);
        output.write(AES_METHOD_ID.length | 0x20);
        output.writeBytes(AES_METHOD_ID);
        writeUint64(output, coderProperties.length);
        output.writeBytes(coderProperties);
        output.write(NID_CODERS_UNPACK_SIZE);
        writeUint64(output, plainHeader.length);
        output.write(NID_CRC);
        output.write(1);
        writeIntLittleEndian(output, crc32(plainHeader));
        output.write(NID_END);

        output.write(NID_END);
        return output.toByteArray();
    }

    /// Builds a fixed signature header that points at the encoded-header descriptor.
    private static byte @Unmodifiable [] signatureHeader(
            int majorVersion,
            int minorVersion,
            long nextHeaderOffset,
            byte @Unmodifiable [] nextHeader
    ) {
        ByteBuffer buffer = ByteBuffer.allocate(SevenZipSignatureHeader.SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(SIGNATURE);
        buffer.put((byte) majorVersion);
        buffer.put((byte) minorVersion);
        buffer.putInt(0);
        buffer.putLong(nextHeaderOffset);
        buffer.putLong(nextHeader.length);
        buffer.putInt((int) crc32(nextHeader));
        CRC32 crc32 = new CRC32();
        crc32.update(buffer.array(), 12, 20);
        buffer.putInt(8, (int) crc32.getValue());
        return buffer.array();
    }

    /// Writes one non-negative 7z variable-length unsigned integer.
    private static void writeUint64(ByteArrayOutputStream output, long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("7z unsigned integer must not be negative");
        }
        int firstByte = 0;
        int mask = 0x80;
        int additionalByteCount;
        for (additionalByteCount = 0; additionalByteCount < 8; additionalByteCount++) {
            if (value < 1L << 7 * (additionalByteCount + 1)) {
                firstByte |= (int) (value >>> 8 * additionalByteCount);
                break;
            }
            firstByte |= mask;
            mask >>>= 1;
        }
        output.write(firstByte);
        for (; additionalByteCount > 0; additionalByteCount--) {
            output.write((int) (0xff & value));
            value >>>= 8;
        }
    }

    /// Writes a 32-bit integer in little-endian byte order.
    private static void writeIntLittleEndian(ByteArrayOutputStream output, long value) {
        output.write((int) value);
        output.write((int) (value >>> 8));
        output.write((int) (value >>> 16));
        output.write((int) (value >>> 24));
    }

    /// Returns the CRC-32 value of one byte array.
    private static long crc32(byte @Unmodifiable [] bytes) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes);
        return crc32.getValue();
    }

    /// Reads bytes until the destination buffer is full.
    private static void readFully(SeekableByteChannel channel, ByteBuffer destination) throws IOException {
        int zeroProgressCount = 0;
        while (destination.hasRemaining()) {
            int read = channel.read(destination);
            if (read < 0) {
                throw new EOFException("Unexpected end of 7z next header");
            }
            if (read == 0) {
                zeroProgressCount++;
                if (zeroProgressCount >= MAX_ZERO_PROGRESS_ATTEMPTS) {
                    throw new IOException("7z header read made no progress");
                }
                Thread.onSpinWait();
            } else {
                zeroProgressCount = 0;
            }
        }
    }

    /// Writes bytes until the source buffer is empty.
    private static void writeFully(SeekableByteChannel channel, ByteBuffer source) throws IOException {
        int zeroProgressCount = 0;
        while (source.hasRemaining()) {
            if (channel.write(source) == 0) {
                zeroProgressCount++;
                if (zeroProgressCount >= MAX_ZERO_PROGRESS_ATTEMPTS) {
                    throw new IOException("7z header write made no progress");
                }
                Thread.onSpinWait();
            } else {
                zeroProgressCount = 0;
            }
        }
    }

    /// Adds two archive offsets and reports overflow as an I/O failure.
    private static long checkedAdd(long left, long right, String message) throws IOException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IOException(message, exception);
        }
    }

    /// Requires the derived key to remain available.
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("7z header encryption key has been cleared");
        }
    }
}
