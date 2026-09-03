// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies RAR3 virtual-machine execution through checksummed synthetic bytecode programs.
@NotNullByDefault
final class Rar3VmExecutionTest {
    /// Verifies arithmetic, logical, byte-register, zero-extension, and sign-extension instructions.
    @Test
    void executesArithmeticAndWidthConversions() throws IOException {
        ProgramWriter writer = new ProgramWriter();
        writer.binaryRegisterImmediate(0, false, 0, 5);
        writer.binaryRegisterImmediate(2, false, 0, 3);
        writer.binaryRegisterImmediate(3, false, 0, 1);
        writer.unaryRegister(6, false, 0);
        writer.unaryRegister(7, false, 0);
        writer.binaryRegisterImmediate(9, false, 0, 3);
        writer.binaryRegisterImmediate(11, false, 0, 8);
        writer.binaryRegisterImmediate(10, false, 0, 10);
        writer.unaryRegister(23, false, 0);
        writer.unaryRegister(27, false, 0);
        writer.binaryRegisterImmediate(35, false, 0, 3);
        writer.binaryRegisterImmediate(36, false, 0, 2);
        writer.moveDirectFromRegister(0, 0);

        writer.binaryRegisterImmediate(0, false, 1, 0x1234_5678);
        writer.binaryRegisterImmediate(0, true, 1, 0xab);
        writer.moveDirectFromRegister(4, 1);
        writer.opcode(32);
        writer.register(2);
        writer.register(1);
        writer.moveDirectFromRegister(8, 2);
        writer.opcode(33);
        writer.register(3);
        writer.register(1);
        writer.moveDirectFromRegister(12, 3);
        writer.ret();

        byte[] output = execute(writer, new byte[16], new int[7], 0, new byte[0], 0L);
        assertEquals(13, ByteArrayAccess.readIntLittleEndian(output, 0));
        assertEquals(0x1234_56ab, ByteArrayAccess.readIntLittleEndian(output, 4));
        assertEquals(0xab, ByteArrayAccess.readIntLittleEndian(output, 8));
        assertEquals(0xffff_ffab, ByteArrayAccess.readIntLittleEndian(output, 12));
    }

    /// Verifies initial registers and indirect and indexed memory operands share masked VM memory.
    @Test
    void executesRegisterBasedMemoryAddressing() throws IOException {
        ProgramWriter writer = new ProgramWriter();
        writer.opcodeWithMode(0, false);
        writer.indirect(0);
        writer.immediate(0x1122_3344, false);
        writer.opcodeWithMode(0, false);
        writer.indexed(0, 4);
        writer.immediate(0x5566_7788, false);
        writer.opcodeWithMode(0, false);
        writer.register(1);
        writer.indirect(0);
        writer.moveDirectFromRegister(0, 1);
        writer.ret();

        int[] registers = new int[7];
        registers[0] = 4;
        byte[] output = execute(writer, new byte[12], registers, 1, new byte[0], 0L);

        assertEquals(0x1122_3344, ByteArrayAccess.readIntLittleEndian(output, 0));
        assertEquals(0x1122_3344, ByteArrayAccess.readIntLittleEndian(output, 4));
        assertEquals(0x5566_7788, ByteArrayAccess.readIntLittleEndian(output, 8));
    }

    /// Verifies register-targeted CALL, RET, JMP, comparison, and conditional-jump control flow.
    @Test
    void executesControlFlowAndStackOperations() throws IOException {
        ProgramWriter callProgram = new ProgramWriter();
        callProgram.binaryRegisterImmediate(0, false, 0, 5);
        callProgram.binaryRegisterImmediate(0, false, 1, 7);
        callProgram.opcode(21);
        callProgram.register(0);
        callProgram.moveDirectImmediate(0, 42);
        callProgram.opcode(8);
        callProgram.register(1);
        callProgram.moveDirectImmediate(0, 7);
        callProgram.ret();
        callProgram.ret();
        assertEquals(
                42,
                ByteArrayAccess.readIntLittleEndian(
                        execute(callProgram, new byte[4], new int[7], 0, new byte[0], 0L),
                        0
                )
        );

        ProgramWriter branchProgram = new ProgramWriter();
        branchProgram.binaryRegisterImmediate(0, false, 0, 5);
        branchProgram.binaryRegisterImmediate(1, false, 0, 5);
        branchProgram.binaryRegisterImmediate(0, false, 1, 5);
        branchProgram.opcode(4);
        branchProgram.register(1);
        branchProgram.moveDirectImmediate(0, 1);
        branchProgram.moveDirectImmediate(0, 2);
        branchProgram.ret();
        assertEquals(
                2,
                ByteArrayAccess.readIntLittleEndian(
                        execute(branchProgram, new byte[4], new int[7], 0, new byte[0], 0L),
                        0
                )
        );
    }

