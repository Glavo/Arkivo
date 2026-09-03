// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies generic archive aggregation across every detectable outer compression format.
@NotNullByDefault
public final class OuterCompressionMatrixTest {
    /// The archive formats available through a forward-only reader.
    private static final @Unmodifiable List<String> STREAMING_FORMATS =
            List.of("ar", "cpio", "rar", "tar", "zip");

    /// The generated archive formats available through a random-access file system.
    private static final @Unmodifiable List<String> FILE_SYSTEM_FORMATS =
            List.of("7z", "ar", "rar", "tar", "zip");

    /// Installed compression formats whose default encodings can be detected without an external hint.
    private static final @Unmodifiable List<CompressionFormat> OUTER_FORMATS =
            CompressionFormats.installed().stream().filter(format -> format.probeSize() > 0).toList();

    /// The regular-file path stored in generated archives.
    private static final String ENTRY_PATH = "entry.txt";

    /// The deterministic regular-file content stored in generated archives.
    private static final byte @Unmodifiable [] ENTRY_CONTENT =
            "Arkivo outer compression matrix\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /// Creates an outer-compression matrix test instance for JUnit.
    public OuterCompressionMatrixTest() {
    }

    /// Opens every forward-only archive format through every detectable compression format and fragmented input.
    ///
    /// @param archiveFormat the logical archive format
    /// @param compressionFormat the outer compression format
    /// @throws IOException if a generated valid combination cannot be read
    @ParameterizedTest(name = "streaming {0} inside {1}")
    @MethodSource("streamingCases")
    void opensEveryStreamingFormatThroughEveryOuterCompression(
            String archiveFormat,
            String compressionFormat
    ) throws IOException {
        byte[] encoded = compress(compressionFormat, createArchive(archiveFormat));
        FragmentedSeekableByteChannel source = new FragmentedSeekableByteChannel(
                encoded,
                1 + Math.floorMod(archiveFormat.hashCode() ^ compressionFormat.hashCode(), 17)
        );

        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                (ReadableByteChannel) source,
                ArchiveReadOptions.DEFAULT
        )) {
            verifyStreamingArchive(reader);
        }
        assertFalse(source.isOpen(), "The streaming reader must close its owned physical source");
    }

    /// Opens every generated file-system archive format through every detectable compression format and short reads.
    ///
    /// @param archiveFormat the logical archive format
    /// @param compressionFormat the outer compression format
    /// @throws IOException if a generated valid combination cannot be mounted
    @ParameterizedTest(name = "file system {0} inside {1}")
    @MethodSource("fileSystemCases")
    void opensEveryFileSystemFormatThroughEveryOuterCompression(
            String archiveFormat,
            String compressionFormat
    ) throws IOException {
        byte[] encoded = compress(compressionFormat, createArchive(archiveFormat));
        FragmentedSeekableByteChannel source = new FragmentedSeekableByteChannel(
                encoded,
                1 + Math.floorMod(archiveFormat.hashCode() + compressionFormat.hashCode(), 19)
        );

        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(source, ArchiveReadOptions.DEFAULT)) {
            assertArrayEquals(ENTRY_CONTENT, Files.readAllBytes(fileSystem.getPath('/' + ENTRY_PATH)));
        }
        assertFalse(source.isOpen(), "The archive file system must close its owned physical source");
    }

    /// Decodes two nested outer wrappers through both generic streaming and file-system entry points.
    ///
    /// Each detectable format occurs once as the inner wrapper and once as the outer wrapper across the matrix.
    ///
    /// @param innerFormat the wrapper decoded second
    /// @param outerFormat the wrapper decoded first
    /// @throws IOException if a generated valid nested encoding cannot be opened
    @ParameterizedTest(name = "tar inside {0} inside {1}")
    @MethodSource("nestedCases")
    void opensTwoNestedOuterCompressionLayers(String innerFormat, String outerFormat) throws IOException {
        byte[] archive = createArchive("tar");
        byte[] encoded = compress(outerFormat, compress(innerFormat, archive));

        FragmentedSeekableByteChannel streamingSource = new FragmentedSeekableByteChannel(encoded, 3);
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                (ReadableByteChannel) streamingSource,
                ArchiveReadOptions.DEFAULT
        )) {
            verifyStreamingArchive(reader);
        }
        assertFalse(streamingSource.isOpen());

        FragmentedSeekableByteChannel fileSystemSource = new FragmentedSeekableByteChannel(encoded, 5);
        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(
                fileSystemSource,
                ArchiveReadOptions.DEFAULT
        )) {
            assertArrayEquals(ENTRY_CONTENT, Files.readAllBytes(fileSystem.getPath('/' + ENTRY_PATH)));
        }
        assertFalse(fileSystemSource.isOpen());
    }

    /// Rejects a second outer wrapper at the configured boundary and closes both kinds of owned source.
    @Test
    void enforcesOuterCompressionLayerLimitAndClosesSources() throws IOException {
        String innerFormat = OUTER_FORMATS.get(0).name();
        String outerFormat = OUTER_FORMATS.get(OUTER_FORMATS.size() - 1).name();
        byte[] encoded = compress(outerFormat, compress(innerFormat, createArchive("tar")));
        ArchiveReadOptions options = ArchiveReadOptions.DEFAULT.withLimits(
                ArchiveReadLimits.builder().maximumOuterCompressionLayers(1L).build()
        );

        FragmentedSeekableByteChannel streamingSource = new FragmentedSeekableByteChannel(encoded, 1);
        ArkivoReadLimitException streamingFailure = assertThrows(
                ArkivoReadLimitException.class,
                () -> {
                    try (ArkivoStreamingReader ignored = ArkivoFormats.openStreamingReader(
                            (ReadableByteChannel) streamingSource,
                            options
                    )) {
                        // A successful open would violate the configured outer-layer limit.
                    }
                }
        );
        assertOuterLayerFailure(streamingFailure);
        assertFalse(streamingSource.isOpen());

        FragmentedSeekableByteChannel fileSystemSource = new FragmentedSeekableByteChannel(encoded, 2);
        ArkivoReadLimitException fileSystemFailure = assertThrows(
                ArkivoReadLimitException.class,
                () -> {
                    try (ArkivoFileSystem ignored = ArkivoFormats.openFileSystem(fileSystemSource, options)) {
                        // A successful mount would violate the configured outer-layer limit.
                    }
                }
        );
        assertOuterLayerFailure(fileSystemFailure);
        assertFalse(fileSystemSource.isOpen());
    }

    /// Supplies every forward-only archive and detectable compression pair.
    ///
    /// @return the complete streaming cross product
    private static Stream<Arguments> streamingCases() {
        return STREAMING_FORMATS.stream().flatMap(archiveFormat -> OUTER_FORMATS.stream().map(
                compressionFormat -> Arguments.of(archiveFormat, compressionFormat.name())
        ));
    }

    /// Supplies every generated file-system archive and detectable compression pair.
    ///
    /// @return the complete file-system cross product
    private static Stream<Arguments> fileSystemCases() {
        return FILE_SYSTEM_FORMATS.stream().flatMap(archiveFormat -> OUTER_FORMATS.stream().map(
                compressionFormat -> Arguments.of(archiveFormat, compressionFormat.name())
        ));
    }

    /// Supplies a ring of nested wrappers that uses every detectable compression format in both positions.
    ///
    /// @return nested inner and outer wrapper names
    private static Stream<Arguments> nestedCases() {
        return IntStream.range(0, OUTER_FORMATS.size()).mapToObj(index -> Arguments.of(
                OUTER_FORMATS.get(index).name(),
                OUTER_FORMATS.get((index + 1) % OUTER_FORMATS.size()).name()
        ));
    }

    /// Creates a small valid archive through the public writer, or a source-generated RAR4 seed for the read-only format.
    ///
    /// @param formatName the installed archive format name
    /// @return the complete archive bytes
    /// @throws IOException if the writable archive cannot be encoded
    private static byte @Unmodifiable [] createArchive(String formatName) throws IOException {
        if ("rar".equals(formatName)) {
            return ArchiveTestFixtures.createRar4Archive(ENTRY_PATH, ENTRY_CONTENT);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter(formatName, output)) {
            try (OutputStream body = writer.beginFile(ENTRY_PATH).openOutputStream()) {
                body.write(ENTRY_CONTENT);
            }
        }
        return output.toByteArray();
    }

    /// Encodes one complete outer wrapper with the installed default codec.
    ///
    /// @param formatName the compression format name
    /// @param input the bytes to encode
    /// @return the complete compressed stream
    /// @throws IOException if compression fails
    private static byte @Unmodifiable [] compress(
            String formatName,
            byte @Unmodifiable [] input
    ) throws IOException {
        ByteBuffer encoded = CompressionFormats.require(formatName)
                .defaultCodec()
                .compress(ByteBuffer.wrap(input));
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        return result;
    }

    /// Verifies the single generated entry and its complete body.
    ///
    /// @param reader the detected reader
    /// @throws IOException if archive metadata or content cannot be read
    private static void verifyStreamingArchive(ArkivoStreamingReader reader) throws IOException {
        assertTrue(reader.next());
        ArchiveEntryAttributes attributes = reader.readAttributes();
        assertEquals(ENTRY_PATH, attributes.path());
        assertTrue(attributes.isRegularFile());
        try (InputStream body = reader.openInputStream()) {
            assertArrayEquals(ENTRY_CONTENT, body.readAllBytes());
        }
        assertFalse(reader.next());
    }

    /// Verifies the exact archive-wide diagnostic for a rejected second wrapper.
    ///
    /// @param failure the reported read-limit failure
    private static void assertOuterLayerFailure(ArkivoReadLimitException failure) {
        assertEquals(ArkivoReadLimitKind.OUTER_COMPRESSION_LAYERS, failure.kind());
        assertEquals(1L, failure.maximum());
        assertEquals(2L, failure.actual());
        assertNull(failure.entryPath());
    }

    /// Exposes immutable bytes through bounded short reads and random-access positioning.
    @NotNullByDefault
    private static final class FragmentedSeekableByteChannel implements SeekableByteChannel {
        /// The immutable source bytes.
        private final byte @Unmodifiable [] content;

        /// The largest positive byte count returned by one read.
        private final int maximumReadSize;

        /// The current channel position.
        private long position;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Creates a read-only channel with the requested fragmentation bound.
        ///
        /// @param content the immutable bytes exposed by this channel
        /// @param maximumReadSize the positive maximum size of one read
        /// @throws IllegalArgumentException if `maximumReadSize` is not positive
        private FragmentedSeekableByteChannel(
                byte @Unmodifiable [] content,
                int maximumReadSize
        ) {
            if (maximumReadSize <= 0) {
                throw new IllegalArgumentException("maximumReadSize must be positive");
            }
            this.content = content;
            this.maximumReadSize = maximumReadSize;
        }

        /// Reads at most the configured fragment size from the current position.
        ///
        /// @param target the writable target buffer
        /// @return a positive byte count, zero for an empty target, or `-1` at end-of-input
        /// @throws IOException if this channel is closed
        @Override
        public int read(ByteBuffer target) throws IOException {
            requireOpen();
            if (!target.hasRemaining()) {
                return 0;
            }
            if (position >= content.length) {
                return -1;
            }
            int count = Math.min(
                    Math.min(content.length - Math.toIntExact(position), target.remaining()),
                    maximumReadSize
            );
            target.put(content, Math.toIntExact(position), count);
            position += count;
            return count;
        }

        /// Rejects writes to this read-only channel.
        ///
        /// @param source the unconsumed source buffer
        /// @return this method never returns normally
        /// @throws NonWritableChannelException always
        @Override
        public int write(ByteBuffer source) throws NonWritableChannelException {
            throw new NonWritableChannelException();
        }

        /// Returns the current channel position.
        ///
        /// @return the nonnegative byte position
        /// @throws IOException if this channel is closed
        @Override
        public long position() throws IOException {
            requireOpen();
            return position;
        }

        /// Changes the current channel position.
        ///
        /// @param newPosition the nonnegative position, which may exceed the content size
        /// @return this channel
        /// @throws IOException if this channel is closed
        /// @throws IllegalArgumentException if `newPosition` is negative
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            requireOpen();
            if (newPosition < 0L) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            position = newPosition;
            return this;
        }

        /// Returns the immutable content size.
        ///
        /// @return the source byte count
        /// @throws IOException if this channel is closed
        @Override
        public long size() throws IOException {
            requireOpen();
            return content.length;
        }

        /// Rejects truncation of this read-only channel.
        ///
        /// @param size the ignored requested size
        /// @return this method never returns normally
        /// @throws NonWritableChannelException always
        @Override
        public SeekableByteChannel truncate(long size) throws NonWritableChannelException {
            throw new NonWritableChannelException();
        }

        /// Returns whether this channel accepts further reads and positioning operations.
        ///
        /// @return `true` until [#close()] is called
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel without changing its immutable content.
        @Override
        public void close() {
            open = false;
        }

        /// Requires this channel to remain open.
        ///
        /// @throws ClosedChannelException if this channel has been closed
        private void requireOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}
