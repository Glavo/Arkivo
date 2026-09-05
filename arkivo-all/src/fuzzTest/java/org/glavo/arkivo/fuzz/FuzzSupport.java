// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.fuzz;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.lz4.LZ4BlockCodec;
import org.glavo.arkivo.codec.lzma.RawLZMACodec;
import org.glavo.arkivo.codec.ppmd.PPMdCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;

/// Provides bounded configurations and deterministic seeds shared by local Jazzer targets.
@NotNullByDefault
final class FuzzSupport {
    /// The largest compressed or archive payload accepted by a parser target.
    static final int MAX_PARSER_INPUT_SIZE = 256 * 1024;

    /// The largest uncompressed payload accepted by a round-trip target.
    static final int MAX_ROUND_TRIP_INPUT_SIZE = 4 * 1024;

    /// The maximum decoded bytes produced by one fuzz invocation.
    static final int MAX_DECODED_OUTPUT_SIZE = 256 * 1024;

    /// The maximum history window made available to one decoder.
    private static final long MAXIMUM_CODEC_WINDOW_SIZE = 16L * 1024L * 1024L;

    /// The maximum codec-accounted memory made available to one decoder.
    private static final long MAXIMUM_CODEC_MEMORY_SIZE = 32L * 1024L * 1024L;

    /// The fixed sector and allocation-block size used by the generated DMG seed.
    static final int DMG_SECTOR_SIZE = 512;

    /// The deterministic incompressible body size used to force split seed output.
    private static final int SPLIT_ARCHIVE_CONTENT_SIZE = 96 * 1024;

    /// The RAR4 signature used by generated stored-file seeds.
    private static final byte @Unmodifiable [] RAR4_SIGNATURE =
            {'R', 'a', 'r', '!', 0x1a, 0x07, 0x00};

    /// The fixed body used to build valid compression and archive seeds.
    static final byte @Unmodifiable [] SEED_CONTENT =
            "Arkivo Jazzer seed\n".getBytes(StandardCharsets.UTF_8);

    /// Installed compression formats in deterministic catalog order.
    static final @Unmodifiable List<CompressionFormat> COMPRESSION_FORMATS =
            CompressionFormats.installed();

    /// Installed compression formats whose default encodings carry a reliable detectable signature.
    static final @Unmodifiable List<CompressionFormat> SIGNED_COMPRESSION_FORMATS =
            COMPRESSION_FORMATS.stream().filter(format -> format.probeSize() > 0).toList();

    /// Formats exercised through their forward-only reader.
    static final @Unmodifiable List<String> STREAMING_ARCHIVE_FORMATS =
            List.of("ar", "cpio", "rar", "tar", "zip");

    /// Formats exercised through their random-access file system.
    static final @Unmodifiable List<String> FILE_SYSTEM_ARCHIVE_FORMATS =
            List.of("7z", "ar", "dmg", "rar", "tar", "zip");

    /// Formats exercised through their public forward-only writer.
    static final @Unmodifiable List<String> STREAMING_WRITER_FORMATS =
            List.of("7z", "ar", "cpio", "tar", "zip");

    /// Resource limits applied to every archive parser invocation.
    static final ArchiveReadOptions ARCHIVE_READ_OPTIONS = ArchiveReadOptions.DEFAULT.withLimits(
            ArchiveReadLimits.builder()
                    .maximumEntryCount(64L)
                    .maximumEntrySize(MAX_DECODED_OUTPUT_SIZE)
                    .maximumTotalEntrySize(MAX_DECODED_OUTPUT_SIZE)
                    .maximumMetadataSize(MAX_DECODED_OUTPUT_SIZE)
                    .maximumCompressionWindowSize(MAXIMUM_CODEC_WINDOW_SIZE)
                    .maximumDecoderMemorySize(MAXIMUM_CODEC_MEMORY_SIZE)
                    .maximumDecodedArchiveSize(MAX_DECODED_OUTPUT_SIZE)
                    .maximumOuterCompressionLayers(2L)
                    .build()
    );

    /// Prevents instantiation.
    private FuzzSupport() {
    }

    /// Returns the installed compression format selected by an arbitrary integer.
    ///
    /// @param selector the arbitrary selector
    /// @return the selected installed format
    static CompressionFormat compressionFormat(int selector) {
        return COMPRESSION_FORMATS.get(Math.floorMod(selector, COMPRESSION_FORMATS.size()));
    }

