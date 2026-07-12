// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32;

/// Implements the native replacements for the standard RAR3 virtual-machine filters.
@NotNullByDefault
final class Rar3StandardFilters {
    /// The effective address space used by the x86 filters.
    private static final int X86_ADDRESS_SPACE = 1 << 24;
    /// The instruction-slot masks indexed by an Itanium bundle template.
    private static final int @Unmodifiable [] ITANIUM_SLOT_MASKS = {
            4, 4, 6, 6, 0, 0, 7, 7, 4, 4, 0, 0, 4, 4, 0, 0
    };

    /// Prevents construction of this utility class.
    private Rar3StandardFilters() {
    }

    /// Identifies a standard filter by its bytecode length and CRC-32 fingerprint.
    static int identify(byte[] code) {
        CRC32 crc = new CRC32();
        crc.update(code);
        long value = crc.getValue();
        if (code.length == 53 && value == 0xad57_6887L) return 0;
        if (code.length == 57 && value == 0x3cd7_e57eL) return 1;
        if (code.length == 120 && value == 0x3769_893fL) return 2;
        if (code.length == 29 && value == 0x0e06_077dL) return 3;
        if (code.length == 149 && value == 0x1c2c_5dc8L) return 4;
        if (code.length == 216 && value == 0xbc85_e701L) return 5;
        return -1;
    }

    /// Applies one identified standard filter.
    static byte[] apply(int identifier, int[] registers, byte[] input, long offset) throws IOException {
        return switch (identifier) {
            case 0 -> filterX86(input, offset, false);
            case 1 -> filterX86(input, offset, true);
            case 2 -> filterItanium(input, offset);
            case 3 -> filterDelta(input, registers[0]);
            case 4 -> filterRgb(input, registers[0], registers[1]);
            case 5 -> filterAudio(input, registers[0]);
            default -> throw new IOException("Unknown RAR3 standard filter");
        };
    }

    /// Converts x86 E8 or E8/E9 relative addresses to their extracted representation.
    private static byte[] filterX86(byte[] input, long offset, boolean includeE9) {
        byte[] result = input.clone();
        int position = 0;
        int filePosition = (int) offset;
        while (position + 4 < result.length) {
            int opcode = result[position] & 0xff;
            position++;
            filePosition++;
            if (opcode != 0xe8 && (!includeE9 || opcode != 0xe9)) continue;
            int address = readInt32(result, position);
            if (address < 0) {
                if (address + filePosition >= 0) writeInt32(result, position, address + X86_ADDRESS_SPACE);
            } else if (address < X86_ADDRESS_SPACE) {
                writeInt32(result, position, address - filePosition);
            }
            position += 4;
            filePosition += 4;
        }
        return result;
    }

    /// Converts Itanium branch bundle offsets to their extracted representation.
    private static byte[] filterItanium(byte[] input, long offset) {
        byte[] result = input.clone();
        int fileOffset = (int) (offset >>> 4);
        for (int bundle = 0; bundle + 21 < result.length; bundle += 16, fileOffset++) {
            int template = (result[bundle] & 0x1f) - 0x10;
            if (template < 0) continue;
            int mask = ITANIUM_SLOT_MASKS[template];
            for (int slot = 0; slot < 3; slot++) {
                if ((mask & 1 << slot) == 0) continue;
                int bitPosition = slot * 41 + 18;
                if (getLittleEndianBits(result, bundle, bitPosition + 24, 4) == 5) {
                    int value = getLittleEndianBits(result, bundle, bitPosition, 20);
                    setLittleEndianBits(result, bundle, bitPosition, value - fileOffset);
                }
            }
        }
        return result;
    }

    /// Restores channel-interleaved bytes encoded with the RAR delta transform.
    private static byte[] filterDelta(byte[] input, int channelCount) throws IOException {
        validateChannels(channelCount);
        byte[] result = new byte[input.length];
        int source = 0;
        for (int channel = 0; channel < channelCount; channel++) {
            int previous = 0;
            for (int target = channel; target < result.length; target += channelCount) {
                previous = previous - input[source++] & 0xff;
                result[target] = (byte) previous;
            }
        }
        return result;
    }

