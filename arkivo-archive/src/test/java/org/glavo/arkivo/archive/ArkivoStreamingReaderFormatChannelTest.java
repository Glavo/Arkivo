// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that archive streaming format implementations are channel-first.
@NotNullByDefault
public final class ArkivoStreamingReaderFormatChannelTest {
    /// Verifies the channel overload is the implementation contract and the stream overload is an adapter.
    @Test
    public void channelMethodDefinesImplementationContract() throws NoSuchMethodException {
        Method channelMethod = ArkivoStreamingReaderFormat.class.getMethod(
                "openStreamingReader",
                ReadableByteChannel.class,
                ArchiveReadOptions.class
        );
        Method streamMethod = ArkivoStreamingReaderFormat.class.getMethod(
                "openStreamingReader",
                InputStream.class,
                ArchiveReadOptions.class
        );

        assertTrue(Modifier.isAbstract(channelMethod.getModifiers()));
        assertFalse(channelMethod.isDefault());
        assertTrue(streamMethod.isDefault());
    }

    /// Verifies the default stream convenience method dispatches through the channel implementation.
    @Test
    public void streamFactoryAdaptsToChannelImplementation() throws IOException {
        TestStreamingReaderFormat format = new TestStreamingReaderFormat();
        ByteArrayInputStream source = new ByteArrayInputStream(new byte[]{1, 2, 3});

        ArkivoStreamingReader reader = format.openStreamingReader(source, ArchiveReadOptions.DEFAULT);
        ReadableByteChannel openedChannel = Objects.requireNonNull(format.openedChannel);
        assertSame(openedChannel, ((TestStreamingReader) reader).source);
        assertTrue(openedChannel.isOpen());

        reader.close();
        assertFalse(openedChannel.isOpen());
    }

    /// Verifies the default path convenience method owns the opened channel.
    @Test
    public void pathFactoryOpensAndOwnsChannel() throws IOException {
        Path path = Files.createTempFile("arkivo-reader-format-", ".bin");
        try {
            TestStreamingReaderFormat format = new TestStreamingReaderFormat();
            ArkivoStreamingReader reader = format.openStreamingReader(path, ArchiveReadOptions.DEFAULT);
            ReadableByteChannel openedChannel = Objects.requireNonNull(format.openedChannel);

            assertTrue(openedChannel.isOpen());
            reader.close();
            assertFalse(openedChannel.isOpen());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Verifies the default path convenience method closes its channel when reader setup fails.
    @Test
    public void pathFactoryClosesChannelAfterSetupFailure() throws IOException {
        Path path = Files.createTempFile("arkivo-reader-format-failure-", ".bin");
        try {
            FailingStreamingReaderFormat format = new FailingStreamingReaderFormat();

            assertThrows(IOException.class, () -> format.openStreamingReader(path, ArchiveReadOptions.DEFAULT));

            assertNotNull(format.openedChannel);
            assertFalse(format.openedChannel.isOpen());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    /// Records the channel received through the abstract format contract.
    @NotNullByDefault
    private static final class TestStreamingReaderFormat implements ArkivoStreamingReaderFormat {
        /// The channel received by the implementation.
        private @Nullable ReadableByteChannel openedChannel;

        /// Returns the test format name.
        @Override
        public String name() {
            return "test";
        }

        /// Opens a reader directly over the provided channel.
        @Override
        public ArkivoStreamingReader openStreamingReader(
                ReadableByteChannel source,
                ArchiveReadOptions options
        ) {
            openedChannel = source;
            return new TestStreamingReader(source);
        }
    }

    /// Records and rejects the channel received through the path adapter.
    @NotNullByDefault
    private static final class FailingStreamingReaderFormat implements ArkivoStreamingReaderFormat {
        /// The channel received before setup fails.
        private @Nullable ReadableByteChannel openedChannel;

        /// Returns the test format name.
        @Override
        public String name() {
            return "failing";
        }

        /// Records the channel and reports a setup failure.
        @Override
        public ArkivoStreamingReader openStreamingReader(
                ReadableByteChannel source,
                ArchiveReadOptions options
        ) throws IOException {
            openedChannel = source;
            throw new IOException("setup failed");
        }
    }

    /// Owns a channel without exposing archive entries.
    @NotNullByDefault
    private static final class TestStreamingReader extends ArkivoStreamingReader {
        /// The owned source channel.
        private final ReadableByteChannel source;

        /// Creates a reader over the source channel.
        private TestStreamingReader(ReadableByteChannel source) {
            this.source = source;
        }

        /// Reports that the test archive has no entries.
        @Override
        protected boolean advance() {
            return false;
        }

        /// Rejects attribute reads because no entry exists.
        @Override
        protected <A extends BasicFileAttributes> A readCurrentAttributes(Class<A> type) {
            throw new IllegalStateException("No current test entry");
        }

        /// Rejects body access because no entry exists.
        @Override
        protected ReadableByteChannel openCurrentChannel() {
            throw new IllegalStateException("No current test entry");
        }

        /// Closes the owned source channel.
        @Override
        protected void closeReader() throws IOException {
            source.close();
        }
    }
}
