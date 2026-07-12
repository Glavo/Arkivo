// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bcj;

import org.glavo.arkivo.codec.transform.ByteTransform;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

/// Creates stateful branch-conversion transforms shared by XZ and 7z filters.
@NotNullByDefault
public final class BCJTransforms {
    /// Prevents utility-class construction.
    private BCJTransforms() {
    }

    /// Creates an x86 BCJ transform.
    public static ByteTransform x86(boolean encoder, int startOffset) {
        return new X86Transform(encoder, startOffset);
    }

    /// Creates a big-endian PowerPC BCJ transform.
    public static ByteTransform powerPc(boolean encoder, int startOffset) {
        return new PowerPcTransform(encoder, startOffset);
    }

    /// Creates an IA-64 BCJ transform.
    public static ByteTransform ia64(boolean encoder, int startOffset) {
        return new Ia64Transform(encoder, startOffset);
    }

    /// Creates a little-endian ARM BCJ transform.
    public static ByteTransform arm(boolean encoder, int startOffset) {
        return new ArmTransform(encoder, startOffset);
    }

    /// Creates a little-endian ARM-Thumb BCJ transform.
    public static ByteTransform armThumb(boolean encoder, int startOffset) {
        return new ArmThumbTransform(encoder, startOffset);
    }

    /// Creates a big-endian SPARC BCJ transform.
    public static ByteTransform sparc(boolean encoder, int startOffset) {
        return new SparcTransform(encoder, startOffset);
    }

    /// Creates a little-endian ARM64 BCJ transform.
    public static ByteTransform arm64(boolean encoder, int startOffset) {
        return new Arm64Transform(encoder, startOffset);
    }

    /// Creates a little-endian RISC-V BCJ transform.
    public static ByteTransform riscV(boolean encoder, int startOffset) {
        return new RiscVTransform(encoder, startOffset);
    }

    /// Returns a little-endian 32-bit value.
    private static int readIntLittleEndian(byte[] buffer, int offset) {
        return Byte.toUnsignedInt(buffer[offset])
                | Byte.toUnsignedInt(buffer[offset + 1]) << 8
                | Byte.toUnsignedInt(buffer[offset + 2]) << 16
                | buffer[offset + 3] << 24;
    }