    /// Applies finite decoder limits and decoded-size metadata required by raw formats.
    ///
    /// @param codec the base codec
    /// @param decodedSize the expected nonnegative decoded size
    /// @return a bounded immutable codec suitable for one fuzz invocation
    static CompressionCodec<?> boundedCodec(CompressionCodec<?> codec, long decodedSize) {
        CompressionCodec<?> configured = codec
                .withMaximumOutputSize(MAX_DECODED_OUTPUT_SIZE)
                .withMaximumWindowSize(MAXIMUM_CODEC_WINDOW_SIZE)
                .withMaximumMemorySize(MAXIMUM_CODEC_MEMORY_SIZE);
        if (configured instanceof LZ4BlockCodec blockCodec) {
            configured = blockCodec.withMaximumBlockSize(MAX_DECODED_OUTPUT_SIZE);
        }
        if (configured instanceof RawLZMACodec rawLzmaCodec) {
            configured = rawLzmaCodec.withDecodedSize(decodedSize);
        }
        if (configured instanceof PPMdCodec ppmdCodec) {
            configured = ppmdCodec.withDecodedSize(decodedSize);
        }
        return configured;
    }

    /// Copies the remaining bytes of a buffer without changing its position.
    ///
    /// @param buffer the source buffer
    /// @return a new byte array containing the remaining bytes
    static byte @Unmodifiable [] remainingBytes(ByteBuffer buffer) {
        ByteBuffer view = buffer.duplicate();
        byte[] result = new byte[view.remaining()];
        view.get(result);
        return result;
    }

    /// Prefixes a payload with fuzz-control bytes.
    ///
    /// @param controls the leading control bytes
    /// @param payload the payload to append
    /// @return a new combined byte array
    static byte @Unmodifiable [] prefix(byte @Unmodifiable [] controls, byte @Unmodifiable [] payload) {
        byte[] result = new byte[controls.length + payload.length];
        System.arraycopy(controls, 0, result, 0, controls.length);
        System.arraycopy(payload, 0, result, controls.length, payload.length);
        return result;
    }

    /// Creates a small valid archive for the named supported format.
    ///
    /// RAR is read-only and therefore uses a source-generated stored RAR4 archive. Other writable formats are generated
    /// through Arkivo's public streaming writer so their seeds track the current encoder implementation.
    ///
    /// @param formatName the installed archive format name
    /// @return a complete valid archive
    /// @throws IOException if the archive cannot be encoded
    static byte @Unmodifiable [] createArchiveSeed(String formatName) throws IOException {
        return createArchiveSeed(formatName, 1);
    }

    /// Creates a small valid archive with the requested number of regular-file entries.
    ///
    /// @param formatName the installed archive format name
    /// @param entryCount the positive number of generated entries
    /// @return a complete valid archive
    /// @throws IOException if the archive cannot be encoded
    static byte @Unmodifiable [] createArchiveSeed(String formatName, int entryCount) throws IOException {
        if (entryCount <= 0) {
            throw new IllegalArgumentException("entryCount must be positive");
        }
        if ("rar".equals(formatName)) {
            return createRAR4ArchiveSeed(entryCount);
        }
        if ("dmg".equals(formatName)) {
            if (entryCount != 1) {
                throw new IllegalArgumentException("DMG seeds do not have archive entries");
            }
            return createDMGSeed();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter(formatName, output)) {
            for (int index = 0; index < entryCount; index++) {
                ArkivoStreamingWriter.Entry entry = writer.beginFile(archiveSeedEntryName(index));
                try (OutputStream body = entry.openOutputStream()) {
                    body.write(SEED_CONTENT);
                }
            }
        }
        return output.toByteArray();
    }

    /// Returns the stable path of one generated archive seed entry.
    private static String archiveSeedEntryName(int index) {
        return index == 0 ? "seed.txt" : "seed-" + index + ".txt";
    }

    /// Creates a minimal flattened UDIF image containing a mountable empty HFS Plus volume.
    static byte @Unmodifiable [] createDMGSeed() {
        return createDMGSeed(createEmptyHFSPlusDisk());
    }

