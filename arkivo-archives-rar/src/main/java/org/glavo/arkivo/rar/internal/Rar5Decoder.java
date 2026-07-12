// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.CRC32;

/// Validates RAR5 decompression requests and manages one native Arkivo decoder session.
@NotNullByDefault
final class Rar5Decoder {
    /// The largest dictionary accepted by the Java decoder.
    static final long MAX_DICTIONARY_SIZE = 768L * 1024L * 1024L;

    /// Prevents instantiation.
    private Rar5Decoder() {
    }

    /// Returns the dictionary size encoded by one validated RAR5 compression descriptor.
    static long dictionarySize(int dictionaryPower, int dictionaryFraction) {
        if (dictionaryPower < 0 || dictionaryPower > 31
                || dictionaryFraction < 0 || dictionaryFraction > 31) {
            throw new IllegalArgumentException("Invalid RAR5 dictionary properties");
        }
        return (32L + dictionaryFraction) << (12 + dictionaryPower);
    }

    /// Returns whether the Java decoder can allocate the encoded dictionary.
    static boolean supportsDictionary(int dictionaryPower, int dictionaryFraction) {
        return dictionarySize(dictionaryPower, dictionaryFraction) <= MAX_DICTIONARY_SIZE;
    }

    /// Creates one stateful decoder session that can preserve RAR5 solid history.
    static Session newSession() {
        return new Session();
    }

    /// Retains the dictionary and Huffman state needed by sequential solid RAR5 entries.
    @NotNullByDefault
    static final class Session {
        /// The stateful Arkivo RAR5 LZ decompressor.
        private final Rar5LzDecoder decoder = new Rar5LzDecoder();

        /// Whether this session has processed or invalidated an entry.
        private boolean initialized;

        /// Whether a successfully decoded dictionary is available for a solid continuation.
        private boolean historyAvailable;

        /// Whether decoder buffers have been released permanently.
        private boolean released;

        /// Creates one decoder session.
        private Session() {
        }

        /// Decompresses one entry and returns its validated output size and CRC32.
        synchronized Result decode(
                InputStream input,
                OutputStream output,
                int dictionaryPower,
                int dictionaryFraction,
                boolean version7,
                boolean solid,
                long unpackedSize
        ) throws IOException {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(output, "output");
            if (released) {
                throw new IOException("RAR5 decoder session has been released");
            }
            if (!supportsDictionary(dictionaryPower, dictionaryFraction)) {
                throw new IOException(
                        "RAR5 dictionary exceeds the supported "
                                + MAX_DICTIONARY_SIZE
                                + "-byte limit"
                );
            }
            if (unpackedSize < 0L) {
                throw new IOException("Compressed RAR5 entry has an unknown unpacked size");
            }
            if (solid && initialized && !historyAvailable) {
                throw new IOException("RAR5 solid entry is missing its preceding decompression history");
            }

            DecoderOutput decoderOutput = new DecoderOutput(output, unpackedSize);
            try {
                decoder.decode(
                        input,
                        decoderOutput,
                        Math.toIntExact(dictionarySize(dictionaryPower, dictionaryFraction)),
                        version7,
                        solid,
                        unpackedSize
                );
                Result result = decoderOutput.result();
                initialized = true;
                historyAvailable = true;
                return result;
            } catch (IOException | RuntimeException | Error exception) {
                initialized = true;
                historyAvailable = false;
                decoder.invalidate();
                throw exception;
            } finally {
                decoderOutput.clear();
            }
        }

        /// Invalidates dictionary history after packed bytes were skipped without decompression.
        synchronized void invalidateHistory() {
            if (released) {
                return;
            }
            initialized = true;
            historyAvailable = false;
            decoder.invalidate();
        }

        /// Releases dictionary and filter buffers retained by this session.
        synchronized void release() {
            if (released) {
                return;
            }
            released = true;
            initialized = false;
            historyAvailable = false;
            decoder.release();
        }
    }

    /// Describes the validated output produced by one RAR5 decompression operation.
    ///
    /// @param size the decompressed byte count
    /// @param crc32 the unsigned CRC32 of the decompressed bytes
    @NotNullByDefault
    record Result(long size, long crc32) {
        /// Validates the decompression result.
        Result {
            if (size < 0L || crc32 < 0L || crc32 > 0xffff_ffffL) {
                throw new IllegalArgumentException("Invalid RAR5 decompression result");
            }
        }
    }

    /// Validates and hashes decompressed bytes before forwarding them to the caller.
    @NotNullByDefault
    private static final class DecoderOutput extends OutputStream {
        /// The caller-owned decompressed output, or `null` after cleanup.
        private @Nullable OutputStream output;

        /// The declared decompressed byte count.
        private final long expectedSize;

        /// The decompressed byte count written so far.
        private long writtenSize;

        /// The CRC32 of decompressed bytes.
        private final CRC32 crc32 = new CRC32();

        /// Creates one validating output bridge.
        private DecoderOutput(OutputStream output, long expectedSize) {
            this.output = Objects.requireNonNull(output, "output");
            this.expectedSize = expectedSize;
        }

        /// Writes one decompressed byte.
        @Override
        public void write(int value) throws IOException {
            OutputStream currentOutput = requireOutput();
            if (writtenSize >= expectedSize) {
                throw new IOException("RAR5 decompressor exceeded the declared unpacked size");
            }
            currentOutput.write(value);
            crc32.update(value);
            writtenSize++;
        }

        /// Writes decompressed bytes while enforcing the declared unpacked size.
        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            OutputStream currentOutput = requireOutput();
            if (writtenSize > expectedSize - length) {
                throw new IOException("RAR5 decompressor exceeded the declared unpacked size");
            }
            currentOutput.write(buffer, offset, length);
            crc32.update(buffer, offset, length);
            writtenSize += length;
        }

        /// Returns the validated decompression result.
        private Result result() throws IOException {
            if (writtenSize != expectedSize) {
                throw new IOException(
                        "RAR5 decompressor produced "
                                + writtenSize
                                + " bytes; expected "
                                + expectedSize
                );
            }
            return new Result(writtenSize, crc32.getValue());
        }

        /// Releases the caller-owned output reference and checksum state.
        private void clear() {
            output = null;
            writtenSize = 0L;
            crc32.reset();
        }

        /// Returns the active caller-owned output.
        private OutputStream requireOutput() throws IOException {
            OutputStream currentOutput = output;
            if (currentOutput == null) {
                throw new IOException("RAR5 decompression output is unavailable");
            }
            return currentOutput;
        }
    }
}
