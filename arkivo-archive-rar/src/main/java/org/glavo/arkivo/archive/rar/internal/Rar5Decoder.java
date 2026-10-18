// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

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

    /// Creates a solid decoder session with byte limits for its window and accounted work arrays.
    /// Either limit may be `UNLIMITED_SIZE`.
    static Session newSession(long maximumWindowSize, long maximumMemorySize) {
        if (maximumWindowSize < ArchiveReadLimits.UNLIMITED_SIZE) {
            throw new IllegalArgumentException("maximumWindowSize must be UNLIMITED_SIZE or non-negative");
        }
        return new Session(maximumWindowSize, maximumMemorySize);
    }

    /// Retains the dictionary and Huffman state needed by sequential solid RAR5 entries.
    @NotNullByDefault
    static final class Session {
        /// The stateful Arkivo RAR5 LZ decompressor.
        private @Nullable Rar5LzDecoder decoder;

        /// The budget shared by retained model data and per-entry work arrays.
        private final RarDecoderMemoryBudget memory;

        /// The configured history-window limit, or `UNLIMITED_SIZE`.
        private final long maximumWindowSize;

        /// Whether this session has processed or invalidated an entry.
        private boolean initialized;

        /// Whether a successfully decoded dictionary is available for a solid continuation.
        private boolean historyAvailable;

        /// Whether decoder buffers have been released permanently.
        private boolean released;

        /// Creates one decoder session.
        private Session(long maximumWindowSize, long maximumMemorySize) {
            this.maximumWindowSize = maximumWindowSize;
            this.memory = new RarDecoderMemoryBudget(maximumMemorySize);
        }

        /// Decompresses one entry and returns its validated output size and CRC32.
        synchronized long decode(
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

            RarValidatingOutputStream decoderOutput =
                    new RarValidatingOutputStream("RAR5", output, unpackedSize);
            try {
                long requiredWindowSize = dictionarySize(dictionaryPower, dictionaryFraction);
                if (maximumWindowSize >= 0 && requiredWindowSize > maximumWindowSize) {
                    throw new DecompressionWindowLimitException(maximumWindowSize, requiredWindowSize);
                }
                if (decoder == null) {
                    decoder = new Rar5LzDecoder(memory);
                }
                decoder.decode(
                        input,
                        decoderOutput,
                        Math.toIntExact(requiredWindowSize),
                        version7,
                        solid,
                        unpackedSize
                );
                long crc32 = decoderOutput.validatedCrc32();
                initialized = true;
                historyAvailable = true;
                return crc32;
            } catch (IOException | RuntimeException | Error exception) {
                invalidateHistory();
                throw exception;
            }
        }

        /// Invalidates dictionary history after packed bytes were skipped without decompression.
        synchronized void invalidateHistory() {
            if (released) {
                return;
            }
            initialized = true;
            historyAvailable = false;
            releaseDecoder();
        }

        /// Releases dictionary and filter buffers retained by this session.
        synchronized void release() {
            if (released) {
                return;
            }
            released = true;
            initialized = false;
            historyAvailable = false;
            releaseDecoder();
        }

        /// Discards the engine after its per-entry workspace has been released.
        private void releaseDecoder() {
            if (decoder != null) {
                decoder.release();
                decoder = null;
            }
        }
    }
}