    /// Verifies every conditional branch against the zero, sign, and unsigned carry flag combinations.
    @Test
    void executesConditionalBranches() throws IOException {
        assertEquals(1, executeConditionalBranch(4, 7, 7));
        assertEquals(0, executeConditionalBranch(4, 7, 6));
        assertEquals(1, executeConditionalBranch(5, 7, 6));
        assertEquals(0, executeConditionalBranch(5, 7, 7));
        assertEquals(1, executeConditionalBranch(13, 0, 1));
        assertEquals(0, executeConditionalBranch(13, 1, 0));
        assertEquals(1, executeConditionalBranch(14, 1, 0));
        assertEquals(0, executeConditionalBranch(14, 0, 1));
        assertEquals(1, executeConditionalBranch(15, 0, 1));
        assertEquals(0, executeConditionalBranch(15, 1, 0));
        assertEquals(1, executeConditionalBranch(16, 0, 1));
        assertEquals(1, executeConditionalBranch(16, 1, 1));
        assertEquals(0, executeConditionalBranch(16, 2, 1));
        assertEquals(1, executeConditionalBranch(17, 2, 1));
        assertEquals(0, executeConditionalBranch(17, 1, 1));
        assertEquals(0, executeConditionalBranch(17, 0, 1));
        assertEquals(1, executeConditionalBranch(18, 2, 1));
        assertEquals(1, executeConditionalBranch(18, 1, 1));
        assertEquals(0, executeConditionalBranch(18, 0, 1));
    }

    /// Verifies value and flag stacks together with complete register save and restore instructions.
    @Test
    void executesStackAndRegisterSaveInstructions() throws IOException {
        ProgramWriter writer = new ProgramWriter();
        writer.binaryRegisterImmediate(0, false, 0, 11);
        writer.binaryRegisterImmediate(0, false, 1, 22);
        writer.opcode(28);
        writer.binaryRegisterImmediate(0, false, 0, 33);
        writer.binaryRegisterImmediate(0, false, 1, 44);
        writer.opcode(29);
        writer.moveDirectFromRegister(0, 0);
        writer.moveDirectFromRegister(4, 1);

        writer.opcode(19);
        writer.register(0);
        writer.binaryRegisterImmediate(0, false, 0, 99);
        writer.opcode(20);
        writer.register(2);
        writer.moveDirectFromRegister(8, 2);

        writer.binaryRegisterImmediate(0, false, 3, 0);
        writer.binaryRegisterImmediate(1, false, 3, 1);
        writer.opcode(30);
        writer.binaryRegisterImmediate(1, false, 3, 0);
        writer.opcode(31);
        writer.binaryRegisterImmediate(0, false, 4, 5);
        writer.binaryRegisterImmediate(37, false, 4, 2);
        writer.moveDirectFromRegister(12, 4);
        writer.ret();

        byte[] output = execute(writer, new byte[16], new int[7], 0, new byte[0], 0L);
        assertEquals(11, ByteArrayAccess.readIntLittleEndian(output, 0));
        assertEquals(22, ByteArrayAccess.readIntLittleEndian(output, 4));
        assertEquals(11, ByteArrayAccess.readIntLittleEndian(output, 8));
        assertEquals(8, ByteArrayAccess.readIntLittleEndian(output, 12));
    }

