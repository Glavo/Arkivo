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

/// Validates legacy RAR decompression requests and manages one native Arkivo decoder session.
@NotNullByDefault
final class Rar4Decoder {
    /// The RAR 1.5 extraction version.
    private static final int VERSION_15 = 15;

    /// The RAR 2.x extraction version.
    private static final int VERSION_20 = 20;

    /// The RAR 2.x extraction version used for large files.
    private static final int VERSION_26 = 26;

    /// The RAR 3.x extraction version.
    private static final int VERSION_29 = 29;

    /// The RAR 3.x extraction version using alternative hash metadata.
    private static final int VERSION_36 = 36;

    /// Prevents instantiation.
    private Rar4Decoder() {
    }

    /// Returns whether the extraction version is currently implemented by the native decoder.
    static boolean supports(int extractionVersion) {
        return extractionVersion == VERSION_15
                || extractionVersion == VERSION_20
                || extractionVersion == VERSION_26
                || extractionVersion == VERSION_29
                || extractionVersion == VERSION_36;
    }

    /// Creates a solid decoder session with window and working-memory limits in bytes, or `UNLIMITED_SIZE`.
    static Session newSession(long maximumWindowSize, long maximumMemorySize) {
        if (maximumWindowSize < ArchiveReadLimits.UNLIMITED_SIZE) {
            throw new IllegalArgumentException("maximumWindowSize must be UNLIMITED_SIZE or non-negative");
        }
        return new Session(maximumWindowSize, maximumMemorySize);
    }

    /// Retains the dictionary and Huffman state needed by sequential solid RAR4 entries.
    @NotNullByDefault
    static final class Session {
        /// The native RAR 3.x/4.x LZ decoder.
        private @Nullable Rar4Lz29Decoder decoder;

        /// The native RAR 1.5 adaptive LZ decoder.
        private @Nullable Rar4Lz15Decoder decoder15;

        /// The native RAR 2.x LZ and adaptive-audio decoder.
        private @Nullable Rar4Lz20Decoder decoder20;

        /// The configured history-window limit, or `UNLIMITED_SIZE`.
        private final long maximumWindowSize;

        /// The work-array budget shared by the active RAR4 decoder family.
        private final RarDecoderMemoryBudget memory;

        /// The retained dictionary size, or zero when no engine is allocated.
        private int activeDictionarySize;

        /// The extraction-version family owning the retained solid history.
        private int activeFamily;

        /// Whether a successfully initialized dictionary is available for a solid continuation.
        private boolean historyAvailable;

        /// Whether this session has processed or invalidated an entry.
        private boolean initialized;

        /// Whether decoder resources have been released permanently.
        private boolean released;

        /// Creates one empty native decoder session.
        private Session(long maximumWindowSize, long maximumMemorySize) {
            this.maximumWindowSize = maximumWindowSize;
            memory = new RarDecoderMemoryBudget(maximumMemorySize);
        }

        /// Decompresses one entry and returns its validated output size and CRC32.
        synchronized long decode(
                InputStream input,
                OutputStream output,
                int extractionVersion,
                int dictionarySize,
                long unpackedSize,
                boolean solid
        ) throws IOException {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(output, "output");
            if (released) {
                throw new IOException("RAR4 decoder session has been released");
            }
            if (!supports(extractionVersion)) {
                throw new IOException("Unsupported RAR4 extraction version: " + extractionVersion);
            }
            if (unpackedSize < 0L) {
                throw new IOException("Compressed RAR4 entry has an unknown unpacked size");
            }
            if (solid && initialized && !historyAvailable) {
                throw new IOException("RAR4 solid entry is missing its preceding decompression history");
            }
            int family = switch (extractionVersion) {
                case VERSION_15 -> VERSION_15;
                case VERSION_20, VERSION_26 -> VERSION_20;
                default -> VERSION_29;
            };
            if (solid && initialized && activeFamily != family) {
                throw new IOException("RAR4 solid entry changes its decompression algorithm");
            }

            RarValidatingOutputStream decoderOutput =
                    new RarValidatingOutputStream("RAR4", output, unpackedSize);
            try {
                if (dictionarySize < 1 << 16 || dictionarySize > 1 << 22
                        || Integer.bitCount(dictionarySize) != 1) {
                    throw new IOException("Invalid RAR4 dictionary size: " + dictionarySize);
                }
                if (maximumWindowSize >= 0 && dictionarySize > maximumWindowSize) {
                    throw new DecompressionWindowLimitException(maximumWindowSize, dictionarySize);
                }
                if (solid && initialized) {
                    if (dictionarySize > activeDictionarySize) {
                        throw new IOException("RAR4 solid entry increases its dictionary size");
                    }
                } else if (family != activeFamily || dictionarySize != activeDictionarySize) {
                    releaseDecoders();
                }
                if (family == VERSION_15) {
                    if (decoder15 == null) {
                        decoder15 = new Rar4Lz15Decoder(dictionarySize, memory);
                    }
                    decoder15.decode(input, decoderOutput, unpackedSize, solid);
                } else if (family == VERSION_20) {
                    if (decoder20 == null) {
                        decoder20 = new Rar4Lz20Decoder(dictionarySize, memory);
                    }
                    decoder20.decode(input, decoderOutput, unpackedSize, solid);
                } else {
                    if (decoder == null) {
                        decoder = new Rar4Lz29Decoder(dictionarySize, memory);
                    }
                    decoder.decode(input, decoderOutput, unpackedSize, solid);
                }
                long crc32 = decoderOutput.validatedCrc32();
                if (!solid || !initialized) {
                    activeDictionarySize = dictionarySize;
                }
                initialized = true;
                historyAvailable = true;
                activeFamily = family;
                return crc32;
            } catch (IOException | RuntimeException | Error exception) {
                invalidateHistory();
                throw exception;
            }
        }

        /// Invalidates dictionary history after an entry is skipped without decompression.
        synchronized void invalidateHistory() {
            if (released) {
                return;
            }
            initialized = true;
            historyAvailable = false;
            releaseDecoders();
        }

        /// Releases the active decoder and permanently closes this session.
        synchronized void release() {
            if (released) {
                return;
            }
            released = true;
            initialized = false;
            historyAvailable = false;
            releaseDecoders();
        }

        /// Discards all retained engine state before switching algorithms or starting a new dictionary.
        private void releaseDecoders() {
            if (decoder != null) {
                decoder.release();
                decoder = null;
            }
            if (decoder15 != null) {
                decoder15.release();
                decoder15 = null;
            }
            if (decoder20 != null) {
                decoder20.release();
                decoder20 = null;
            }
            activeFamily = 0;
            activeDictionarySize = 0;
        }
    }
}
