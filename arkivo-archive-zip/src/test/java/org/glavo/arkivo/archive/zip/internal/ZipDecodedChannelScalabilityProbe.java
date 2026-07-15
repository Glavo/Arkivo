// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/// Creates and verifies a decoded ZIP channel whose logical size exceeds the verification heap.
@NotNullByDefault
public final class ZipDecodedChannelScalabilityProbe {
    /// The generated Deflate entry name.
    private static final String ENTRY_NAME = "large.bin";

    /// The logical decoded entry size.
    private static final long ENTRY_SIZE = 80L * 1024L * 1024L;

    /// The reusable generation and verification block size.
    private static final int BLOCK_SIZE = 64 * 1024;

    /// Prevents utility-class construction.
    private ZipDecodedChannelScalabilityProbe() {
    }

    /// Creates or verifies the fixture selected by the first argument.
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected mode and archive path");
        }
        Path archive = Path.of(args[1]);
        switch (args[0]) {
            case "create" -> create(archive);
            case "verify" -> verify(archive);
            default -> throw new IllegalArgumentException("Unknown mode: " + args[0]);
        }
    }

    /// Creates a highly compressible entry without retaining its decoded body in memory.
    private static void create(Path archive) throws IOException {
        Files.createDirectories(archive.toAbsolutePath().getParent());
        byte[] block = expectedBlock();
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.setLevel(Deflater.BEST_SPEED);
            output.putNextEntry(new ZipEntry(ENTRY_NAME));
            long remaining = ENTRY_SIZE;
            while (remaining > 0L) {
                int count = (int) Math.min(remaining, block.length);
                output.write(block, 0, count);
                remaining -= count;
            }
            output.closeEntry();
        }
    }

    /// Opens the decoded seekable channel under the task heap limit and verifies random ranges.
    private static void verify(Path archive) throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive);
             SeekableByteChannel channel = Files.newByteChannel(fileSystem.getPath("/" + ENTRY_NAME))) {
            require(channel.size() == ENTRY_SIZE, "Unexpected decoded entry size");
            verifyRange(channel, 0L, 4096);
            verifyRange(channel, 31L * 1024L * 1024L + 17L, 8192);
            verifyRange(channel, ENTRY_SIZE - 4096L, 4096);
            verifyRange(channel, 1024L, 2048);
        }
    }

    /// Verifies one random decoded byte range.
    private static void verifyRange(SeekableByteChannel channel, long position, int length) throws IOException {
        channel.position(position);
        ByteBuffer bytes = ByteBuffer.allocate(length);
        while (bytes.hasRemaining()) {
            int count = channel.read(bytes);
            require(count > 0, "Decoded channel ended before the requested range");
        }
        byte[] actual = bytes.array();
        for (int index = 0; index < actual.length; index++) {
            byte expected = expectedByte(position + index);
            require(actual[index] == expected, "Decoded channel returned incorrect data");
        }
    }

    /// Creates one repeated deterministic source block.
    private static byte[] expectedBlock() {
        byte[] block = new byte[BLOCK_SIZE];
        for (int index = 0; index < block.length; index++) {
            block[index] = expectedByte(index);
        }
        return block;
    }

    /// Returns the deterministic byte at one logical entry offset.
    private static byte expectedByte(long offset) {
        return (byte) (offset * 31L + 7L);
    }

    /// Raises an assertion failure when the probe invariant is false.
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
