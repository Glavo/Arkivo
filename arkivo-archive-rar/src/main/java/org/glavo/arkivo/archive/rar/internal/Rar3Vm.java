// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;
import org.glavo.arkivo.internal.ByteArrayAccess;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// Compiles and executes the bounded 32-bit virtual machine embedded in RAR3 filters.
@NotNullByDefault
final class Rar3Vm {
    /// The VM data-memory size.
    static final int MEMORY_SIZE = 0x40000;
    /// The address mask applied to every memory operand.
    static final int MEMORY_MASK = MEMORY_SIZE - 1;
    /// The start of the VM global-memory block.
    static final int GLOBAL_ADDRESS = 0x3c000;
    /// The total global-memory block size.
    static final int GLOBAL_SIZE = 0x2000;
    /// The reserved fixed portion of the global-memory block.
    static final int FIXED_GLOBAL_SIZE = 0x40;
    /// The maximum number of decoded instructions in one program.
    private static final int MAX_PROGRAM_INSTRUCTIONS = 0x10000;
    /// The maximum number of instructions executed in one invocation.
    private static final int MAX_EXECUTED_INSTRUCTIONS = 25_000_000;
    /// The carry flag.
    private static final int FLAG_CARRY = 1;
    /// The zero flag.
    private static final int FLAG_ZERO = 2;
    /// The sign flag.
    private static final int FLAG_SIGN = 0x8000_0000;
    /// An immediate operand.
    private static final int OPERAND_IMMEDIATE = 0;
    /// A register operand.
    private static final int OPERAND_REGISTER = 1;
    /// A direct memory operand.
    private static final int OPERAND_DIRECT = 2;
    /// A register-indirect memory operand.
    private static final int OPERAND_INDIRECT = 3;
    /// A register-plus-displacement memory operand.
    private static final int OPERAND_INDEXED = 4;

    /// Prevents construction of this utility class.
    private Rar3Vm() {
    }

    /// Compiles a checksummed RAR3 VM bytecode buffer.
    static Program compile(byte[] code) throws IOException {
        Objects.requireNonNull(code, "code");
        if (code.length < 2) {
            throw new IOException("RAR3 filter program is empty");
        }
        int checksum = 0;
        for (int index = 1; index < code.length; index++) checksum ^= code[index] & 0xff;
        if (checksum != (code[0] & 0xff)) {
            throw new IOException("Invalid RAR3 filter program checksum");
        }

        Rar3BitReader reader = new Rar3BitReader(code, 1);
        byte[] staticData = new byte[0];
        if (reader.readBits(1) != 0) {
            long encodedSize = reader.readEncodedUint32();
            if (encodedSize >= GLOBAL_SIZE - FIXED_GLOBAL_SIZE) {
                throw new IOException("RAR3 filter static data is too large");
            }
            staticData = reader.readBytes((int) encodedSize + 1);
        }

        List<Instruction> instructions = new ArrayList<>();
        while (reader.remainingBits() >= 4) {
            try {
                instructions.add(readInstruction(reader, instructions.size()));
            } catch (EOFException ignored) {
                break;
            }
            if (instructions.size() > MAX_PROGRAM_INSTRUCTIONS) {
                throw new IOException("RAR3 filter program contains too many instructions");
            }
        }
        return new Program(instructions.toArray(Instruction[]::new), staticData);
    }

    /// Decodes one VM instruction and its operands.
    private static Instruction readInstruction(Rar3BitReader reader, int instructionIndex) throws IOException {
        int opcode = reader.readBits(4);
        if ((opcode & 8) != 0) opcode = (opcode << 2 | reader.readBits(2)) - 24;
        if (opcode < 0 || opcode > 39) throw new IOException("Invalid RAR3 VM opcode");
        boolean byteMode = supportsByteMode(opcode) && reader.readBits(1) != 0;
        int operandCount = operandCount(opcode);
        Operand first = operandCount == 0 ? Operand.NONE : readOperand(reader, byteMode);
        Operand second = operandCount < 2 ? Operand.NONE : readOperand(reader, byteMode);
        if (operandCount == 1 && isJump(opcode) && first.type == OPERAND_IMMEDIATE) {
            int target = first.value;
            if (Integer.compareUnsigned(target, 256) >= 0) {
                target -= 256;
            } else {
                if (target >= 136) target -= 264;
                else if (target >= 16) target -= 8;
                else if (target >= 8) target -= 16;
                target += instructionIndex;
            }
            first = new Operand(OPERAND_IMMEDIATE, 0, target);
        }
        return new Instruction(opcode, byteMode, first, second);
    }