    /// Creates a flattened UDIF image containing an Apple Partition Map and an HFS Plus partition.
    static byte @Unmodifiable [] createApplePartitionMapDMGSeed() {
        byte[] volume = createEmptyHFSPlusDisk();
        byte[] disk = new byte[3 * DMG_SECTOR_SIZE + volume.length];
        ByteBuffer map = ByteBuffer.wrap(disk).order(ByteOrder.BIG_ENDIAN);
        map.putShort(0, (short) 0x4552);
        map.putShort(2, (short) DMG_SECTOR_SIZE);
        writeApplePartitionMapEntry(
                disk,
                1,
                2,
                1,
                2,
                "Partition Map",
                "Apple_partition_map"
        );
        writeApplePartitionMapEntry(
                disk,
                2,
                2,
                3,
                volume.length / DMG_SECTOR_SIZE,
                "Seed",
                "Apple_HFS"
        );
        System.arraycopy(volume, 0, disk, 3 * DMG_SECTOR_SIZE, volume.length);
        return createDMGSeed(disk);
    }

    /// Creates a flattened UDIF image containing a GUID Partition Table and an HFS Plus partition.
    static byte @Unmodifiable [] createGUIDPartitionTableDMGSeed() {
        byte[] volume = createEmptyHFSPlusDisk();
        int partitionStart = 3;
        int partitionBlocks = volume.length / DMG_SECTOR_SIZE;
        byte[] disk = new byte[partitionStart * DMG_SECTOR_SIZE + volume.length];
        ByteBuffer gpt = ByteBuffer.wrap(disk).order(ByteOrder.LITTLE_ENDIAN);

        int entryOffset = 2 * DMG_SECTOR_SIZE;
        int entryCount = 2;
        int entrySize = 128;
        gpt.putInt(entryOffset, 0x4846_5300);
        gpt.putShort(entryOffset + 4, (short) 0);
        gpt.putShort(entryOffset + 6, (short) 0x11aa);
        disk[entryOffset + 8] = (byte) 0xaa;
        disk[entryOffset + 9] = 0x11;
        disk[entryOffset + 10] = 0x00;
        disk[entryOffset + 11] = 0x30;
        disk[entryOffset + 12] = 0x65;
        disk[entryOffset + 13] = 0x43;
        disk[entryOffset + 14] = (byte) 0xec;
        disk[entryOffset + 15] = (byte) 0xac;
        disk[entryOffset + 16] = 1;
        gpt.putLong(entryOffset + 32, partitionStart);
        gpt.putLong(entryOffset + 40, partitionStart + partitionBlocks - 1L);
        byte[] name = "Seed".getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(name, 0, disk, entryOffset + 56, name.length);

        int headerOffset = DMG_SECTOR_SIZE;
        byte[] signature = "EFI PART".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(signature, 0, disk, headerOffset, signature.length);
        gpt.putInt(headerOffset + 8, 0x0001_0000);
        gpt.putInt(headerOffset + 12, 92);
        gpt.putLong(headerOffset + 24, 1L);
        gpt.putLong(headerOffset + 32, disk.length / DMG_SECTOR_SIZE - 1L);
        gpt.putLong(headerOffset + 40, partitionStart);
        gpt.putLong(headerOffset + 48, partitionStart + partitionBlocks - 1L);
        disk[headerOffset + 56] = 1;
        gpt.putLong(headerOffset + 72, 2L);
        gpt.putInt(headerOffset + 80, entryCount);
        gpt.putInt(headerOffset + 84, entrySize);
        gpt.putInt(headerOffset + 88, crc32(disk, entryOffset, entryCount * entrySize));
        gpt.putInt(headerOffset + 16, crc32(disk, headerOffset, 92));

        System.arraycopy(volume, 0, disk, partitionStart * DMG_SECTOR_SIZE, volume.length);
        return createDMGSeed(disk);
    }

