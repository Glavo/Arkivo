// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.CRC32;

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
        synchronized Result decode(
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

            DecoderOutput decoderOutput = new DecoderOutput(output, unpackedSize);
            try {
                if (family == VERSION_15) {
                    decoder15.decode(input, decoderOutput, unpackedSize, solid);
                } else if (family == VERSION_20) {
                    decoder20.decode(input, decoderOutput, unpackedSize, solid);
                } else {
                    decoder.decode(input, decoderOutput, unpackedSize, solid);
                }
                Result result = decoderOutput.result();
                initialized = true;
                historyAvailable = true;
                activeFamily = family;
                return result;
            } catch (IOException | RuntimeException | Error exception) {
                initialized = true;
                historyAvailable = false;
                decoder.invalidate();
                decoder15.invalidate();
                decoder20.invalidate();
                activeFamily = 0;
                throw exception;
            } finally {
                decoderOutput.clear();
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

    /// Describes the validated output produced by one decompression operation.
    ///
    /// @param size the decompressed byte count
    /// @param crc32 the unsigned CRC32 of the decompressed bytes
    @NotNullByDefault
    record Result(long size, long crc32) {
        /// Validates the decompression result values.
        Result {
            if (size < 0L || crc32 < 0L || crc32 > 0xffff_ffffL) {
                throw new IllegalArgumentException("Invalid RAR4 decompression result");
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
                throw new IOException("RAR4 decompressor exceeded the declared unpacked size");
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
                throw new IOException("RAR4 decompressor exceeded the declared unpacked size");
            }
            currentOutput.write(buffer, offset, length);
            crc32.update(buffer, offset, length);
            writtenSize += length;
        }

        /// Returns the validated decompression result.
        private Result result() throws IOException {
            if (writtenSize != expectedSize) {
                throw new IOException(
                        "RAR4 decompressor produced " + writtenSize + " bytes; expected " + expectedSize
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
                throw new IOException("RAR4 decompression output is unavailable");
            }
            return currentOutput;
        }
    }
}