    /// Decodes one immediate, register, or memory operand.
    private static Operand readOperand(Rar3BitReader reader, boolean byteMode) throws IOException {
        if (reader.readBits(1) != 0) {
            return new Operand(OPERAND_REGISTER, reader.readBits(3), 0);
        }
        if (reader.readBits(1) == 0) {
            int value = byteMode ? reader.readBits(8) : (int) reader.readEncodedUint32();
            return new Operand(OPERAND_IMMEDIATE, 0, value);
        }
        if (reader.readBits(1) == 0) {
            return new Operand(OPERAND_INDIRECT, reader.readBits(3), 0);
        }
        if (reader.readBits(1) == 0) {
            int register = reader.readBits(3);
            return new Operand(OPERAND_INDEXED, register, (int) reader.readEncodedUint32());
        }
        return new Operand(OPERAND_DIRECT, 0, (int) reader.readEncodedUint32() & MEMORY_MASK);
    }

    /// Returns whether an opcode carries an encoded byte-mode flag.
    private static boolean supportsByteMode(int opcode) {
        return opcode <= 3 || opcode == 6 || opcode == 7 || opcode >= 9 && opcode <= 12
                || opcode == 23 || opcode >= 24 && opcode <= 27 || opcode >= 34 && opcode <= 38;
    }

    /// Returns the number of operands consumed by an opcode.
    private static int operandCount(int opcode) {
        return switch (opcode) {
            case 22, 28, 29, 30, 31, 39 -> 0;
            case 4, 5, 6, 7, 8, 13, 14, 15, 16, 17, 18, 19, 20, 21, 23, 27 -> 1;
            default -> 2;
        };
    }

    /// Returns whether a one-operand instruction changes the instruction pointer.
    private static boolean isJump(int opcode) {
        return opcode == 4 || opcode == 5 || opcode == 8 || opcode >= 13 && opcode <= 18 || opcode == 21;
    }

    /// A compiled VM program with execution state retained between solid invocations.
    @NotNullByDefault
    static final class Program {
        /// The immutable decoded instruction sequence.
        private final Instruction @Unmodifiable [] instructions;
        /// The immutable program-defined global data.
        private final byte @Unmodifiable [] staticData;
        /// Global bytes requested by the previous invocation.
        private byte[] persistentGlobal = new byte[0];
        /// The number of completed invocations.
        private int executionCount;

        /// Creates a compiled VM program.
        private Program(Instruction[] instructions, byte[] staticData) {
            this.instructions = instructions.clone();
            this.staticData = staticData.clone();
        }

