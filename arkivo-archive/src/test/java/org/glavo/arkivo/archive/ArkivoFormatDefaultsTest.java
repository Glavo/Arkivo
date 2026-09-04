// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies the shared metadata and convenience-factory behavior of archive format capabilities.
@NotNullByDefault
public final class ArkivoFormatDefaultsTest {
    /// Temporary storage used to exercise path-backed factories.
    @TempDir
    public Path temporaryDirectory;

    /// Verifies the metadata and prefix-matching defaults supplied by the root format interface.
    @Test
    public void suppliesConservativeRootDefaults() {
        RecordingFormat format = new RecordingFormat();
        ByteBuffer prefix = ByteBuffer.wrap(new byte[]{1, 2, 3, 4}).order(ByteOrder.LITTLE_ENDIAN);
        prefix.position(1);
        prefix.mark();
        prefix.limit(3);

        assertEquals(List.of(), format.aliases());
        assertEquals(List.of("test"), format.fileExtensions());
        assertFalse(format.supportsOuterCompression());
        assertEquals(0, format.probeSize());
        assertFalse(format.matches(prefix));
        assertEquals(1, prefix.position());
        assertEquals(3, prefix.limit());
        assertSame(ByteOrder.LITTLE_ENDIAN, prefix.order());
        prefix.reset();
        assertEquals(1, prefix.position());
        assertEquals("arkivo+test", format.uriScheme());
    }

    /// Verifies single-stream and multi-volume streaming convenience factories use their default options.
    @Test
    public void streamingFactoriesSupplyDefaultOptions() throws IOException {
        RecordingFormat format = new RecordingFormat();
        ArkivoVolumeSource volumeSource = index -> null;
        ArkivoVolumeTarget volumeTarget = () -> {
            throw new AssertionError("The recording format must not open the volume target");
        };

        try (ReadableByteChannel source = Channels.newChannel(InputStream.nullInputStream());
             WritableByteChannel target = Channels.newChannel(OutputStream.nullOutputStream())) {
            assertSame(format.stop, assertThrows(
                    UnsupportedOperationException.class,
                    () -> format.openStreamingReader(source)
            ));
            assertInvocation(format, "stream-reader", source, null, 0L, ArchiveReadOptions.DEFAULT);

            assertSame(format.stop, assertThrows(
                    UnsupportedOperationException.class,
                    () -> format.openStreamingWriter(target)
            ));
            assertInvocation(format, "stream-writer", target, null, 0L, ArchiveCreateOptions.DEFAULT);
        }

        assertSame(format.stop, assertThrows(
                UnsupportedOperationException.class,
                () -> format.openStreamingReader(volumeSource)
        ));
        assertInvocation(format, "volume-stream-reader", volumeSource, null, 0L, ArchiveReadOptions.DEFAULT);

        assertSame(format.stop, assertThrows(
                UnsupportedOperationException.class,
                () -> format.openStreamingWriter(volumeTarget, 4096L)
        ));
        assertInvocation(format, "volume-stream-writer", volumeTarget, null, 4096L, ArchiveCreateOptions.DEFAULT);
    }

    /// Verifies file-system convenience factories preserve their sources and supply default read options.
    @Test
    public void fileSystemFactoriesSupplyDefaultOptions() throws IOException {
        RecordingFormat format = new RecordingFormat();
        ArkivoSeekableChannelSource repeatableSource = () -> {
            throw new AssertionError("The recording format must not open the repeatable source");
        };
        ArkivoVolumeSource volumeSource = index -> null;

        assertSame(format.stop, assertThrows(
                UnsupportedOperationException.class,
                () -> format.open(repeatableSource)
        ));
        assertInvocation(format, "repeatable-file-system", repeatableSource, null, 0L, ArchiveReadOptions.DEFAULT);

        try (SeekableByteChannel source = Files.newByteChannel(
                temporaryDirectory.resolve("direct.bin"),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        )) {
            assertSame(format.stop, assertThrows(
                    UnsupportedOperationException.class,
                    () -> format.open(source)
            ));
            assertInvocation(format, "channel-file-system", source, null, 0L, ArchiveReadOptions.DEFAULT);
        }

        assertSame(format.stop, assertThrows(
                UnsupportedOperationException.class,
                () -> format.open(volumeSource)
        ));
        assertInvocation(format, "volume-file-system", volumeSource, null, 0L, ArchiveReadOptions.DEFAULT);
    }