    /// Creates one raw-run flattened UDIF image for the supplied sector-aligned decoded disk.
    private static byte @Unmodifiable [] createDMGSeed(byte @Unmodifiable [] disk) {
        if (disk.length % DMG_SECTOR_SIZE != 0) {
            throw new IllegalArgumentException("DMG seed disk must be sector aligned");
        }
        long sectorCount = disk.length / DMG_SECTOR_SIZE;
        byte[] blockTable = new byte[204 + 2 * 40];
        ByteBuffer table = ByteBuffer.wrap(blockTable).order(ByteOrder.BIG_ENDIAN);
        table.putInt(0, 0x6d697368);
        table.putInt(4, 1);
        table.putLong(16, sectorCount);
        table.putInt(200, 2);
        table.putInt(204, 1);
        table.putLong(220, sectorCount);
        table.putLong(236, disk.length);
        table.putInt(244, -1);
        table.putLong(252, sectorCount);

        String plist = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<plist version=\"1.0\"><dict><key>resource-fork</key><dict>"
                + "<key>blkx</key><array><dict><key>Data</key><data>"
                + Base64.getEncoder().encodeToString(blockTable)
                + "</data></dict></array></dict></dict></plist>";
        byte[] xml = plist.getBytes(StandardCharsets.UTF_8);
        byte[] trailer = new byte[DMG_SECTOR_SIZE];
        ByteBuffer koly = ByteBuffer.wrap(trailer).order(ByteOrder.BIG_ENDIAN);
        koly.putInt(0, 0x6b6f6c79);
        koly.putInt(4, 4);
        koly.putInt(8, DMG_SECTOR_SIZE);
        koly.putInt(12, 1);
        koly.putLong(32, disk.length);
        koly.putLong(216, disk.length);
        koly.putLong(224, xml.length);
        koly.putLong(492, sectorCount);

        ByteArrayOutputStream output = new ByteArrayOutputStream(disk.length + xml.length + trailer.length);
        output.writeBytes(disk);
        output.writeBytes(xml);
        output.writeBytes(trailer);
        return output.toByteArray();
    }

    /// Writes one Apple Partition Map entry into a decoded fuzz seed disk.
    private static void writeApplePartitionMapEntry(
            byte[] disk,
            int slot,
            int entryCount,
            int startBlock,
            int blockCount,
            String name,
            String type
    ) {
        int offset = Math.multiplyExact(slot, DMG_SECTOR_SIZE);
        ByteBuffer entry = ByteBuffer.wrap(disk).order(ByteOrder.BIG_ENDIAN);
        entry.putShort(offset, (short) 0x504d);
        entry.putInt(offset + 4, entryCount);
        entry.putInt(offset + 8, startBlock);
        entry.putInt(offset + 12, blockCount);
        writeAsciiCString(disk, offset + 16, 32, name);
        writeAsciiCString(disk, offset + 48, 32, type);
    }

    /// Writes one ASCII string into a fixed-width zero-initialized field.
    private static void writeAsciiCString(byte[] target, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length >= length) {
            throw new IllegalArgumentException("DMG seed string does not fit its field");
        }
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    /// Returns the CRC-32 of one array range as its raw 32-bit representation.
    private static int crc32(byte[] bytes, int offset, int length) {
        CRC32 checksum = new CRC32();
        checksum.update(bytes, offset, length);
        return (int) checksum.getValue();
    }

    /// Creates an eight-block HFS Plus volume containing only its root catalog folder.
    private static byte[] createEmptyHFSPlusDisk() {
        byte[] disk = new byte[8 * DMG_SECTOR_SIZE];
        ByteBuffer buffer = ByteBuffer.wrap(disk).order(ByteOrder.BIG_ENDIAN);
        int volumeHeader = 2 * DMG_SECTOR_SIZE;
        buffer.putShort(volumeHeader, (short) 0x482b);
        buffer.putShort(volumeHeader + 2, (short) 4);
        buffer.putInt(volumeHeader + 40, DMG_SECTOR_SIZE);
        buffer.putInt(volumeHeader + 44, 8);
        buffer.putInt(volumeHeader + 48, 2);
        writeHFSPlusFork(buffer, volumeHeader + 192, DMG_SECTOR_SIZE, 1, 3, 1);
        writeHFSPlusFork(buffer, volumeHeader + 272, 2L * DMG_SECTOR_SIZE, 2, 4, 2);

        writeBTreeHeader(buffer, 3 * DMG_SECTOR_SIZE, 0, 0, 0, 0, 0, 1, 10);
        writeBTreeHeader(buffer, 4 * DMG_SECTOR_SIZE, 1, 1, 1, 1, 1, 2, 516);
        int leaf = 5 * DMG_SECTOR_SIZE;
        buffer.putInt(leaf, 0);
        buffer.putInt(leaf + 4, 0);
        buffer.put(leaf + 8, (byte) 0xff);
        buffer.put(leaf + 9, (byte) 1);
        buffer.putShort(leaf + 10, (short) 1);

        int record = leaf + 14;
        byte[] name = "Root".getBytes(StandardCharsets.UTF_16BE);
        buffer.putShort(record, (short) 14);
        buffer.putInt(record + 2, 1);
        buffer.putShort(record + 6, (short) 4);
        System.arraycopy(name, 0, disk, record + 8, name.length);
        int data = record + 16;
        buffer.putShort(data, (short) 1);
        buffer.putInt(data + 8, 2);
        buffer.putInt(data + 32, 501);
        buffer.putInt(data + 36, 20);
        buffer.putShort(data + 42, (short) 0040755);
        buffer.putShort(leaf + DMG_SECTOR_SIZE - 2, (short) 14);
        buffer.putShort(leaf + DMG_SECTOR_SIZE - 4, (short) 118);
        return disk;
    }