        /// Executes the program over one raw filter block.
        byte[] execute(
                byte[] input,
                int[] initialRegisters,
                int initialRegisterMask,
                byte[] initialGlobal,
                long offset
        ) throws IOException {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(initialRegisters, "initialRegisters");
            Objects.requireNonNull(initialGlobal, "initialGlobal");
            if (input.length > GLOBAL_ADDRESS || initialRegisters.length != 7) {
                throw new IOException("Invalid RAR3 VM invocation");
            }

            Machine machine = new Machine();
            System.arraycopy(input, 0, machine.memory, 0, input.length);
            machine.registers[3] = GLOBAL_ADDRESS;
            machine.registers[4] = input.length;
            machine.registers[5] = executionCount;
            for (int index = 0; index < initialRegisters.length; index++) {
                if ((initialRegisterMask & 1 << index) != 0) machine.registers[index] = initialRegisters[index];
            }
            machine.registers[6] = (int) offset;
            machine.registers[7] = MEMORY_SIZE;

            int global = GLOBAL_ADDRESS;
            for (int index = 0; index < 7; index++) ByteArrayAccess.writeIntLittleEndian(machine.memory, global + index * 4, machine.registers[index]);
            ByteArrayAccess.writeIntLittleEndian(machine.memory, global + 0x1c, input.length);
            ByteArrayAccess.writeIntLittleEndian(machine.memory, global + 0x20, 0);
            ByteArrayAccess.writeIntLittleEndian(machine.memory, global + 0x24, (int) offset);
            ByteArrayAccess.writeIntLittleEndian(machine.memory, global + 0x28, (int) (offset >>> 32));
            ByteArrayAccess.writeIntLittleEndian(machine.memory, global + 0x2c, executionCount);

            byte[] dynamicGlobal = persistentGlobal.length == 0 ? initialGlobal : persistentGlobal;
            int copied = Math.min(dynamicGlobal.length, GLOBAL_SIZE - FIXED_GLOBAL_SIZE);
            System.arraycopy(dynamicGlobal, 0, machine.memory, global + FIXED_GLOBAL_SIZE, copied);
            int staticCount = Math.min(staticData.length, GLOBAL_SIZE - FIXED_GLOBAL_SIZE - copied);
            System.arraycopy(staticData, 0, machine.memory, global + FIXED_GLOBAL_SIZE + copied, staticCount);
            run(machine);
            executionCount++;

            int saveSize = Math.min(ByteArrayAccess.readIntLittleEndian(machine.memory, global + 0x30), GLOBAL_SIZE - FIXED_GLOBAL_SIZE);
            persistentGlobal = saveSize <= 0
                    ? new byte[0]
                    : Arrays.copyOfRange(machine.memory, global + FIXED_GLOBAL_SIZE, global + FIXED_GLOBAL_SIZE + saveSize);
            int outputLength = ByteArrayAccess.readIntLittleEndian(machine.memory, global + 0x1c) & MEMORY_MASK;
            int outputStart = ByteArrayAccess.readIntLittleEndian(machine.memory, global + 0x20) & MEMORY_MASK;
            if (outputStart + outputLength > MEMORY_SIZE) throw new IOException("RAR3 VM output exceeds its memory");
            return Arrays.copyOfRange(machine.memory, outputStart, outputStart + outputLength);
        }

        /// Runs the instruction sequence until RET, an out-of-range jump, or the execution limit.
        private void run(Machine machine) throws IOException {
            int instructionPointer = 0;
            for (int count = 0; count < MAX_EXECUTED_INSTRUCTIONS; count++) {
                if (instructionPointer < 0 || instructionPointer >= instructions.length) return;
                Instruction instruction = instructions[instructionPointer];
                int next = execute(machine, instruction, instructionPointer);
                instructionPointer = next == Integer.MIN_VALUE ? instructionPointer + 1 : next;
            }
            throw new IOException("RAR3 VM instruction limit exceeded");
        }

