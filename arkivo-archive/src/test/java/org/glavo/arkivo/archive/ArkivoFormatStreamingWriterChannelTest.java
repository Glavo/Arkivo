// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.channels.WritableByteChannel;
import java.nio.file.attribute.FileAttributeView;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that archive streaming writer formats are channel-first.
@NotNullByDefault
public final class ArkivoFormatStreamingWriterChannelTest {
    /// Verifies the channel overload is the implementation contract and the stream overload is an adapter.
    @Test
    public void channelMethodDefinesImplementationContract() throws NoSuchMethodException {
        Method channelMethod = ArkivoFormat.StreamingWriter.class.getMethod(
                "openStreamingWriter",
                WritableByteChannel.class,
                ArchiveCreateOptions.class
        );
        Method streamMethod = ArkivoFormat.StreamingWriter.class.getMethod(
                "openStreamingWriter",
                OutputStream.class,
                ArchiveCreateOptions.class
        );

        assertTrue(Modifier.isAbstract(channelMethod.getModifiers()));
        assertFalse(channelMethod.isDefault());
        assertTrue(streamMethod.isDefault());
    }

    /// Verifies the default stream convenience method dispatches through the channel implementation.
    @Test
    public void streamFactoryAdaptsToChannelImplementation() throws IOException {
        TestStreamingWriterFormat format = new TestStreamingWriterFormat();
        ByteArrayOutputStream target = new ByteArrayOutputStream();

        ArkivoStreamingWriter writer = format.openStreamingWriter(target, ArchiveCreateOptions.DEFAULT);
        WritableByteChannel openedChannel = Objects.requireNonNull(format.openedChannel);
        assertSame(openedChannel, ((TestStreamingWriter) writer).target);
        assertTrue(openedChannel.isOpen());

        writer.close();
        assertFalse(openedChannel.isOpen());
    }

    /// Records the channel received through the abstract writer contract.
    @NotNullByDefault
    private static final class TestStreamingWriterFormat implements ArkivoFormat.StreamingWriter {
        /// The channel received by the implementation.
        private @Nullable WritableByteChannel openedChannel;

        /// Returns the test format name.
        @Override
        public String name() {
            return "test";
        }

        /// Opens a writer directly over the provided channel.
        @Override
        public ArkivoStreamingWriter openStreamingWriter(
                WritableByteChannel target,
                ArchiveCreateOptions options
        ) {
            openedChannel = target;
            return new TestStreamingWriter(target);
        }
    }

    /// Owns a channel without writing archive entries.
    @NotNullByDefault
    private static final class TestStreamingWriter extends ArkivoStreamingWriter {
        /// The owned target channel.
        private final WritableByteChannel target;

        /// Creates a writer over the target channel.
        private TestStreamingWriter(WritableByteChannel target) {
            this.target = target;
        }

        /// Rejects file creation in the minimal test writer.
        @Override
        protected void beginFileEntry(String path) {
            throw new UnsupportedOperationException("Test writer does not create entries");
        }

        /// Rejects directory creation in the minimal test writer.
        @Override
        protected void beginDirectoryEntry(String path) {
            throw new UnsupportedOperationException("Test writer does not create entries");
        }

        /// Rejects symbolic-link creation in the minimal test writer.
        @Override
        protected void beginSymbolicLinkEntry(String path, String linkTarget) {
            throw new UnsupportedOperationException("Test writer does not create entries");
        }

        /// Returns no configurable attribute view.
        @Override
        protected <V extends FileAttributeView> @Nullable V currentAttributeView(Class<V> type) {
            return null;
        }

        /// Rejects entry completion in the minimal test writer.
        @Override
        protected void finishCurrentEntry() {
            throw new IllegalStateException("No current test entry");
        }

        /// Rejects body access in the minimal test writer.
        @Override
        protected WritableByteChannel openCurrentChannel() {
            throw new IllegalStateException("No current test entry");
        }

        /// Closes the owned target channel.
        @Override
        protected void closeWriter() throws IOException {
            target.close();
        }
    }
}