    /// Verifies a path-backed file-system factory closes its temporary channel when setup fails.
    @Test
    public void pathFileSystemFactoryClosesChannelAfterSetupFailure() throws IOException {
        RecordingFormat format = new RecordingFormat();
        Path path = temporaryDirectory.resolve("archive.bin");
        Files.write(path, new byte[]{1, 2, 3});

        assertSame(format.stop, assertThrows(
                UnsupportedOperationException.class,
                () -> format.open(path)
        ));
        assertEquals("channel-file-system", format.operation);
        assertNull(format.secondArgument);
        assertEquals(0L, format.size);
        assertSame(ArchiveReadOptions.DEFAULT, format.options);
        SeekableByteChannel openedChannel = assertInstanceOf(SeekableByteChannel.class, format.firstArgument);
        assertFalse(openedChannel.isOpen());
    }

    /// Verifies writable file-system convenience factories supply default creation and update options.
    @Test
    public void writableFileSystemFactoriesSupplyDefaultOptions() throws IOException {
        RecordingFormat format = new RecordingFormat();
        Path path = temporaryDirectory.resolve("archive.bin");
        ArkivoVolumeSource volumeSource = index -> null;
        ArkivoVolumeTarget volumeTarget = () -> {
            throw new AssertionError("The recording format must not open the volume target");
        };

        assertSame(format.stop, assertThrows(
                UnsupportedOperationException.class,
                () -> format.create(path)
        ));
        assertInvocation(format, "path-create", path, null, 0L, ArchiveCreateOptions.DEFAULT);

        assertSame(format.stop, assertThrows(
                UnsupportedOperationException.class,
                () -> format.update(path)
        ));
        assertInvocation(format, "path-update", path, null, 0L, ArchiveUpdateOptions.DEFAULT);

        assertSame(format.stop, assertThrows(
                UnsupportedOperationException.class,
                () -> format.create(volumeTarget, 8192L)
        ));
        assertInvocation(format, "volume-create", volumeTarget, null, 8192L, ArchiveCreateOptions.DEFAULT);

        assertSame(format.stop, assertThrows(
                UnsupportedOperationException.class,
                () -> format.update(volumeSource, volumeTarget, 16384L)
        ));
        assertInvocation(
                format,
                "volume-update",
                volumeSource,
                volumeTarget,
                16384L,
                ArchiveUpdateOptions.DEFAULT
        );
    }

    /// Verifies the most recent invocation recorded by a minimal multi-capability format.
    private static void assertInvocation(
            RecordingFormat format,
            String operation,
            Object firstArgument,
            @Nullable Object secondArgument,
            long size,
            Object options
    ) {
        assertEquals(operation, format.operation);
        assertSame(firstArgument, format.firstArgument);
        assertSame(secondArgument, format.secondArgument);
        assertEquals(size, format.size);
        assertSame(options, format.options);
    }