        /// Executes one decoded instruction and returns a replacement instruction pointer when set.
        private static int execute(Machine m, Instruction i, int ip) throws IOException {
            int left = i.first.read(m, i.byteMode);
            int right = i.second.read(m, i.byteMode);
            int result;
            switch (i.opcode) {
                case 0 -> i.first.write(m, i.byteMode, right);
                case 1 -> m.flags = subtractionFlags(left, right, i.byteMode);
                case 2 -> {
                    result = left + right;
                    i.first.write(m, i.byteMode, result);
                    m.flags = additionFlags(left, right, result, i.byteMode);
                }
                case 3 -> {
                    result = left - right;
                    i.first.write(m, i.byteMode, result);
                    m.flags = subtractionFlags(left, right, i.byteMode);
                }
                case 4 -> { if ((m.flags & FLAG_ZERO) != 0) return left; }
                case 5 -> { if ((m.flags & FLAG_ZERO) == 0) return left; }
                case 6 -> {
                    result = left + 1;
                    i.first.write(m, i.byteMode, result);
                    m.flags = zeroSignFlags(result, i.byteMode);
                }
                case 7 -> {
                    result = left - 1;
                    i.first.write(m, i.byteMode, result);
                    m.flags = zeroSignFlags(result, i.byteMode);
                }
                case 8 -> { return left; }
                case 9 -> {
                    result = left ^ right;
                    i.first.write(m, i.byteMode, result);
                    m.flags = zeroSignFlags(result, i.byteMode);
                }
                case 10 -> {
                    result = left & right;
                    i.first.write(m, i.byteMode, result);
                    m.flags = zeroSignFlags(result, i.byteMode);
                }
                case 11 -> {
                    result = left | right;
                    i.first.write(m, i.byteMode, result);
                    m.flags = zeroSignFlags(result, i.byteMode);
                }
                case 12 -> m.flags = zeroSignFlags(left & right, i.byteMode);
                case 13 -> { if ((m.flags & FLAG_SIGN) != 0) return left; }
                case 14 -> { if ((m.flags & FLAG_SIGN) == 0) return left; }
                case 15 -> { if ((m.flags & FLAG_CARRY) != 0) return left; }
                case 16 -> { if ((m.flags & (FLAG_CARRY | FLAG_ZERO)) != 0) return left; }
                case 17 -> { if ((m.flags & (FLAG_CARRY | FLAG_ZERO)) == 0) return left; }
                case 18 -> { if ((m.flags & FLAG_CARRY) == 0) return left; }
                case 19 -> push(m, left);
                case 20 -> i.first.write(m, false, pop(m));
                case 21 -> { push(m, ip + 1); return left; }
                case 22 -> { return Integer.compareUnsigned(m.registers[7], MEMORY_SIZE) >= 0 ? -1 : pop(m); }
                case 23 -> i.first.write(m, i.byteMode, ~left);
                case 24, 25, 26 -> {
                    int count = right & 31;
                    result = i.opcode == 24 ? left << count : i.opcode == 25 ? left >>> count : left >> count;
                    i.first.write(m, i.byteMode, result);
                    m.flags = shiftFlags(left, result, count, i.byteMode, i.opcode == 24);
                }
                case 27 -> {
                    result = -left;
                    i.first.write(m, i.byteMode, result);
                    m.flags = zeroSignFlags(result, i.byteMode) | (masked(result, i.byteMode) == 0 ? 0 : FLAG_CARRY);
                }
                case 28 -> pushAll(m);
                case 29 -> popAll(m);
                case 30 -> push(m, m.flags);
                case 31 -> m.flags = pop(m);
                case 32 -> i.first.write(m, false, i.second.read(m, true));
                case 33 -> i.first.write(m, false, (byte) i.second.read(m, true));
                case 34 -> {
                    i.first.write(m, i.byteMode, right);
                    i.second.write(m, i.byteMode, left);
                }
                case 35 -> i.first.write(m, i.byteMode, left * right);
                case 36 -> { if (right != 0) i.first.write(m, i.byteMode, Integer.divideUnsigned(left, right)); }
                case 37 -> {
                    int carry = m.flags & FLAG_CARRY;
                    result = left + right + carry;
                    i.first.write(m, i.byteMode, result);
                    m.flags = additionFlags(left, right + carry, result, i.byteMode);
                }
                case 38 -> {
                    int carry = m.flags & FLAG_CARRY;
                    result = left - right - carry;
                    i.first.write(m, i.byteMode, result);
                    m.flags = subtractionFlags(left, right + carry, i.byteMode);
                }
                case 39 -> { }
                default -> throw new IOException("Invalid RAR3 VM opcode");
            }
            return Integer.MIN_VALUE;
        }
    }

    /// Mutable registers, flags, and memory for one invocation.
    @NotNullByDefault
    private static final class Machine {
        /// The eight general-purpose registers.
        private final int[] registers = new int[8];
        /// The VM memory plus padding for wrapped 32-bit accesses.
        private final byte[] memory = new byte[MEMORY_SIZE + 4];
        /// The current condition flags.
        private int flags;
        /// Creates a zero-initialized invocation state.
        private Machine() {
        }
    }

    /// Describes one decoded instruction.
    ///
    /// @param opcode the numeric opcode
    /// @param byteMode whether operands use their low byte
    /// @param first the first operand or {@link Operand#NONE}
    /// @param second the second operand or {@link Operand#NONE}
    @NotNullByDefault
    private record Instruction(int opcode, boolean byteMode, Operand first, Operand second) {
    }

    /// Describes one decoded operand.
    ///
    /// @param type the operand addressing mode
    /// @param register the register index used by register-based modes
    /// @param value the immediate value, direct address, or indexed displacement
    @NotNullByDefault
    private record Operand(int type, int register, int value) {
        /// The placeholder used by instructions with fewer than two operands.
        private static final Operand NONE = new Operand(OPERAND_IMMEDIATE, 0, 0);

        /// Reads this operand from one machine invocation.
        private int read(Machine machine, boolean byteMode) {
            return switch (type) {
                case OPERAND_IMMEDIATE -> value;
                case OPERAND_REGISTER -> byteMode ? machine.registers[register] & 0xff : machine.registers[register];
                case OPERAND_DIRECT -> readMemory(machine, value, byteMode);
                case OPERAND_INDIRECT -> readMemory(machine, machine.registers[register], byteMode);
                case OPERAND_INDEXED -> readMemory(machine, machine.registers[register] + value, byteMode);
                default -> throw new AssertionError("Invalid RAR3 VM operand");
            };
        }