    /// Stores a little-endian 32-bit value.
    private static void writeIntLittleEndian(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) value;
        buffer[offset + 1] = (byte) (value >>> 8);
        buffer[offset + 2] = (byte) (value >>> 16);
        buffer[offset + 3] = (byte) (value >>> 24);
    }

    /// Returns a big-endian 32-bit value.
    private static int readIntBigEndian(byte[] buffer, int offset) {
        return buffer[offset] << 24
                | Byte.toUnsignedInt(buffer[offset + 1]) << 16
                | Byte.toUnsignedInt(buffer[offset + 2]) << 8
                | Byte.toUnsignedInt(buffer[offset + 3]);
    }

    /// Stores a big-endian 32-bit value.
    private static void writeIntBigEndian(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value >>> 24);
        buffer[offset + 1] = (byte) (value >>> 16);
        buffer[offset + 2] = (byte) (value >>> 8);
        buffer[offset + 3] = (byte) value;
    }

    /// Converts x86 CALL and JMP relative addresses.
    @NotNullByDefault
    private static final class X86Transform implements ByteTransform {
        /// Whether each relative address is converted to its absolute form.
        private final boolean encoder;

        /// The accepted recent-candidate masks.
        private static final boolean @Unmodifiable [] MASK_ALLOWED = {
                true, true, true, false, true, false, false, false
        };

        /// The tested high-byte index for each recent-candidate mask.
        private static final int @Unmodifiable [] MASK_BIT_INDEX = {0, 1, 2, 2, 3, 3, 3, 3};

        /// The absolute position corresponding to the current buffer prefix.
        private int streamPosition;

        /// The recent candidate-position mask carried across buffer boundaries.
        private int previousMask;

        /// Creates an x86 encoder or decoder.
        private X86Transform(boolean encoder, int startOffset) {
            this.encoder = encoder;
            this.streamPosition = startOffset + 5;
        }

        /// Converts complete x86 branch instructions and retains up to four lookahead bytes.
        @Override
        public int transform(byte[] buffer, int offset, int length) {
            int previousCandidate = offset - 1;
            int limit = offset + length - 5;
            int cursor;
            for (cursor = offset; cursor <= limit; cursor++) {
                if ((buffer[cursor] & 0xfe) != 0xe8) {
                    continue;
                }

                int gap = cursor - previousCandidate;
                if (gap > 3) {
                    previousMask = 0;
                } else {
                    previousMask = previousMask << (gap - 1) & 7;
                    if (previousMask != 0
                            && (!MASK_ALLOWED[previousMask]
                            || isSignExtensionByte(buffer[cursor + 4 - MASK_BIT_INDEX[previousMask]]))) {
                        previousCandidate = cursor;
                        previousMask = previousMask << 1 | 1;
                        continue;
                    }
                }
                previousCandidate = cursor;

                if (isSignExtensionByte(buffer[cursor + 4])) {
                    int source = readIntLittleEndian(buffer, cursor + 1);
                    int converted;
                    while (true) {
                        int instructionPosition = streamPosition + cursor - offset;
                        converted = encoder ? source + instructionPosition : source - instructionPosition;
                        if (previousMask == 0) {
                            break;
                        }
                        int bitIndex = MASK_BIT_INDEX[previousMask] * 8;
                        if (!isSignExtensionByte((byte) (converted >>> (24 - bitIndex)))) {
                            break;
                        }
                        source = converted ^ ((1 << (32 - bitIndex)) - 1);
                    }
                    converted = converted << 7 >> 7;
                    writeIntLittleEndian(buffer, cursor + 1, converted);
                    cursor += 4;
                } else {
                    previousMask = previousMask << 1 | 1;
                }
            }

            int trailingGap = cursor - previousCandidate;
            previousMask = trailingGap > 3 ? 0 : previousMask << (trailingGap - 1);
            int processed = cursor - offset;
            streamPosition += processed;
            return processed;
        }

        /// Returns whether a byte is a valid sign extension for an x86 relative address.
        private static boolean isSignExtensionByte(byte value) {
            return value == 0 || value == (byte) 0xff;
        }
    }

    /// Converts big-endian PowerPC branch instructions.
    @NotNullByDefault
    private static final class PowerPcTransform implements ByteTransform {
        /// Whether each relative address is converted to its absolute form.
        private final boolean encoder;

        /// The absolute position corresponding to the current buffer prefix.
        private int streamPosition;

        /// Creates a PowerPC encoder or decoder.
        private PowerPcTransform(boolean encoder, int startOffset) {
            this.encoder = encoder;
            this.streamPosition = startOffset;
        }

        /// Converts complete four-byte branch instructions.
        @Override
        public int transform(byte[] buffer, int offset, int length) {
            int cursor;
            int limit = offset + length - 4;
            for (cursor = offset; cursor <= limit; cursor += 4) {
                if ((buffer[cursor] & 0xfc) == 0x48 && (buffer[cursor + 3] & 3) == 1) {
                    int instruction = readIntBigEndian(buffer, cursor);
                    int position = streamPosition + cursor - offset;
                    int adjustment = encoder ? position : -position;
                    int converted = 0x48000001 | (instruction + adjustment & 0x03fffffc);
                    writeIntBigEndian(buffer, cursor, converted);
                }
            }
            int processed = cursor - offset;
            streamPosition += processed;
            return processed;
        }
    }

    /// Converts branch slots in IA-64 instruction bundles.
    @NotNullByDefault
    private static final class Ia64Transform implements ByteTransform {
        /// The branch-slot masks indexed by the five-bit bundle template.
        private static final int @Unmodifiable [] BRANCH_SLOTS = {
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                4, 4, 6, 6, 0, 0, 7, 7,
                4, 4, 0, 0, 4, 4, 0, 0
        };

        /// Whether each relative address is converted to its absolute form.
        private final boolean encoder;

        /// The absolute position corresponding to the current bundle prefix.
        private int streamPosition;

        /// Creates an IA-64 encoder or decoder.
        private Ia64Transform(boolean encoder, int startOffset) {
            this.encoder = encoder;
            this.streamPosition = startOffset;
        }

        /// Converts complete 16-byte IA-64 instruction bundles.
        @Override
        public int transform(byte[] buffer, int offset, int length) {
            int cursor;
            int limit = offset + length - 16;
            for (cursor = offset; cursor <= limit; cursor += 16) {
                int activeSlots = BRANCH_SLOTS[buffer[cursor] & 0x1f];
                for (int slot = 0, slotBit = 5; slot < 3; slot++, slotBit += 41) {
                    if ((activeSlots & 1 << slot) == 0) {
                        continue;
                    }
                    int byteOffset = slotBit >>> 3;
                    int bitOffset = slotBit & 7;
                    long packed = 0L;
                    for (int index = 0; index < 6; index++) {
                        packed |= (long) Byte.toUnsignedInt(buffer[cursor + byteOffset + index]) << (index * 8);
                    }
                    long instruction = packed >>> bitOffset;
                    if ((instruction >>> 37 & 0x0f) != 5 || (instruction >>> 9 & 7) != 0) {
                        continue;
                    }

                    int source = (int) (instruction >>> 13 & 0x0fffff);
                    source |= (int) (instruction >>> 36 & 1) << 20;
                    source <<= 4;
                    int position = streamPosition + cursor - offset;
                    int converted = encoder ? source + position : source - position;
                    converted >>>= 4;

                    instruction &= ~(0x8fffffL << 13);
                    instruction |= (long) (converted & 0x0fffff) << 13;
                    instruction |= (long) (converted & 0x100000) << 16;
                    packed &= (1L << bitOffset) - 1L;
                    packed |= instruction << bitOffset;
                    for (int index = 0; index < 6; index++) {
                        buffer[cursor + byteOffset + index] = (byte) (packed >>> (index * 8));
                    }
                }
            }
            int processed = cursor - offset;
            streamPosition += processed;
            return processed;
        }
    }

    /// Converts little-endian ARM branch-with-link instructions.
    @NotNullByDefault
    private static final class ArmTransform implements ByteTransform {
        /// Whether each relative address is converted to its absolute form.
        private final boolean encoder;

        /// The absolute position corresponding to the current instruction prefix.
        private int streamPosition;

        /// Creates an ARM encoder or decoder.
        private ArmTransform(boolean encoder, int startOffset) {
            this.encoder = encoder;
            this.streamPosition = startOffset + 8;
        }

        /// Converts complete four-byte ARM instructions.
        @Override
        public int transform(byte[] buffer, int offset, int length) {
            int cursor;
            int limit = offset + length - 4;
            for (cursor = offset; cursor <= limit; cursor += 4) {
                if (Byte.toUnsignedInt(buffer[cursor + 3]) == 0xeb) {
                    int source = Byte.toUnsignedInt(buffer[cursor])
                            | Byte.toUnsignedInt(buffer[cursor + 1]) << 8
                            | Byte.toUnsignedInt(buffer[cursor + 2]) << 16;
                    source <<= 2;
                    int position = streamPosition + cursor - offset;
                    int converted = encoder ? source + position : source - position;
                    converted >>>= 2;
                    buffer[cursor] = (byte) converted;
                    buffer[cursor + 1] = (byte) (converted >>> 8);
                    buffer[cursor + 2] = (byte) (converted >>> 16);
                }
            }
            int processed = cursor - offset;
            streamPosition += processed;
            return processed;
        }
    }

    /// Converts little-endian ARM-Thumb BL instructions.
    @NotNullByDefault
    private static final class ArmThumbTransform implements ByteTransform {
        /// Whether each relative address is converted to its absolute form.
        private final boolean encoder;

        /// The absolute position corresponding to the current instruction prefix.
        private int streamPosition;

        /// Creates an ARM-Thumb encoder or decoder.
        private ArmThumbTransform(boolean encoder, int startOffset) {
            this.encoder = encoder;
            this.streamPosition = startOffset + 4;
        }

        /// Converts complete four-byte ARM-Thumb branch instructions on two-byte boundaries.
        @Override
        public int transform(byte[] buffer, int offset, int length) {
            int cursor;
            int limit = offset + length - 4;
            for (cursor = offset; cursor <= limit; cursor += 2) {
                if ((buffer[cursor + 1] & 0xf8) == 0xf0 && (buffer[cursor + 3] & 0xf8) == 0xf8) {
                    int source = (buffer[cursor + 1] & 7) << 19
                            | Byte.toUnsignedInt(buffer[cursor]) << 11
                            | (buffer[cursor + 3] & 7) << 8
                            | Byte.toUnsignedInt(buffer[cursor + 2]);
                    source <<= 1;
                    int position = streamPosition + cursor - offset;
                    int converted = encoder ? source + position : source - position;
                    converted >>>= 1;
                    buffer[cursor + 1] = (byte) (0xf0 | converted >>> 19 & 7);
                    buffer[cursor] = (byte) (converted >>> 11);
                    buffer[cursor + 3] = (byte) (0xf8 | converted >>> 8 & 7);
                    buffer[cursor + 2] = (byte) converted;
                    cursor += 2;
                }
            }
            int processed = cursor - offset;
            streamPosition += processed;
            return processed;
        }
    }

    /// Converts big-endian SPARC CALL instructions.
    @NotNullByDefault
    private static final class SparcTransform implements ByteTransform {
        /// Whether each relative address is converted to its absolute form.
        private final boolean encoder;

        /// The absolute position corresponding to the current instruction prefix.
        private int streamPosition;

        /// Creates a SPARC encoder or decoder.
        private SparcTransform(boolean encoder, int startOffset) {
            this.encoder = encoder;
            this.streamPosition = startOffset;
        }

        /// Converts complete four-byte SPARC instructions.
        @Override
        public int transform(byte[] buffer, int offset, int length) {
            int cursor;
            int limit = offset + length - 4;
            for (cursor = offset; cursor <= limit; cursor += 4) {
                if ((buffer[cursor] == 0x40 && (buffer[cursor + 1] & 0xc0) == 0)
                        || (buffer[cursor] == 0x7f && (buffer[cursor + 1] & 0xc0) == 0xc0)) {
                    int instruction = readIntBigEndian(buffer, cursor);
                    int positionWords = (streamPosition + cursor - offset) >>> 2;
                    int converted = instruction + (encoder ? positionWords : -positionWords);
                    converted = converted << 9 >> 9;
                    converted = 0x40000000 | converted & 0x3fffffff;
                    writeIntBigEndian(buffer, cursor, converted);
                }
            }
            int processed = cursor - offset;
            streamPosition += processed;
            return processed;
        }
    }

    /// Converts ARM64 BL and ADRP relative addresses.
    @NotNullByDefault
    private static final class Arm64Transform implements ByteTransform {
        /// Whether relative addresses are converted to absolute addresses.
        private final boolean encoder;

        /// The absolute position corresponding to the current buffer prefix.
        private int streamPosition;

        /// Creates an ARM64 encoder or decoder.
        private Arm64Transform(boolean encoder, int startOffset) {
            this.encoder = encoder;
            this.streamPosition = startOffset;
        }

        /// Converts every complete four-byte instruction.
        @Override
        public int transform(byte[] buffer, int offset, int length) {
            int end = offset + length - 4;
            int index;
            for (index = offset; index <= end; index += 4) {
                int high = buffer[index + 3];
                int instruction;
                if ((high & 0xfc) == 0x94) {
                    instruction = readIntLittleEndian(buffer, index);
                    int pc = (streamPosition + index - offset) >>> 2;
                    int adjusted = encoder ? instruction + pc : instruction - pc;
                    writeIntLittleEndian(buffer, index, 0x9400_0000 | adjusted & 0x03ff_ffff);
                } else if ((high & 0x9f) == 0x90) {
                    instruction = readIntLittleEndian(buffer, index);
                    int source = instruction >>> 29 & 3 | instruction >>> 3 & 0x001f_fffc;
                    if (((source + 0x0002_0000) & 0x001c_0000) != 0) {
                        continue;
                    }
                    int pc = (streamPosition + index - offset) >>> 12;
                    int destination = encoder ? source + pc : source - pc;
                    instruction &= 0x9000_001f;
                    instruction |= (destination & 3) << 29;
                    instruction |= (destination & 0x0003_fffc) << 3;
                    instruction |= -(destination & 0x0002_0000) & 0x00e0_0000;
                    writeIntLittleEndian(buffer, index, instruction);
                }
            }
            int processed = index - offset;
            streamPosition += processed;
            return processed;
        }
    }

    /// Converts RISC-V JAL and AUIPC instruction pairs.
    @NotNullByDefault
    private static final class RiscVTransform implements ByteTransform {
        /// Whether relative addresses are converted to the encoded representation.
        private final boolean encoder;

        /// The absolute position corresponding to the current buffer prefix.
        private int streamPosition;

        /// Creates a RISC-V encoder or decoder.
        private RiscVTransform(boolean encoder, int startOffset) {
            this.encoder = encoder;
            this.streamPosition = startOffset;
        }

        /// Converts complete RISC-V branch candidates while retaining an instruction tail.
        @Override
        public int transform(byte[] buffer, int offset, int length) {
            int end = offset + length - 8;
            int index;
            for (index = offset; index <= end; index += 2) {
                int instruction = Byte.toUnsignedInt(buffer[index]);
                if (instruction == 0xef) {
                    int byte1 = Byte.toUnsignedInt(buffer[index + 1]);
                    if ((byte1 & 0x0d) != 0) {
                        continue;
                    }
                    if (encoder) {
                        encodeJal(buffer, offset, index, byte1);
                    } else {
                        decodeJal(buffer, offset, index, byte1);
                    }
                    index += 2;
                } else if ((instruction & 0x7f) == 0x17) {
                    int word = readIntLittleEndian(buffer, index);
                    int consumed = encoder
                            ? encodeAuipc(buffer, offset, index, word)
                            : decodeAuipc(buffer, offset, index, word);
                    index += consumed - 2;
                }
            }
            int processed = index - offset;
            streamPosition += processed;
            return processed;
        }

        /// Encodes one JAL immediate into its absolute big-endian representation.
        private void encodeJal(byte[] buffer, int offset, int index, int byte1) {
            int byte2 = Byte.toUnsignedInt(buffer[index + 2]);
            int byte3 = Byte.toUnsignedInt(buffer[index + 3]);
            int address = (byte1 & 0xf0) << 8
                    | (byte2 & 0x0f) << 16
                    | (byte2 & 0x10) << 7
                    | (byte2 & 0xe0) >>> 4
                    | (byte3 & 0x7f) << 4
                    | (byte3 & 0x80) << 13;
            address += streamPosition + index - offset;
            buffer[index + 1] = (byte) (byte1 & 0x0f | address >>> 13 & 0xf0);
            buffer[index + 2] = (byte) (address >>> 9);
            buffer[index + 3] = (byte) (address >>> 1);
        }

        /// Decodes one JAL absolute representation into a relative immediate.
        private void decodeJal(byte[] buffer, int offset, int index, int byte1) {
            int byte2 = Byte.toUnsignedInt(buffer[index + 2]);
            int byte3 = Byte.toUnsignedInt(buffer[index + 3]);
            int address = (byte1 & 0xf0) << 13 | byte2 << 9 | byte3 << 1;
            address -= streamPosition + index - offset;
            buffer[index + 1] = (byte) (byte1 & 0x0f | address >>> 8 & 0xf0);
            buffer[index + 2] = (byte) (address >>> 16 & 0x0f
                    | address >>> 7 & 0x10
                    | address << 4 & 0xe0);
            buffer[index + 3] = (byte) (address >>> 4 & 0x7f | address >>> 13 & 0x80);
        }

        /// Encodes one AUIPC candidate and returns the number of bytes that must be skipped.
        private int encodeAuipc(byte[] buffer, int offset, int index, int instruction) {
            if ((instruction & 0x0e80) != 0) {
                int second = readIntLittleEndian(buffer, index + 4);
                if ((((instruction << 8) ^ second) & 0x000f_8003) != 3) {
                    return 6;
                }
                int address = (instruction & 0xffff_f000) + (second >> 20);
                address += streamPosition + index - offset;
                instruction = 0x17 | 2 << 7 | second << 12;
                writeIntLittleEndian(buffer, index, instruction);
                writeIntBigEndian(buffer, index + 4, address);
                return 8;
            }

            int sourceRegister = instruction >>> 27;
            if (((instruction - 0x3100) & 0x3f80) >= (sourceRegister & 0x1d)) {
                return 4;
            }
            int address = readIntLittleEndian(buffer, index + 4);
            int second = instruction >>> 12 | address << 20;
            instruction = 0x17 | sourceRegister << 7 | address & 0xffff_f000;
            writeIntLittleEndian(buffer, index, instruction);
            writeIntLittleEndian(buffer, index + 4, second);
            return 8;
        }

        /// Decodes one AUIPC candidate and returns the number of bytes that must be skipped.
        private int decodeAuipc(byte[] buffer, int offset, int index, int instruction) {
            int second;
            if ((instruction & 0x0e80) != 0) {
                second = readIntLittleEndian(buffer, index + 4);
                if ((((instruction << 8) ^ second) & 0x000f_8003) != 3) {
                    return 6;
                }
                int address = (instruction & 0xffff_f000) + (second >>> 20);
                instruction = 0x17 | 2 << 7 | second << 12;
                second = address;
            } else {
                int secondSourceRegister = instruction >>> 27;
                if (((instruction - 0x3100) & 0x3f80) >= (secondSourceRegister & 0x1d)) {
                    return 4;
                }
                int address = readIntBigEndian(buffer, index + 4);
                address -= streamPosition + index - offset;
                second = instruction >>> 12 | address << 20;
                instruction = 0x17 | secondSourceRegister << 7 | address + 0x800 & 0xffff_f000;
            }
            writeIntLittleEndian(buffer, index, instruction);
            writeIntLittleEndian(buffer, index + 4, second);
            return 8;
        }
    }
}