    /// Verifies shifts, carry arithmetic, exchange, test, zero division, and the compatibility no-op.
    @Test
    void executesRemainingArithmeticInstructions() throws IOException {
        ProgramWriter writer = new ProgramWriter();
        writer.binaryRegisterImmediate(0, false, 0, 0x8000_0001);
        writer.binaryRegisterImmediate(24, false, 0, 1);
        writer.binaryRegisterImmediate(0, false, 1, 10);
        writer.binaryRegisterImmediate(37, false, 1, 5);
        writer.moveDirectFromRegister(0, 0);
        writer.moveDirectFromRegister(4, 1);

        writer.binaryRegisterImmediate(0, false, 2, 1);
        writer.binaryRegisterImmediate(25, false, 2, 1);
        writer.binaryRegisterImmediate(0, false, 3, 5);
        writer.binaryRegisterImmediate(38, false, 3, 1);
        writer.moveDirectFromRegister(8, 2);
        writer.moveDirectFromRegister(12, 3);

        writer.binaryRegisterImmediate(0, false, 4, 0x8000_0000);
        writer.binaryRegisterImmediate(26, false, 4, 1);
        writer.binaryRegisterImmediate(0, false, 5, 7);
        writer.opcodeWithMode(34, false);
        writer.register(4);
        writer.register(5);
        writer.moveDirectFromRegister(16, 4);
        writer.moveDirectFromRegister(20, 5);

        writer.binaryRegisterImmediate(0, false, 6, 9);
        writer.binaryRegisterImmediate(36, false, 6, 0);
        writer.moveDirectFromRegister(24, 6);
        writer.binaryRegisterImmediate(12, false, 6, 9);
        writer.opcode(39);
        writer.ret();

        byte[] output = execute(writer, new byte[28], new int[7], 0, new byte[0], 0L);
        assertEquals(2, ByteArrayAccess.readIntLittleEndian(output, 0));
        assertEquals(16, ByteArrayAccess.readIntLittleEndian(output, 4));
        assertEquals(0, ByteArrayAccess.readIntLittleEndian(output, 8));
        assertEquals(3, ByteArrayAccess.readIntLittleEndian(output, 12));
        assertEquals(7, ByteArrayAccess.readIntLittleEndian(output, 16));
        assertEquals(0xc000_0000, ByteArrayAccess.readIntLittleEndian(output, 20));
        assertEquals(9, ByteArrayAccess.readIntLittleEndian(output, 24));
    }

    /// Verifies the fixed global block exposes invocation registers, offsets, and the execution count.
    @Test
    void exposesInvocationMetadata() throws IOException {
        ProgramWriter writer = new ProgramWriter();
        writer.configureOutput(Rar3Vm.GLOBAL_ADDRESS, Rar3Vm.FIXED_GLOBAL_SIZE);
        writer.ret();
        Rar3Vm.Program program = Rar3Vm.compile(writer.toByteArray());
        int[] registers = new int[7];
        registers[0] = 11;
        registers[2] = 22;
        long offset = 0x1122_3344_5566_7788L;

        byte[] first = program.execute(new byte[3], registers, 0b101, new byte[0], offset);
        assertEquals(11, ByteArrayAccess.readIntLittleEndian(first, 0));
        assertEquals(0, ByteArrayAccess.readIntLittleEndian(first, 4));
        assertEquals(22, ByteArrayAccess.readIntLittleEndian(first, 8));
        assertEquals(Rar3Vm.GLOBAL_ADDRESS, ByteArrayAccess.readIntLittleEndian(first, 12));
        assertEquals(3, ByteArrayAccess.readIntLittleEndian(first, 16));
        assertEquals(0, ByteArrayAccess.readIntLittleEndian(first, 20));
        assertEquals(0x5566_7788, ByteArrayAccess.readIntLittleEndian(first, 24));
        assertEquals(Rar3Vm.FIXED_GLOBAL_SIZE, ByteArrayAccess.readIntLittleEndian(first, 0x1c));
        assertEquals(Rar3Vm.GLOBAL_ADDRESS, ByteArrayAccess.readIntLittleEndian(first, 0x20));
        assertEquals(0x5566_7788, ByteArrayAccess.readIntLittleEndian(first, 0x24));
        assertEquals(0x1122_3344, ByteArrayAccess.readIntLittleEndian(first, 0x28));
        assertEquals(0, ByteArrayAccess.readIntLittleEndian(first, 0x2c));

        byte[] second = program.execute(new byte[0], registers, 0b101, new byte[0], offset);
        assertEquals(1, ByteArrayAccess.readIntLittleEndian(second, 20));
        assertEquals(1, ByteArrayAccess.readIntLittleEndian(second, 0x2c));
    }