        /// Writes this operand in one machine invocation.
        private void write(Machine machine, boolean byteMode, int newValue) {
            switch (type) {
                case OPERAND_IMMEDIATE -> { }
                case OPERAND_REGISTER -> machine.registers[register] = byteMode
                        ? machine.registers[register] & 0xffff_ff00 | newValue & 0xff : newValue;
                case OPERAND_DIRECT -> writeMemory(machine, value, newValue, byteMode);
                case OPERAND_INDIRECT -> writeMemory(machine, machine.registers[register], newValue, byteMode);
                case OPERAND_INDEXED -> writeMemory(machine, machine.registers[register] + value, newValue, byteMode);
                default -> throw new AssertionError("Invalid RAR3 VM operand");
            }
        }
    }

    /// Reads one byte or little-endian word from masked VM memory.
    private static int readMemory(Machine machine, int address, boolean byteMode) {
        int maskedAddress = address & MEMORY_MASK;
        return byteMode ? machine.memory[maskedAddress] & 0xff : ByteArrayAccess.readIntLittleEndian(machine.memory, maskedAddress);
    }

    /// Writes one byte or little-endian word to masked VM memory.
    private static void writeMemory(Machine machine, int address, int value, boolean byteMode) {
        int maskedAddress = address & MEMORY_MASK;
        if (byteMode) machine.memory[maskedAddress] = (byte) value;
        else ByteArrayAccess.writeIntLittleEndian(machine.memory, maskedAddress, value);
    }

    /// Pushes one word on the masked VM stack.
    private static void push(Machine machine, int value) {
        machine.registers[7] -= 4;
        writeMemory(machine, machine.registers[7], value, false);
    }

    /// Pops one word from the masked VM stack.
    private static int pop(Machine machine) {
        int value = readMemory(machine, machine.registers[7], false);
        machine.registers[7] += 4;
        return value;
    }

    /// Pushes all registers in ascending register order.
    private static void pushAll(Machine machine) {
        int stack = machine.registers[7];
        int[] snapshot = machine.registers.clone();
        for (int value : snapshot) {
            stack -= 4;
            writeMemory(machine, stack, value, false);
        }
        machine.registers[7] = stack;
    }

    /// Restores all registers from a PUSHA frame.
    private static void popAll(Machine machine) {
        int stack = machine.registers[7];
        for (int register = 7; register >= 0; register--) {
            machine.registers[register] = readMemory(machine, stack, false);
            stack += 4;
        }
    }

    /// Computes zero and sign flags for a result width.
    private static int zeroSignFlags(int value, boolean byteMode) {
        int result = masked(value, byteMode);
        if (result == 0) return FLAG_ZERO;
        return (result & (byteMode ? 0x80 : FLAG_SIGN)) != 0 ? FLAG_SIGN : 0;
    }

    /// Computes flags for one addition.
    private static int additionFlags(int left, int right, int result, boolean byteMode) {
        long mask = byteMode ? 0xffL : 0xffff_ffffL;
        long sum = (left & mask) + (right & mask);
        return zeroSignFlags(result, byteMode) | (sum > mask ? FLAG_CARRY : 0);
    }

    /// Computes flags for one subtraction.
    private static int subtractionFlags(int left, int right, boolean byteMode) {
        long mask = byteMode ? 0xffL : 0xffff_ffffL;
        int result = left - right;
        return zeroSignFlags(result, byteMode) | ((left & mask) < (right & mask) ? FLAG_CARRY : 0);
    }

    /// Computes flags for one logical shift.
    private static int shiftFlags(int original, int result, int count, boolean byteMode, boolean leftShift) {
        int flags = zeroSignFlags(result, byteMode);
        if (count == 0) return flags;
        int width = byteMode ? 8 : 32;
        if (count <= width) {
            int bit = leftShift ? width - count : count - 1;
            if (((original >>> bit) & 1) != 0) flags |= FLAG_CARRY;
        }
        return flags;
    }

    /// Masks an arithmetic result to its active operand width.
    private static int masked(int value, boolean byteMode) {
        return byteMode ? value & 0xff : value;
    }

}
