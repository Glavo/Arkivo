// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Objects;

/// Implements the traditional PKWARE ZIP encryption transform.
@NotNullByDefault
final class ZipTraditionalCrypto {
    /// The traditional ZIP encryption header size.
    static final int HEADER_SIZE = 12;

    /// The CRC-32 table used by the PKWARE key schedule.
    private static final int[] CRC_TABLE = createCrcTable();

    /// The random source used to create encryption headers.
    private static final SecureRandom RANDOM = new SecureRandom();

    /// Creates no instances.
    private ZipTraditionalCrypto() {
    }

    /// Returns the current failure with the given exception added as a suppressed failure when needed.
    private static Throwable mergeFailure(@Nullable Throwable failure, Throwable exception) {
        if (failure != null) {
            failure.addSuppressed(exception);
            return failure;
        }
        return exception;
    }

    /// Throws the given failure while preserving its original checked or unchecked type.
    private static void throwFailure(@Nullable Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error exception) {
            throw exception;
        }
    }

    /// Opens a stream that decrypts a traditional ZIP entry body after validating its encryption header.
    static InputStream openDecryptingStream(
            InputStream input,
            byte[] password,
            int verificationByte
    ) throws IOException {
        Decryptor decryptor = openDecryptor(input, password, verificationByte);
        return new DecryptingInputStream(input, decryptor);
    }

    /// Reads and validates a traditional ZIP encryption header and returns a decryptor for the remaining entry data.
    static Decryptor openDecryptor(
            InputStream input,
            byte[] password,
            int verificationByte
    ) throws IOException {
        Decryptor decryptor = new Decryptor(State.forPassword(password));
        byte[] header = readHeader(input);
        for (int index = 0; index < header.length; index++) {
            int value = decryptor.decrypt(Byte.toUnsignedInt(header[index]));
            header[index] = (byte) value;
        }
        if (Byte.toUnsignedInt(header[HEADER_SIZE - 1]) != (verificationByte & 0xff)) {
            throw new IOException("ZIP password verification failed");
        }
        return decryptor;
    }

    /// Writes a traditional ZIP encryption header and returns a stream that encrypts entry data.
    static OutputStream openEncryptingStream(
            OutputStream output,
            byte[] password,
            int verificationByte
    ) throws IOException {
        State state = State.forPassword(password);
        byte[] header = new byte[HEADER_SIZE];
        RANDOM.nextBytes(header);
        header[HEADER_SIZE - 1] = (byte) verificationByte;
        for (int index = 0; index < header.length; index++) {
            int plain = Byte.toUnsignedInt(header[index]);
            header[index] = (byte) (plain ^ state.decryptByte());
            state.update(plain);
        }
        output.write(header);
        return new EncryptingOutputStream(output, state);
    }

    /// Reads the encrypted header bytes.
    private static byte[] readHeader(InputStream input) throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        int offset = 0;
        while (offset < header.length) {
            int read = input.read(header, offset, header.length - offset);
            if (read < 0) {
                throw new EOFException("Unexpected end of ZIP encryption header");
            }
            offset += read;
        }
        return header;
    }

    /// Creates the CRC-32 lookup table.
    private static int[] createCrcTable() {
        int[] table = new int[256];
        for (int index = 0; index < table.length; index++) {
            int value = index;
            for (int bit = 0; bit < 8; bit++) {
                value = (value >>> 1) ^ ((value & 1) != 0 ? 0xedb8_8320 : 0);
            }
            table[index] = value;
        }
        return table;
    }

    /// Updates a CRC-32 accumulator with one byte.
    private static int updateCrc32(int crc32, int value) {
        return (crc32 >>> 8) ^ CRC_TABLE[(crc32 ^ value) & 0xff];
    }

    /// Stores the three mutable traditional ZIP encryption keys.
    @NotNullByDefault
    private static final class State {
        /// The first key.
        private int key0 = 0x1234_5678;

        /// The second key.
        private int key1 = 0x2345_6789;

        /// The third key.
        private int key2 = 0x3456_7890;

        /// Creates a state initialized with the given password.
        private static State forPassword(byte[] password) {
            Objects.requireNonNull(password, "password");
            State state = new State();
            for (byte value : password) {
                state.update(Byte.toUnsignedInt(value));
            }
            return state;
        }

        /// Updates the key state with one plaintext byte.
        private void update(int value) {
            key0 = updateCrc32(key0, value);
            key1 = key1 + (key0 & 0xff);
            key1 = key1 * 134_775_813 + 1;
            key2 = updateCrc32(key2, key1 >>> 24);
        }

        /// Returns the next keystream byte.
        private int decryptByte() {
            int temp = (key2 | 2) & 0xffff;
            return ((temp * (temp ^ 1)) >>> 8) & 0xff;
        }
    }

    /// Decrypts traditional ZIP bytes while updating the key state.
    @NotNullByDefault
    static final class Decryptor {
        /// The mutable key state.
        private final State state;

        /// Creates a decryptor with initialized key state.
        private Decryptor(State state) {
            this.state = Objects.requireNonNull(state, "state");
        }

        /// Decrypts one encrypted byte and updates the key state with the plaintext byte.
        int decrypt(int encrypted) {
            int plain = (encrypted & 0xff) ^ state.decryptByte();
            state.update(plain);
            return plain;
        }
    }

    /// Decrypts traditional ZIP entry data.
    @NotNullByDefault
    private static final class DecryptingInputStream extends FilterInputStream {
        /// The decryptor used for this entry data.
        private final Decryptor decryptor;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Whether the wrapped input stream has been closed.
        private boolean inputClosed;

        /// Creates a decrypting input stream.
        private DecryptingInputStream(InputStream input, Decryptor decryptor) {
            super(Objects.requireNonNull(input, "input"));
            this.decryptor = Objects.requireNonNull(decryptor, "decryptor");
        }

        /// Reads one decrypted byte.
        @Override
        public int read() throws IOException {
            ensureOpen();
            int encrypted = in.read();
            if (encrypted < 0) {
                return -1;
            }
            return decryptor.decrypt(encrypted);
        }

        /// Reads decrypted bytes.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            ensureOpen();
            int read = in.read(bytes, offset, length);
            for (int index = 0; index < read; index++) {
                int byteIndex = offset + index;
                bytes[byteIndex] = (byte) decryptor.decrypt(Byte.toUnsignedInt(bytes[byteIndex]));
            }
            return read;
        }

        /// Closes this decrypting stream.
        @Override
        public void close() throws IOException {
            if (closed && inputClosed) {
                return;
            }
            closed = true;
            in.close();
            inputClosed = true;
        }

        /// Requires this stream to be open.
        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("ZIP traditional input stream is closed");
            }
        }
    }

    /// Encrypts traditional ZIP entry data.
    @NotNullByDefault
    private static final class EncryptingOutputStream extends FilterOutputStream {
        /// The mutable key state.
        private final State state;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Whether the wrapped output stream has been closed.
        private boolean outputClosed;

        /// Creates an encrypting output stream.
        private EncryptingOutputStream(OutputStream output, State state) {
            super(Objects.requireNonNull(output, "output"));
            this.state = Objects.requireNonNull(state, "state");
        }

        /// Writes one encrypted byte.
        @Override
        public void write(int value) throws IOException {
            ensureOpen();
            int plain = value & 0xff;
            out.write(plain ^ state.decryptByte());
            state.update(plain);
        }

        /// Writes encrypted bytes.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            ensureOpen();
            for (int index = 0; index < length; index++) {
                write(Byte.toUnsignedInt(bytes[offset + index]));
            }
        }

        /// Closes this encrypting stream and its wrapped output stream.
        @Override
        public void close() throws IOException {
            if (closed && outputClosed) {
                return;
            }
            closed = true;
            Throwable failure = null;
            try {
                flush();
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
            }
            try {
                out.close();
                outputClosed = true;
            } catch (IOException | RuntimeException | Error exception) {
                failure = mergeFailure(failure, exception);
            }
            throwFailure(failure);
        }

        /// Requires this stream to be open.
        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("ZIP traditional output stream is closed");
            }
        }
    }
}