    /// Verifies static global bytes and explicitly retained dynamic globals survive at the documented VM address.
    @Test
    void loadsStaticAndPersistentGlobalData() throws IOException {
        ProgramWriter staticWriter = new ProgramWriter(new byte[]{7, 8});
        staticWriter.configureOutput(Rar3Vm.GLOBAL_ADDRESS + Rar3Vm.FIXED_GLOBAL_SIZE, 2);
        staticWriter.ret();
        assertArrayEquals(
                new byte[]{7, 8},
                execute(staticWriter, new byte[0], new int[7], 0, new byte[0], 0L)
        );

        ProgramWriter persistentWriter = new ProgramWriter();
        persistentWriter.configureOutput(Rar3Vm.GLOBAL_ADDRESS + Rar3Vm.FIXED_GLOBAL_SIZE, 2);
        persistentWriter.moveDirectImmediate(Rar3Vm.GLOBAL_ADDRESS + 0x30, 2);
        persistentWriter.ret();
        Rar3Vm.Program persistentProgram = Rar3Vm.compile(persistentWriter.toByteArray());
        assertArrayEquals(
                new byte[]{1, 2},
                persistentProgram.execute(new byte[0], new int[7], 0, new byte[]{1, 2}, 0L)
        );
        assertArrayEquals(
                new byte[]{1, 2},
                persistentProgram.execute(new byte[0], new int[7], 0, new byte[]{9, 9}, 1L)
        );
    }

    /// Verifies malformed programs, invalid invocation shapes, and out-of-memory output ranges are rejected.
    @Test
    void validatesCompilationAndInvocationBoundaries() throws IOException {
        assertThrows(NullPointerException.class, () -> Rar3Vm.compile(null));
        assertThrows(IOException.class, () -> Rar3Vm.compile(new byte[0]));
        assertThrows(IOException.class, () -> Rar3Vm.compile(new byte[]{0}));
        assertThrows(IOException.class, () -> Rar3Vm.compile(new byte[]{0, 1}));
        assertThrows(
                IOException.class,
                () -> Rar3Vm.compile(
                        new ProgramWriter(new byte[Rar3Vm.GLOBAL_SIZE - Rar3Vm.FIXED_GLOBAL_SIZE + 1])
                                .toByteArray()
                )
        );

        ProgramWriter returnWriter = new ProgramWriter();
        returnWriter.ret();
        Rar3Vm.Program program = Rar3Vm.compile(returnWriter.toByteArray());
        assertThrows(
                IOException.class,
                () -> program.execute(new byte[Rar3Vm.GLOBAL_ADDRESS + 1], new int[7], 0, new byte[0], 0L)
        );
        assertThrows(IOException.class, () -> program.execute(new byte[0], new int[6], 0, new byte[0], 0L));
        assertThrows(NullPointerException.class, () -> program.execute(null, new int[7], 0, new byte[0], 0L));
        assertThrows(NullPointerException.class, () -> program.execute(new byte[0], null, 0, new byte[0], 0L));
        assertThrows(NullPointerException.class, () -> program.execute(new byte[0], new int[7], 0, null, 0L));

        ProgramWriter overflowingOutput = new ProgramWriter();
        overflowingOutput.configureOutput(Rar3Vm.MEMORY_MASK, 2);
        overflowingOutput.ret();
        assertThrows(
                IOException.class,
                () -> execute(overflowingOutput, new byte[0], new int[7], 0, new byte[0], 0L)
        );
    }

    /// Compiles and executes one generated program.
    private static byte[] execute(
            ProgramWriter writer,
            byte[] input,
            int[] registers,
            int registerMask,
            byte[] global,
            long offset
    ) throws IOException {
        return Rar3Vm.compile(writer.toByteArray()).execute(input, registers, registerMask, global, offset);
    }

    /// Executes one comparison followed by a conditional branch and returns whether it was taken.
    private static int executeConditionalBranch(int opcode, int left, int right) throws IOException {
        ProgramWriter writer = new ProgramWriter();
        writer.binaryRegisterImmediate(0, false, 0, 6);
        writer.binaryRegisterImmediate(0, false, 1, left);
        writer.binaryRegisterImmediate(1, false, 1, right);
        writer.opcode(opcode);
        writer.register(0);
        writer.moveDirectImmediate(0, 0);
        writer.ret();
        writer.moveDirectImmediate(0, 1);
        writer.ret();
        return ByteArrayAccess.readIntLittleEndian(
                execute(writer, new byte[4], new int[7], 0, new byte[0], 0L),
                0
        );
    }

    /// Writes one checksummed RAR3 VM program using specification-level instruction and operand encodings.
    @NotNullByDefault
    private static final class ProgramWriter {
        /// Completed encoded bytes.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        /// The partially assembled output byte.
        private int currentByte;

        /// The number of high bits already assigned in the current byte.
        private int currentBitCount;

        /// Creates a program without static global data.
        private ProgramWriter() {
            write(0, 1);
        }