    /// Restores the three color channels and green-channel decorrelation of an RGB image.
    private static byte[] filterRgb(byte[] input, int encodedWidth, int redPosition) throws IOException {
        int width = encodedWidth - 3;
        if (width < 0 || redPosition < 0 || redPosition > 2) {
            throw new IOException("Invalid RAR3 RGB filter parameters");
        }
        byte[] result = new byte[input.length];
        int source = 0;
        for (int channel = 0; channel < 3; channel++) {
            int previous = 0;
            for (int target = channel; target < result.length; target += 3) {
                int predicted = previous;
                int upperPosition = target - width;
                if (upperPosition >= 3) {
                    int upper = result[upperPosition] & 0xff;
                    int upperLeft = result[upperPosition - 3] & 0xff;
                    int linear = previous + upper - upperLeft;
                    int previousError = Math.abs(linear - previous);
                    int upperError = Math.abs(linear - upper);
                    int upperLeftError = Math.abs(linear - upperLeft);
                    predicted = previousError <= upperError && previousError <= upperLeftError
                            ? previous : upperError <= upperLeftError ? upper : upperLeft;
                }
                previous = predicted - input[source++] & 0xff;
                result[target] = (byte) previous;
            }
        }
        for (int position = redPosition; position + 2 < result.length; position += 3) {
            int green = result[position + 1] & 0xff;
            result[position] = (byte) ((result[position] & 0xff) + green);
            result[position + 2] = (byte) ((result[position + 2] & 0xff) + green);
        }
        return result;
    }

    /// Restores adaptively predicted interleaved audio channels.
    private static byte[] filterAudio(byte[] input, int channelCount) throws IOException {
        validateChannels(channelCount);
        byte[] result = new byte[input.length];
        int source = 0;
        for (int channel = 0; channel < channelCount; channel++) {
            int previous = 0;
            int byteCount = 0;
            int[] differences = new int[7];
            int[] deltas = new int[3];
            int[] coefficients = new int[3];
            for (int target = channel; target < result.length; target += channelCount) {
                int predicted = previous * 8 + coefficients[0] * deltas[0]
                        + coefficients[1] * deltas[1] + coefficients[2] * deltas[2];
                predicted = (byte) (predicted >> 3);
                int encoded = input[source++];
                int decoded = predicted - encoded;
                result[target] = (byte) decoded;
                int scaled = encoded * 8;
                differences[0] += Math.abs(scaled);
                differences[1] += Math.abs(scaled - deltas[0]);
                differences[2] += Math.abs(scaled + deltas[0]);
                differences[3] += Math.abs(scaled - deltas[1]);
                differences[4] += Math.abs(scaled + deltas[1]);
                differences[5] += Math.abs(scaled - deltas[2]);
                differences[6] += Math.abs(scaled + deltas[2]);
                int previousDelta = (byte) (decoded - previous);
                previous = decoded;
                deltas[2] = deltas[1];
                deltas[1] = previousDelta - deltas[0];
                deltas[0] = previousDelta;
                if ((byteCount & 0x1f) == 0) {
                    int best = 0;
                    for (int index = 1; index < differences.length; index++) {
                        if (differences[index] < differences[best]) best = index;
                    }
                    int adjustment = best - 1;
                    if (adjustment >= 0) {
                        int coefficient = adjustment / 2;
                        if ((adjustment & 1) == 0) {
                            coefficients[coefficient] = Math.max(-16, coefficients[coefficient] - 1);
                        } else {
                            coefficients[coefficient] = Math.min(16, coefficients[coefficient] + 1);
                        }
                    }
                    Arrays.fill(differences, 0);
                }
                byteCount++;
            }
        }
        return result;
    }

    /// Rejects channel counts that cannot describe the supplied interleaving.
    private static void validateChannels(int channelCount) throws IOException {
        if (channelCount <= 0 || channelCount > 256) {
            throw new IOException("Invalid RAR3 filter channel count");
        }
    }

    /// Reads a little-endian 32-bit integer.
    private static int readInt32(byte[] data, int offset) {
        return data[offset] & 0xff | (data[offset + 1] & 0xff) << 8
                | (data[offset + 2] & 0xff) << 16 | data[offset + 3] << 24;
    }

    /// Writes a little-endian 32-bit integer.
    private static void writeInt32(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    /// Reads an unaligned field from a little-endian Itanium bundle.
    private static int getLittleEndianBits(byte[] data, int base, int position, int count) {
        int byteOffset = base + (position >>> 3);
        long value = (data[byteOffset] & 0xffL) | (data[byteOffset + 1] & 0xffL) << 8
                | (data[byteOffset + 2] & 0xffL) << 16 | (data[byteOffset + 3] & 0xffL) << 24;
        return (int) (value >>> (position & 7) & (1L << count) - 1L);
    }

    /// Replaces an unaligned field in a little-endian Itanium bundle.
    private static void setLittleEndianBits(byte[] data, int base, int position, int bits) {
        int byteOffset = base + (position >>> 3);
        int shift = position & 7;
        long value = (data[byteOffset] & 0xffL) | (data[byteOffset + 1] & 0xffL) << 8
                | (data[byteOffset + 2] & 0xffL) << 16 | (data[byteOffset + 3] & 0xffL) << 24;
        long fieldMask = ((1L << 20) - 1L) << shift;
        value = value & ~fieldMask | ((long) bits << shift & fieldMask);
        data[byteOffset] = (byte) value;
        data[byteOffset + 1] = (byte) (value >>> 8);
        data[byteOffset + 2] = (byte) (value >>> 16);
        data[byteOffset + 3] = (byte) (value >>> 24);
    }
}
