// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.glavo.arkivo.archive.zip.ZipMethod;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PushbackInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies ZIP64 record encoding and descriptor selection at unsigned 32-bit size boundaries.
@NotNullByDefault
final class Zip64RecordBoundaryTest {
    /// Verifies that central directory entries move oversized local-header offsets into ZIP64 extra data.
    @Test
    void centralDirectoryEncodesOversizedLocalHeaderOffset() throws Exception {
        byte[] rawName = "large-offset.txt".getBytes(StandardCharsets.UTF_8);
        Object metadata = entryMetadata();
        Object centralEntry = centralEntry(
                "large-offset.txt",
                rawName,
                0L,
                0L,
                0xffff_ffffL + 1L,
                metadata
        );

        Method encoder = ZipArkivoWritableFileSystemImpl.class.getDeclaredMethod(
                "centralDirectoryEntryBytes",
                centralEntry.getClass()
        );
        encoder.setAccessible(true);
        byte[] centralDirectory = (byte[]) encoder.invoke(null, centralEntry);
        ByteBuffer buffer = ByteBuffer.wrap(centralDirectory).order(ByteOrder.LITTLE_ENDIAN);
        int extraOffset = 46 + rawName.length;

        assertEquals(0x02014b50, buffer.getInt(0));
        assertEquals(45, Short.toUnsignedInt(buffer.getShort(6)));
        assertEquals(0, buffer.getInt(20));
        assertEquals(0, buffer.getInt(24));
        assertEquals(0xffff_ffffL, Integer.toUnsignedLong(buffer.getInt(42)));
        assertEquals(rawName.length, Short.toUnsignedInt(buffer.getShort(28)));
        assertEquals(12, Short.toUnsignedInt(buffer.getShort(30)));
        assertEquals(0x0001, Short.toUnsignedInt(buffer.getShort(extraOffset)));
        assertEquals(8, Short.toUnsignedInt(buffer.getShort(extraOffset + 2)));
        assertEquals(0xffff_ffffL + 1L, buffer.getLong(extraOffset + 4));
    }

    /// Verifies that ZIP64 data descriptors encode compressed and uncompressed sizes as 64-bit values.
    @Test
    void dataDescriptorEncodesUnsigned32BitOverflow() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ZipArkivoWritableFileSystemImpl fileSystem = new ZipArkivoWritableFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                output,
                ZipArkivoFileSystemConfig.fromCreateOptions(ZipArchiveOptions.CREATE_DEFAULTS),
                null
        );
        Method encoder = ZipArkivoWritableFileSystemImpl.class.getDeclaredMethod(
                "writeDataDescriptor",
                long.class,
                long.class,
                long.class,
                boolean.class
        );
        encoder.setAccessible(true);

        encoder.invoke(fileSystem, 0x1234_5678L, 0xffff_ffffL + 2L, 0xffff_ffffL + 1L, true);

        byte[] descriptor = output.toByteArray();
        ByteBuffer buffer = ByteBuffer.wrap(descriptor).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(24, descriptor.length);
        assertEquals(0x08074b50, buffer.getInt(0));
        assertEquals(0x1234_5678L, Integer.toUnsignedLong(buffer.getInt(4)));
        assertEquals(0xffff_ffffL + 2L, buffer.getLong(8));
        assertEquals(0xffff_ffffL + 1L, buffer.getLong(16));
    }

    /// Verifies that observed multi-gigabyte sizes select a ZIP64 descriptor without a local ZIP64 marker.
    @Test
    void observedSizeSelectsZip64Descriptor() throws Exception {
        long crc32 = 0x5c31_6f50L;
        long compressedSize = 4_859_752L;
        long uncompressedSize = 5_000_000_000L;
        ByteBuffer descriptor = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        descriptor.putInt(0x08074b50);
        descriptor.putInt((int) crc32);
        descriptor.putLong(compressedSize);
        descriptor.putLong(uncompressedSize);

        Method matcher = ZipArkivoStreamingReaderImpl.class.getDeclaredMethod(
                "readAndMatchesDataDescriptor",
                PushbackInputStream.class,
                boolean.class,
                long.class,
                long.class,
                long.class
        );
        matcher.setAccessible(true);
        try (PushbackInputStream input = new PushbackInputStream(
                new ByteArrayInputStream(descriptor.array()),
                descriptor.capacity()
        )) {
            assertTrue((boolean) matcher.invoke(null, input, false, crc32, compressedSize, uncompressedSize));
        }
    }

    /// Creates writable ZIP entry metadata through its private constructor.
    private static Object entryMetadata() throws ReflectiveOperationException {
        Class<?> type = nestedClass("EntryMetadata");
        Constructor<?> constructor = type.getDeclaredConstructor(
                int.class,
                ZipEncryption.class,
                FileTime.class,
                int.class,
                int.class,
                long.class,
                long.class,
                long.class,
                byte[].class,
                byte[].class,
                byte[].class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
                ZipMethod.STORED.id(),
                ZipEncryption.NONE,
                null,
                20,
                0,
                0L,
                0L,
                0L,
                new byte[0],
                new byte[0],
                null
        );
    }

    /// Creates a streaming ZIP central directory entry through its private constructor.
    private static Object centralEntry(
            String name,
            byte @Unmodifiable [] rawName,
            long compressedSize,
            long uncompressedSize,
            long localHeaderOffset,
            Object metadata
    ) throws ReflectiveOperationException {
        Class<?> type = nestedClass("CentralEntry");
        Constructor<?> constructor = type.getDeclaredConstructor(
                String.class,
                byte[].class,
                int.class,
                int.class,
                int.class,
                int.class,
                long.class,
                long.class,
                long.class,
                int.class,
                long.class,
                long.class,
                long.class,
                metadata.getClass()
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
                name,
                rawName,
                1 << 11,
                ZipMethod.STORED.id(),
                0,
                0,
                0L,
                compressedSize,
                uncompressedSize,
                0,
                localHeaderOffset,
                0L,
                0L,
                metadata
        );
    }

    /// Returns the named private nested writable ZIP record class.
    private static Class<?> nestedClass(String simpleName) {
        for (Class<?> type : ZipArkivoWritableFileSystemImpl.class.getDeclaredClasses()) {
            if (type.getSimpleName().equals(simpleName)) {
                return type;
            }
        }
        throw new AssertionError("Missing writable ZIP nested class: " + simpleName);
    }
}