        /// Creates a program with the supplied static global bytes.
        private ProgramWriter(byte[] staticData) {
            write(1, 1);
            writeEncodedUint32(staticData.length - 1);
            writeBytes(staticData);
        }

        /// Writes a two-operand register/immediate instruction carrying a byte-mode flag.
        private void binaryRegisterImmediate(int opcode, boolean byteMode, int register, int value) {
            opcodeWithMode(opcode, byteMode);
            register(register);
            immediate(value, byteMode);
        }

        /// Writes a one-operand register instruction carrying a byte-mode flag.
        private void unaryRegister(int opcode, boolean byteMode, int register) {
            opcodeWithMode(opcode, byteMode);
            register(register);
        }

        /// Writes a dword MOV from an immediate value to direct memory.
        private void moveDirectImmediate(int address, int value) {
            opcodeWithMode(0, false);
            direct(address);
            immediate(value, false);
        }

        /// Writes a dword MOV from a register to direct memory.
        private void moveDirectFromRegister(int address, int register) {
            opcodeWithMode(0, false);
            direct(address);
            register(register);
        }

        /// Selects a VM-memory output range through the fixed global block.
        private void configureOutput(int address, int length) {
            moveDirectImmediate(Rar3Vm.GLOBAL_ADDRESS + 0x1c, length);
            moveDirectImmediate(Rar3Vm.GLOBAL_ADDRESS + 0x20, address);
        }

        /// Writes a RET instruction.
        private void ret() {
            opcode(22);
        }

        /// Writes an opcode followed by its byte-mode flag.
        private void opcodeWithMode(int opcode, boolean byteMode) {
            opcode(opcode);
            write(byteMode ? 1 : 0, 1);
        }

        /// Writes the variable-width opcode encoding.
        private void opcode(int opcode) {
            if (opcode < 0 || opcode > 39) {
                throw new IllegalArgumentException("Invalid synthetic VM opcode");
            }
            if (opcode < 8) {
                write(opcode, 4);
            } else {
                write(opcode + 24, 6);
            }
        }

        /// Writes a register operand.
        private void register(int register) {
            write(1, 1);
            write(register, 3);
        }

        /// Writes an immediate operand for the active width.
        private void immediate(int value, boolean byteMode) {
            write(0, 2);
            if (byteMode) {
                write(value, 8);
            } else {
                writeEncodedUint32(value);
            }
        }

        /// Writes a register-indirect memory operand.
        private void indirect(int register) {
            write(0b010, 3);
            write(register, 3);
        }

        /// Writes a register-plus-displacement memory operand.
        private void indexed(int register, int displacement) {
            write(0b0110, 4);
            write(register, 3);
            writeEncodedUint32(displacement);
        }

        /// Writes a direct memory operand.
        private void direct(int address) {
            write(0b0111, 4);
            writeEncodedUint32(address);
        }

        /// Writes a RAR3 variable-width unsigned integer.
        private void writeEncodedUint32(int value) {
            if (value >= 0 && value <= 0x0f) {
                write(0, 2);
                write(value, 4);
            } else if (value >= 0 && value <= 0xff) {
                write(1, 2);
                write(value, 8);
            } else if (value >= 0 && value <= 0xffff) {
                write(2, 2);
                write(value, 16);
            } else {
                write(3, 2);
                write(value, 32);
            }
        }

        /// Writes complete bytes without inserting alignment padding.
        private void writeBytes(byte[] values) {
            for (byte value : values) {
                write(value & 0xff, 8);
            }
        }

        /// Writes the requested low bits from most to least significant.
        private void write(int value, int count) {
            for (int bit = count - 1; bit >= 0; bit--) {
                currentByte = currentByte << 1 | value >>> bit & 1;
                currentBitCount++;
                if (currentBitCount == 8) {
                    output.write(currentByte);
                    currentByte = 0;
                    currentBitCount = 0;
                }
            }
        }

        /// Returns checksummed bytecode with zero padding in the final body byte.
        private byte[] toByteArray() {
            if (currentBitCount != 0) {
                output.write(currentByte << (8 - currentBitCount));
                currentByte = 0;
                currentBitCount = 0;
            }
            byte[] body = output.toByteArray();
            byte[] code = new byte[body.length + 1];
            System.arraycopy(body, 0, code, 1, body.length);
            int checksum = 0;
            for (byte value : body) {
                checksum ^= value & 0xff;
            }
            code[0] = (byte) checksum;
            return code;
        }
    }
}
