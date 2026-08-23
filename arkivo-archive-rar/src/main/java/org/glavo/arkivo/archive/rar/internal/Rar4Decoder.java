// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;

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

    /// Creates one stateful decoder session that can preserve a solid dictionary across entries.
    static Session newSession() {
        return new Session();
    }

    /// Retains the dictionary and Huffman state needed by sequential solid RAR4 entries.
    @NotNullByDefault
    static final class Session {
        /// The native RAR 3.x/4.x LZ decoder.
        private final Rar4Lz29Decoder decoder = new Rar4Lz29Decoder();

        /// The native RAR 1.5 adaptive LZ decoder.
        private final Rar4Lz15Decoder decoder15 = new Rar4Lz15Decoder();

        /// The native RAR 2.x LZ and adaptive-audio decoder.
        private final Rar4Lz20Decoder decoder20 = new Rar4Lz20Decoder();

        /// The extraction-version family owning the retained solid history.
        private int activeFamily;

        /// Whether a successfully initialized dictionary is available for a solid continuation.
        private boolean historyAvailable;

        /// Whether this session has processed or invalidated an entry.
        private boolean initialized;

        /// Whether decoder resources have been released permanently.
        private boolean released;

        /// Creates one empty native decoder session.
        private Session() {
        }

        /// Decompresses one entry and returns its validated output size and CRC32.
        synchronized long decode(
                InputStream input,
                OutputStream output,
                int extractionVersion,
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
                if (family == VERSION_15) {
                    decoder15.decode(input, decoderOutput, unpackedSize, solid);
                } else if (family == VERSION_20) {
                    decoder20.decode(input, decoderOutput, unpackedSize, solid);
                } else {
                    decoder.decode(input, decoderOutput, unpackedSize, solid);
                }
                long crc32 = decoderOutput.validatedCrc32();
                initialized = true;
                historyAvailable = true;
                activeFamily = family;
                return crc32;
            } catch (IOException | RuntimeException | Error exception) {
                initialized = true;
                historyAvailable = false;
                decoder.invalidate();
                decoder15.invalidate();
                decoder20.invalidate();
                activeFamily = 0;
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
            decoder.invalidate();
            decoder15.invalidate();
            decoder20.invalidate();
            activeFamily = 0;
        }

        /// Releases the fixed dictionary retained by this session.
        synchronized void release() {
            if (released) {
                return;
            }
            released = true;
            initialized = false;
            historyAvailable = false;
            decoder.release();
            decoder15.release();
            decoder20.release();
            activeFamily = 0;
        }
    }
}