    /// Writes one HFS Plus fork-data record with one extent.
    private static void writeHFSPlusFork(
            ByteBuffer buffer,
            int offset,
            long logicalSize,
            int totalBlocks,
            int startBlock,
            int blockCount
    ) {
        buffer.putLong(offset, logicalSize);
        buffer.putInt(offset + 12, totalBlocks);
        buffer.putInt(offset + 16, startBlock);
        buffer.putInt(offset + 20, blockCount);
    }

    /// Writes one minimal HFS Plus B-tree header node.
    private static void writeBTreeHeader(
            ByteBuffer buffer,
            int offset,
            int treeDepth,
            int rootNode,
            int leafRecords,
            int firstLeafNode,
            int lastLeafNode,
            int totalNodes,
            int maximumKeyLength
    ) {
        buffer.put(offset + 8, (byte) 1);
        buffer.putShort(offset + 10, (short) 1);
        int header = offset + 14;
        buffer.putShort(header, (short) treeDepth);
        buffer.putInt(header + 2, rootNode);
        buffer.putInt(header + 6, leafRecords);
        buffer.putInt(header + 10, firstLeafNode);
        buffer.putInt(header + 14, lastLeafNode);
        buffer.putShort(header + 18, (short) DMG_SECTOR_SIZE);
        buffer.putShort(header + 20, (short) maximumKeyLength);
        buffer.putInt(header + 22, totalNodes);
        buffer.putShort(offset + DMG_SECTOR_SIZE - 2, (short) 14);
    }

    /// Compresses an archive seed with the named outer compression format.
    ///
    /// @param formatName the installed compression format name
    /// @param archive the decoded archive bytes
    /// @return the complete compressed encoding
    /// @throws IOException if the encoding cannot be produced
    static byte @Unmodifiable [] compressArchive(
            String formatName,
            byte @Unmodifiable [] archive
    ) throws IOException {
        CompressionCodec<?> codec = CompressionFormats.require(formatName).defaultCodec();
        return remainingBytes(codec.compress(ByteBuffer.wrap(archive)));
    }

    /// Creates a deterministic split archive through Arkivo's public multi-volume writer.
    ///
    /// @param formatName the installed archive format name
    /// @param splitSize the positive output volume size
    /// @return at least two committed physical volumes
    /// @throws IOException if the archive cannot be encoded
    static @Unmodifiable List<byte @Unmodifiable []> createSplitArchiveSeed(
            String formatName,
            long splitSize
    ) throws IOException {
        byte[] content = new byte[SPLIT_ARCHIVE_CONTENT_SIZE];
        int state = 0x6d2b79f5;
        for (int index = 0; index < content.length; index++) {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            content[index] = (byte) state;
        }

        InMemoryVolumeTarget target = new InMemoryVolumeTarget();
        try (ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter(formatName, target, splitSize)) {
            try (OutputStream body = writer.beginFile("split-seed.bin").openOutputStream()) {
                body.write(content);
            }
        }
        List<byte[]> volumes = target.volumes();
        if (volumes.size() < 2) {
            throw new IOException("Split archive seed did not cross a volume boundary: " + formatName);
        }
        return volumes;
    }