    /// Records dispatch through every abstract factory contract and stops before constructing an archive object.
    @NotNullByDefault
    private static final class RecordingFormat implements
            ArkivoFormat.VolumeStreamingReadable,
            ArkivoFormat.VolumeStreamingWritable,
            ArkivoFormat.FileSystem.Writable,
            ArkivoFormat.VolumeFileSystem.Writable {
        /// The exception used to stop a factory after recording its arguments.
        private final UnsupportedOperationException stop =
                new UnsupportedOperationException("Recorded archive format factory invocation");

        /// The most recently invoked factory name, or `null` before the first invocation.
        private @Nullable String operation;

        /// The most recent primary factory argument, or `null` before the first invocation.
        private @Nullable Object firstArgument;

        /// The most recent secondary factory argument, or `null` when the factory has none.
        private @Nullable Object secondArgument;

        /// The most recent split size, or zero when the factory has none.
        private long size;

        /// The most recent options object, or `null` before the first invocation.
        private @Nullable Object options;

        /// Creates an empty factory recorder.
        private RecordingFormat() {
        }

        /// Returns the stable test format name.
        @Override
        public String name() {
            return "test";
        }

        /// Records the channel-backed streaming reader factory.
        @Override
        public ArkivoStreamingReader openStreamingReader(
                ReadableByteChannel source,
                ArchiveReadOptions options
        ) {
            throw record("stream-reader", source, null, 0L, options);
        }

        /// Records the multi-volume streaming reader factory.
        @Override
        public ArkivoStreamingReader openStreamingReader(
                ArkivoVolumeSource source,
                ArchiveReadOptions options
        ) {
            throw record("volume-stream-reader", source, null, 0L, options);
        }

        /// Records the channel-backed streaming writer factory.
        @Override
        public ArkivoStreamingWriter openStreamingWriter(
                WritableByteChannel target,
                ArchiveCreateOptions options
        ) {
            throw record("stream-writer", target, null, 0L, options);
        }

        /// Records the multi-volume streaming writer factory.
        @Override
        public ArkivoStreamingWriter openStreamingWriter(
                ArkivoVolumeTarget target,
                long splitSize,
                ArchiveCreateOptions options
        ) {
            throw record("volume-stream-writer", target, null, splitSize, options);
        }

        /// Records the repeatable-source file-system factory.
        @Override
        public ArkivoFileSystem open(
                ArkivoSeekableChannelSource source,
                ArchiveReadOptions options
        ) {
            throw record("repeatable-file-system", source, null, 0L, options);
        }

        /// Records the direct-channel file-system factory.
        @Override
        public ArkivoFileSystem open(
                SeekableByteChannel source,
                ArchiveReadOptions options
        ) {
            throw record("channel-file-system", source, null, 0L, options);
        }

        /// Records the multi-volume file-system factory.
        @Override
        public ArkivoFileSystem open(
                ArkivoVolumeSource source,
                ArchiveReadOptions options
        ) {
            throw record("volume-file-system", source, null, 0L, options);
        }

        /// Records the path-backed creation factory.
        @Override
        public ArkivoFileSystem create(Path path, ArchiveCreateOptions options) {
            throw record("path-create", path, null, 0L, options);
        }

        /// Records the path-backed update factory.
        @Override
        public ArkivoFileSystem update(Path path, ArchiveUpdateOptions options) {
            throw record("path-update", path, null, 0L, options);
        }

        /// Records the multi-volume creation factory.
        @Override
        public ArkivoFileSystem create(
                ArkivoVolumeTarget target,
                long splitSize,
                ArchiveCreateOptions options
        ) {
            throw record("volume-create", target, null, splitSize, options);
        }

        /// Records the multi-volume update factory.
        @Override
        public ArkivoFileSystem update(
                ArkivoVolumeSource source,
                ArkivoVolumeTarget target,
                long splitSize,
                ArchiveUpdateOptions options
        ) {
            throw record("volume-update", source, target, splitSize, options);
        }

        /// Stores one factory invocation and returns the shared stopping exception.
        private UnsupportedOperationException record(
                String operation,
                Object firstArgument,
                @Nullable Object secondArgument,
                long size,
                Object options
        ) {
            this.operation = operation;
            this.firstArgument = firstArgument;
            this.secondArgument = secondArgument;
            this.size = size;
            this.options = options;
            return stop;
        }
    }
}