    /// Creates a two-volume stored RAR4 archive without committing binary fixtures to the repository.
    ///
    /// @return two deterministic physical RAR volumes
    /// @throws IOException if the in-memory RAR structure cannot be encoded
    static @Unmodifiable List<byte @Unmodifiable []> createSplitRARSeed() throws IOException {
        byte[] firstPart = "split ".getBytes(StandardCharsets.UTF_8);
        byte[] secondPart = "rar seed".getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[firstPart.length + secondPart.length];
        System.arraycopy(firstPart, 0, content, 0, firstPart.length);
        System.arraycopy(secondPart, 0, content, firstPart.length, secondPart.length);
        CRC32 contentCRC = new CRC32();
        contentCRC.update(content);

        byte[] firstVolume = createRAR4Volume(
                false,
                rar4FileFields("split.txt", content.length, contentCRC.getValue(), firstPart),
                0x8002L,
                firstPart
        );
        byte[] secondVolume = createRAR4Volume(
                true,
                rar4FileFields("split.txt", content.length, contentCRC.getValue(), secondPart),
                0x8001L,
                secondPart
        );
        return List.of(firstVolume, secondVolume);
    }

    /// Creates one stored RAR4 archive with the requested number of complete entries.
    private static byte @Unmodifiable [] createRAR4ArchiveSeed(int entryCount) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(RAR4_SIGNATURE);
        writeRAR4Block(output, 0x73, 0L, new byte[6], new byte[0]);

        CRC32 contentCRC = new CRC32();
        contentCRC.update(SEED_CONTENT);
        for (int index = 0; index < entryCount; index++) {
            writeRAR4Block(
                    output,
                    0x74,
                    0L,
                    rar4FileFields(
                            archiveSeedEntryName(index),
                            SEED_CONTENT.length,
                            contentCRC.getValue(),
                            SEED_CONTENT
                    ),
                    SEED_CONTENT
            );
        }
        writeRAR4Block(output, 0x7b, 0L, new byte[0], new byte[0]);
        return output.toByteArray();
    }

    /// Creates one RAR4 volume containing a single stored file part.
    private static byte @Unmodifiable [] createRAR4Volume(
            boolean finalVolume,
            byte @Unmodifiable [] fileFields,
            long fileFlags,
            byte @Unmodifiable [] body
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(RAR4_SIGNATURE);
        writeRAR4Block(output, 0x73, 0L, new byte[6], new byte[0]);
        writeRAR4Block(output, 0x74, fileFlags, fileFields, body);
        if (finalVolume) {
            writeRAR4Block(output, 0x7b, 0L, new byte[0], new byte[0]);
        }
        return output.toByteArray();
    }

    /// Encodes the fixed fields of one stored RAR4 file part.
    private static byte @Unmodifiable [] rar4FileFields(
            String path,
            long unpackedSize,
            long contentCRC,
            byte @Unmodifiable [] body
    ) throws IOException {
        byte[] name = path.getBytes(StandardCharsets.UTF_8);
        ByteBuffer fields = ByteBuffer.allocate(25 + name.length).order(ByteOrder.LITTLE_ENDIAN);
        fields.putInt(body.length);
        fields.putInt(Math.toIntExact(unpackedSize));
        fields.put((byte) 3);
        fields.putInt((int) contentCRC);
        fields.putInt(0);
        fields.put((byte) 29);
        fields.put((byte) 0x30);
        fields.putShort((short) name.length);
        fields.putInt(0100644);
        fields.put(name);
        return fields.array();
    }

    /// Writes one RAR4 block and its optional data area.
    private static void writeRAR4Block(
            ByteArrayOutputStream output,
            int type,
            long flags,
            byte @Unmodifiable [] fields,
            byte @Unmodifiable [] data
    ) throws IOException {
        byte[] header = new byte[5 + fields.length];
        ByteBuffer headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        headerBuffer.put((byte) type);
        headerBuffer.putShort((short) flags);
        headerBuffer.putShort((short) (7 + fields.length));
        headerBuffer.put(fields);
        CRC32 headerCRC = new CRC32();
        headerCRC.update(header);
        byte[] checksum = new byte[2];
        ByteBuffer.wrap(checksum).order(ByteOrder.LITTLE_ENDIAN).putShort((short) headerCRC.getValue());
        output.write(checksum);
        output.write(header);
        output.write(data);
    }
}
