// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.glavo.arkivo.codec.internal.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the convenience factories and metadata defaults defined by [CompressionCodec].
@NotNullByDefault
final class CompressionCodecFactoryDefaultsTest {
    /// Verifies default format metadata and encoder options without relying on a concrete compression format.
    @Test
    void exposesFormatAndEncodingDefaults() throws IOException {
        FactoryState state = new FactoryState();
        PlainCodec codec = new PlainCodec(state);
        ByteBuffer prefix = ByteBuffer.wrap(new byte[]{1, 2, 3});
        prefix.position(1);

        assertEquals(List.of(), codec.aliases());
        assertEquals(List.of("plain"), codec.fileExtensions());
        assertEquals(0, codec.probeSize());
        assertFalse(codec.matches(prefix));
        assertEquals(1, prefix.position());
        assertEquals(3, prefix.limit());
        assertThrows(NullPointerException.class, () -> codec.matches(null));
        assertSame(codec, codec.format());
        assertSame(codec, codec.defaultCodec());

        assertEquals(CompressionCodec.UNKNOWN_SIZE, codec.maxCompressedSize(0L));
        assertEquals(CompressionCodec.UNKNOWN_SIZE, codec.maxCompressedSize(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> codec.maxCompressedSize(-1L));

        try (CompressionEncoder ignored = codec.newEncoder()) {
            assertSame(EncodingOptions.DEFAULT, state.lastEncodingOptions());
        }
        assertEquals(1, state.encoderCreations());
    }

    /// Verifies default channel and stream factories borrow their endpoints and preserve bytes.
    @Test
    void createsBorrowedChannelAndStreamAdapters() throws IOException {
        PlainCodec codec = new PlainCodec(new FactoryState());

        ByteArrayOutputStream channelBytes = new ByteArrayOutputStream();
        WritableByteChannel channelTarget = Channels.newChannel(channelBytes);
        CompressingWritableByteChannel encoder = codec.newWritableByteChannel(channelTarget);
        assertEquals(3, encoder.write(ByteBuffer.wrap(new byte[]{1, 2, 3})));
        encoder.finish();
        assertTrue(channelTarget.isOpen());
        assertArrayEquals(new byte[]{1, 2, 3}, channelBytes.toByteArray());

        ReadableByteChannel channelSource = Channels.newChannel(new ByteArrayInputStream(new byte[]{4, 5, 6}));
        DecompressingReadableByteChannel decoder = codec.newReadableByteChannel(channelSource);
        ByteBuffer decoded = ByteBuffer.allocate(3);
        assertEquals(3, decoder.read(decoded));
        assertEquals(-1, decoder.read(ByteBuffer.allocate(1)));
        decoder.close();
        assertTrue(channelSource.isOpen());
        assertArrayEquals(new byte[]{4, 5, 6}, decoded.array());

        TrackingOutputStream streamTarget = new TrackingOutputStream();
        try (OutputStream output = codec.newOutputStream(streamTarget)) {
            output.write(new byte[]{7, 8, 9});
        }
        assertEquals(0, streamTarget.closeCalls());
        assertArrayEquals(new byte[]{7, 8, 9}, streamTarget.toByteArray());

        TrackingInputStream streamSource = new TrackingInputStream(new byte[]{10, 11, 12});
        try (InputStream input = codec.newInputStream(streamSource)) {
            assertArrayEquals(new byte[]{10, 11, 12}, input.readAllBytes());
        }
        assertEquals(0, streamSource.closeCalls());
    }

    /// Verifies explicit stream ownership closes each endpoint exactly once.
    @Test
    void closesOwnedStreamEndpointsExactlyOnce() throws IOException {
        PlainCodec codec = new PlainCodec(new FactoryState());
        TrackingOutputStream target = new TrackingOutputStream();

        OutputStream output = codec.newOutputStream(
                target,
                EncodingOptions.DEFAULT,
                ResourceOwnership.OWNED
        );
        output.write(1);
        output.close();
        output.close();

        assertEquals(1, target.closeCalls());
        assertArrayEquals(new byte[]{1}, target.toByteArray());

        TrackingInputStream source = new TrackingInputStream(new byte[]{2});
        InputStream input = codec.newInputStream(source, ResourceOwnership.OWNED);
        assertEquals(2, input.read());
        input.close();
        input.close();

        assertEquals(1, source.closeCalls());
    }

    /// Verifies public factories reject null arguments before allocating an encoder or decoder.
    @Test
    void validatesFactoryArgumentsBeforeCreatingEngines() {
        FactoryState state = new FactoryState();
        PlainCodec codec = new PlainCodec(state);
        WritableByteChannel channelTarget = Channels.newChannel(new ByteArrayOutputStream());
        ReadableByteChannel channelSource = Channels.newChannel(new ByteArrayInputStream(new byte[0]));
        TrackingOutputStream streamTarget = new TrackingOutputStream();
        TrackingInputStream streamSource = new TrackingInputStream(new byte[0]);

        assertThrows(
                NullPointerException.class,
                () -> codec.newWritableByteChannel(null, EncodingOptions.DEFAULT, ResourceOwnership.BORROWED)
        );
        assertThrows(
                NullPointerException.class,
                () -> codec.newWritableByteChannel(channelTarget, null, ResourceOwnership.BORROWED)
        );
        assertThrows(
                NullPointerException.class,
                () -> codec.newWritableByteChannel(channelTarget, EncodingOptions.DEFAULT, null)
        );
        assertThrows(
                NullPointerException.class,
                () -> codec.newReadableByteChannel(null, ResourceOwnership.BORROWED)
        );
        assertThrows(
                NullPointerException.class,
                () -> codec.newReadableByteChannel(channelSource, null)
        );
        assertThrows(
                NullPointerException.class,
                () -> codec.newOutputStream(null, EncodingOptions.DEFAULT, ResourceOwnership.BORROWED)
        );
        assertThrows(
                NullPointerException.class,
                () -> codec.newOutputStream(streamTarget, null, ResourceOwnership.BORROWED)
        );
        assertThrows(
                NullPointerException.class,
                () -> codec.newOutputStream(streamTarget, EncodingOptions.DEFAULT, null)
        );
        assertThrows(
                NullPointerException.class,
                () -> codec.newInputStream(null, ResourceOwnership.BORROWED)
        );
        assertThrows(
                NullPointerException.class,
                () -> codec.newInputStream(streamSource, null)
        );

        assertEquals(0, state.encoderCreations());
        assertEquals(0, state.decoderCreations());
    }

    /// Verifies flush-only codec factories retain their narrower declared capability and validate before allocation.
    @Test
    void createsFlushOnlyFactories() throws IOException {
        FactoryState state = new FactoryState();
        FlushCodec codec = new FlushCodec(state);

        try (CompressionEncoder.Flushable encoder = codec.newEncoder()) {
            assertSame(EncodingOptions.DEFAULT, state.lastEncodingOptions());
            assertEquals(CodecOutcome.FLUSHED, encoder.flush(ByteBuffer.allocate(0)));
        }

        WritableByteChannel target = Channels.newChannel(new ByteArrayOutputStream());
        int creationsBeforeValidation = state.encoderCreations();
        assertThrows(
                NullPointerException.class,
                () -> codec.newWritableByteChannel(null, EncodingOptions.DEFAULT, ResourceOwnership.BORROWED)
        );
        assertThrows(
                NullPointerException.class,
                () -> codec.newWritableByteChannel(target, null, ResourceOwnership.BORROWED)
        );
        assertThrows(
                NullPointerException.class,
                () -> codec.newWritableByteChannel(target, EncodingOptions.DEFAULT, null)
        );
        assertEquals(creationsBeforeValidation, state.encoderCreations());

        CompressingWritableByteChannel.Flushable channel = codec.newWritableByteChannel(target);
        assertFalse(channel instanceof CompressingWritableByteChannel.Framed);
        channel.flush();
        channel.close();
        assertTrue(target.isOpen());
    }

    /// Verifies frame-only factories preserve frame typing for encoders and both channel directions.
    @Test
    void createsFrameOnlyFactories() throws IOException {
        FactoryState state = new FactoryState();
        FrameCodec codec = new FrameCodec(state);

        try (CompressionEncoder.Framed encoder = codec.newEncoder()) {
            encoder.finishFrame(ByteBuffer.allocate(0));
            encoder.startFrame();
            assertSame(EncodingOptions.DEFAULT, assertInstanceOf(FrameEncoder.class, encoder).lastFrameOptions());
        }

        WritableByteChannel target = Channels.newChannel(new ByteArrayOutputStream());
        int encoderCreationsBeforeValidation = state.encoderCreations();
        assertThrows(
                NullPointerException.class,
                () -> codec.newWritableByteChannel(null, EncodingOptions.DEFAULT, ResourceOwnership.BORROWED)
        );
        assertThrows(
                NullPointerException.class,
                () -> codec.newWritableByteChannel(target, null, ResourceOwnership.BORROWED)
        );
        assertThrows(
                NullPointerException.class,
                () -> codec.newWritableByteChannel(target, EncodingOptions.DEFAULT, null)
        );
        assertEquals(encoderCreationsBeforeValidation, state.encoderCreations());

        CompressingWritableByteChannel.Framed encoderChannel = codec.newWritableByteChannel(target);
        assertFalse(encoderChannel instanceof CompressingWritableByteChannel.Flushable);
        encoderChannel.finishFrame();
        encoderChannel.close();
        assertTrue(target.isOpen());

        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(new byte[0]));
        int decoderCreationsBeforeValidation = state.decoderCreations();
        assertThrows(
                NullPointerException.class,
                () -> codec.newReadableByteChannel(null, ResourceOwnership.BORROWED)
        );
        assertThrows(NullPointerException.class, () -> codec.newReadableByteChannel(source, null));
        assertEquals(decoderCreationsBeforeValidation, state.decoderCreations());

        DecompressingReadableByteChannel.Framed decoderChannel = codec.newReadableByteChannel(source);
        decoderChannel.close();
        assertTrue(source.isOpen());
    }

    /// Verifies the combined capability interface supplies its covariant default encoder factory.
    @Test
    void createsCombinedEncoderWithDefaultOptions() throws IOException {
        FactoryState state = new FactoryState();
        CombinedCodec codec = new CombinedCodec(state);

        try (CompressionEncoder.FlushableFramed encoder = codec.newEncoder()) {
            assertSame(EncodingOptions.DEFAULT, state.lastEncodingOptions());
            assertEquals(CodecOutcome.FLUSHED, encoder.flush(ByteBuffer.allocate(0)));
            assertEquals(CodecOutcome.BOUNDARY_REACHED, encoder.finishFrame(ByteBuffer.allocate(0)));
        }
    }

    /// Verifies seekable codec and index convenience methods delegate default options and borrowed ownership.
    ///
    @Test
    void delegatesSeekableConvenienceFactories() throws IOException {
        FactoryState factoryState = new FactoryState();
        SeekableFactoryState seekableState = new SeekableFactoryState();
        SeekableCodec codec = new SeekableCodec(factoryState, seekableState);
        WritableByteChannel target = Channels.newChannel(new ByteArrayOutputStream());

        assertTrue(codec.supportsSeekableEncoding());
        CompressingWritableByteChannel.Framed encoder = codec.newSeekableWritableByteChannel(target);
        assertSame(SeekableEncodingOptions.DEFAULT, seekableState.options());
        assertSame(ResourceOwnership.BORROWED, seekableState.ownership());
        encoder.close();
        assertTrue(target.isOpen());

        TestIndex index = new TestIndex();
        try (SeekableByteChannel source = new EmptySeekableByteChannel()) {
            assertSame(source, index.newReadableByteChannel(source));
            assertSame(source, index.source());
            assertSame(ResourceOwnership.BORROWED, index.ownership());
        }
    }

    /// Records engine construction performed by one immutable test codec family.
    @NotNullByDefault
    private static final class FactoryState {
        /// Number of encoders created.
        private final AtomicInteger encoderCreations = new AtomicInteger();

        /// Number of decoders created.
        private final AtomicInteger decoderCreations = new AtomicInteger();

        /// Options passed to the most recently created encoder.
        private final AtomicReference<@Nullable EncodingOptions> lastEncodingOptions = new AtomicReference<>();

        /// Records an encoder construction.
        private void recordEncoder(EncodingOptions options) {
            lastEncodingOptions.set(Objects.requireNonNull(options, "options"));
            encoderCreations.incrementAndGet();
        }

        /// Records a decoder construction.
        private void recordDecoder() {
            decoderCreations.incrementAndGet();
        }

        /// Returns the encoder-construction count.
        private int encoderCreations() {
            return encoderCreations.get();
        }

        /// Returns the decoder-construction count.
        private int decoderCreations() {
            return decoderCreations.get();
        }

        /// Returns the most recently supplied encoding options.
        private EncodingOptions lastEncodingOptions() {
            return Objects.requireNonNull(lastEncodingOptions.get(), "No encoder options were recorded");
        }
    }

    /// Implements shared immutable configuration for byte-copying test codecs.
    ///
    /// @param <C> the concrete test codec type
    @NotNullByDefault
    private abstract static class CopyingCodec<C extends CompressionCodec<C>>
            implements CompressionCodec<C>, CompressionFormat {
        /// Shared construction recorder.
        private final FactoryState state;

        /// Stable format name.
        private final String name;

        /// Maximum decoded-output size.
        private final long maximumOutputSize;

        /// Maximum decoder history-window size.
        private final long maximumWindowSize;

        /// Maximum decoder working-memory size.
        private final long maximumMemorySize;

        /// Creates a test codec with explicit decoder limits.
        private CopyingCodec(
                FactoryState state,
                String name,
                long maximumOutputSize,
                long maximumWindowSize,
                long maximumMemorySize
        ) {
            this.state = Objects.requireNonNull(state, "state");
            this.name = Objects.requireNonNull(name, "name");
            this.maximumOutputSize = maximumOutputSize;
            this.maximumWindowSize = maximumWindowSize;
            this.maximumMemorySize = maximumMemorySize;
        }

        /// Recreates the concrete codec with the requested decoder limits.
        protected abstract C recreate(long maximumOutputSize, long maximumWindowSize, long maximumMemorySize);

        /// Records one encoder construction.
        protected final void recordEncoder(EncodingOptions options) {
            state.recordEncoder(options);
        }

        /// Records one decoder construction.
        protected final void recordDecoder() {
            state.recordDecoder();
        }

        /// Returns the configured decoded-output limit.
        @Override
        public final long maximumOutputSize() {
            return maximumOutputSize;
        }

        /// Returns the configured history-window limit.
        @Override
        public final long maximumWindowSize() {
            return maximumWindowSize;
        }

        /// Returns the configured decoder-memory limit.
        @Override
        public final long maximumMemorySize() {
            return maximumMemorySize;
        }

        /// Returns a codec with the requested decoded-output limit.
        @Override
        public final C withMaximumOutputSize(long value) {
            CompressionDecoderSupport.validateLimit(value, "maximumOutputSize");
            return recreate(value, maximumWindowSize, maximumMemorySize);
        }

        /// Returns a codec with the requested history-window limit.
        @Override
        public final C withMaximumWindowSize(long value) {
            CompressionDecoderSupport.validateLimit(value, "maximumWindowSize");
            return recreate(maximumOutputSize, value, maximumMemorySize);
        }

        /// Returns a codec with the requested decoder-memory limit.
        @Override
        public final C withMaximumMemorySize(long value) {
            CompressionDecoderSupport.validateLimit(value, "maximumMemorySize");
            return recreate(maximumOutputSize, maximumWindowSize, value);
        }

        /// Returns the stable test format name.
        @Override
        public final String name() {
            return name;
        }

        /// Returns this object as its format identity.
        @Override
        public final CompressionFormat format() {
            return this;
        }

        /// Returns this object as its canonical codec.
        @Override
        public final CompressionCodec<?> defaultCodec() {
            return this;
        }

        /// Creates a copying encoder and records its options.
        @Override
        public CompressionEncoder newEncoder(EncodingOptions options) {
            recordEncoder(options);
            return new CopyEncoder();
        }

        /// Creates a copying decoder and records its construction.
        @Override
        public CompressionDecoder newDecoder() {
            recordDecoder();
            return new CopyDecoder();
        }
    }

    /// Implements the base compression codec without optional engine capabilities.
    @NotNullByDefault
    private static final class PlainCodec extends CopyingCodec<PlainCodec> {
        /// Shared construction recorder.
        private final FactoryState state;

        /// Creates an unrestricted test codec.
        private PlainCodec(FactoryState state) {
            this(state, UNLIMITED_SIZE, UNLIMITED_SIZE, UNLIMITED_SIZE);
        }

        /// Creates a test codec with explicit decoder limits.
        private PlainCodec(
                FactoryState state,
                long maximumOutputSize,
                long maximumWindowSize,
                long maximumMemorySize
        ) {
            super(state, "plain", maximumOutputSize, maximumWindowSize, maximumMemorySize);
            this.state = Objects.requireNonNull(state, "state");
        }

        /// Recreates this codec with the requested decoder limits.
        @Override
        protected PlainCodec recreate(long maximumOutputSize, long maximumWindowSize, long maximumMemorySize) {
            return new PlainCodec(state, maximumOutputSize, maximumWindowSize, maximumMemorySize);
        }
    }

    /// Implements a codec exposing only nonterminal flushing.
    @NotNullByDefault
    private static final class FlushCodec extends CopyingCodec<FlushCodec>
            implements CompressionCodec.Flushable<FlushCodec> {
        /// Shared construction recorder.
        private final FactoryState state;

        /// Creates an unrestricted test codec.
        private FlushCodec(FactoryState state) {
            this(state, UNLIMITED_SIZE, UNLIMITED_SIZE, UNLIMITED_SIZE);
        }

        /// Creates a test codec with explicit decoder limits.
        private FlushCodec(
                FactoryState state,
                long maximumOutputSize,
                long maximumWindowSize,
                long maximumMemorySize
        ) {
            super(state, "flush", maximumOutputSize, maximumWindowSize, maximumMemorySize);
            this.state = Objects.requireNonNull(state, "state");
        }

        /// Recreates this codec with the requested decoder limits.
        @Override
        protected FlushCodec recreate(long maximumOutputSize, long maximumWindowSize, long maximumMemorySize) {
            return new FlushCodec(state, maximumOutputSize, maximumWindowSize, maximumMemorySize);
        }

        /// Creates a flush-capable copying encoder.
        @Override
        public CompressionEncoder.Flushable newEncoder(EncodingOptions options) {
            recordEncoder(options);
            return new FlushEncoder();
        }
    }

    /// Implements a codec exposing only independent frame boundaries.
    @NotNullByDefault
    private static class FrameCodec extends CopyingCodec<FrameCodec>
            implements CompressionCodec.Framed<FrameCodec> {
        /// Shared construction recorder.
        private final FactoryState state;

        /// Creates an unrestricted test codec.
        private FrameCodec(FactoryState state) {
            this(state, UNLIMITED_SIZE, UNLIMITED_SIZE, UNLIMITED_SIZE);
        }

        /// Creates a test codec with explicit decoder limits.
        private FrameCodec(
                FactoryState state,
                long maximumOutputSize,
                long maximumWindowSize,
                long maximumMemorySize
        ) {
            super(state, "frame", maximumOutputSize, maximumWindowSize, maximumMemorySize);
            this.state = Objects.requireNonNull(state, "state");
        }

        /// Recreates this codec with the requested decoder limits.
        @Override
        protected FrameCodec recreate(long maximumOutputSize, long maximumWindowSize, long maximumMemorySize) {
            return new FrameCodec(state, maximumOutputSize, maximumWindowSize, maximumMemorySize);
        }

        /// Creates a frame-capable copying encoder.
        @Override
        public CompressionEncoder.Framed newEncoder(EncodingOptions options) {
            recordEncoder(options);
            return new FrameEncoder();
        }

        /// Creates a frame-capable copying decoder.
        @Override
        public CompressionDecoder.Framed newDecoder() {
            recordDecoder();
            return new FrameDecoder();
        }
    }

    /// Implements a codec exposing both flushing and independent frame boundaries.
    @NotNullByDefault
    private static final class CombinedCodec extends CopyingCodec<CombinedCodec>
            implements CompressionCodec.FlushableFramed<CombinedCodec> {
        /// Shared construction recorder.
        private final FactoryState state;

        /// Creates an unrestricted test codec.
        private CombinedCodec(FactoryState state) {
            this(state, UNLIMITED_SIZE, UNLIMITED_SIZE, UNLIMITED_SIZE);
        }

        /// Creates a test codec with explicit decoder limits.
        private CombinedCodec(
                FactoryState state,
                long maximumOutputSize,
                long maximumWindowSize,
                long maximumMemorySize
        ) {
            super(state, "combined", maximumOutputSize, maximumWindowSize, maximumMemorySize);
            this.state = Objects.requireNonNull(state, "state");
        }

        /// Recreates this codec with the requested decoder limits.
        @Override
        protected CombinedCodec recreate(long maximumOutputSize, long maximumWindowSize, long maximumMemorySize) {
            return new CombinedCodec(state, maximumOutputSize, maximumWindowSize, maximumMemorySize);
        }

        /// Creates a flush- and frame-capable copying encoder.
        @Override
        public CompressionEncoder.FlushableFramed newEncoder(EncodingOptions options) {
            recordEncoder(options);
            return new CombinedEncoder();
        }

        /// Creates a frame-capable copying decoder.
        @Override
        public CompressionDecoder.Framed newDecoder() {
            recordDecoder();
            return new FrameDecoder();
        }
    }

    /// Records seekable factory arguments.
    @NotNullByDefault
    private static final class SeekableFactoryState {
        /// Most recently supplied seekable options.
        private final AtomicReference<@Nullable SeekableEncodingOptions> options = new AtomicReference<>();

        /// Most recently supplied ownership policy.
        private final AtomicReference<@Nullable ResourceOwnership> ownership = new AtomicReference<>();

        /// Records one seekable factory invocation.
        private void record(SeekableEncodingOptions value, ResourceOwnership ownershipValue) {
            options.set(Objects.requireNonNull(value, "value"));
            ownership.set(Objects.requireNonNull(ownershipValue, "ownershipValue"));
        }

        /// Returns the most recently supplied seekable options.
        private SeekableEncodingOptions options() {
            return Objects.requireNonNull(options.get(), "No seekable options were recorded");
        }

        /// Returns the most recently supplied ownership policy.
        private ResourceOwnership ownership() {
            return Objects.requireNonNull(ownership.get(), "No ownership policy was recorded");
        }
    }

    /// Implements a seekable codec whose indexed writer delegates to the ordinary framed adapter.
    @NotNullByDefault
    private static final class SeekableCodec extends CopyingCodec<SeekableCodec>
            implements CompressionCodec.Seekable<SeekableCodec> {
        /// Shared construction recorder.
        private final FactoryState factoryState;

        /// Shared seekable-factory recorder.
        private final SeekableFactoryState seekableState;

        /// Creates an unrestricted seekable test codec.
        private SeekableCodec(FactoryState factoryState, SeekableFactoryState seekableState) {
            this(
                    factoryState,
                    seekableState,
                    UNLIMITED_SIZE,
                    UNLIMITED_SIZE,
                    UNLIMITED_SIZE
            );
        }

        /// Creates a seekable test codec with explicit decoder limits.
        private SeekableCodec(
                FactoryState factoryState,
                SeekableFactoryState seekableState,
                long maximumOutputSize,
                long maximumWindowSize,
                long maximumMemorySize
        ) {
            super(factoryState, "seekable", maximumOutputSize, maximumWindowSize, maximumMemorySize);
            this.factoryState = Objects.requireNonNull(factoryState, "factoryState");
            this.seekableState = Objects.requireNonNull(seekableState, "seekableState");
        }

        /// Recreates this codec with the requested decoder limits.
        @Override
        protected SeekableCodec recreate(long maximumOutputSize, long maximumWindowSize, long maximumMemorySize) {
            return new SeekableCodec(
                    factoryState,
                    seekableState,
                    maximumOutputSize,
                    maximumWindowSize,
                    maximumMemorySize
            );
        }

        /// Creates a frame-capable copying encoder.
        @Override
        public CompressionEncoder.Framed newEncoder(EncodingOptions options) {
            recordEncoder(options);
            return new FrameEncoder();
        }

        /// Creates a frame-capable copying decoder.
        @Override
        public CompressionDecoder.Framed newDecoder() {
            recordDecoder();
            return new FrameDecoder();
        }

        /// Records seekable options and creates an ordinary framed test channel.
        @Override
        public CompressingWritableByteChannel.Framed newSeekableWritableByteChannel(
                WritableByteChannel target,
                SeekableEncodingOptions options,
                ResourceOwnership ownership
        ) throws IOException {
            Objects.requireNonNull(target, "target");
            seekableState.record(options, ownership);
            return newWritableByteChannel(
                    target,
                    EncodingOptions.ofSourceSize(options.sourceSize()),
                    ownership
            );
        }

        /// Reports no synthetic terminal index.
        @Override
        public @Nullable Index readIndex(SeekableByteChannel source) {
            Objects.requireNonNull(source, "source");
            return null;
        }
    }

    /// Implements an empty index while recording readable-channel factory arguments.
    @NotNullByDefault
    private static final class TestIndex implements CompressionCodec.Seekable.Index {
        /// Most recently supplied source channel.
        private @Nullable SeekableByteChannel source;

        /// Most recently supplied ownership policy.
        private @Nullable ResourceOwnership ownership;

        /// Returns zero because the synthetic index covers no bytes.
        @Override
        public long compressedSize() {
            return 0L;
        }

        /// Returns zero because the synthetic index describes no decoded bytes.
        @Override
        public long uncompressedSize() {
            return 0L;
        }

        /// Returns zero because the synthetic index has no frames.
        @Override
        public int frameCount() {
            return 0;
        }

        /// Rejects every frame index because the synthetic index is empty.
        @Override
        public long frameCompressedOffset(int frameIndex) {
            throw new IndexOutOfBoundsException(frameIndex);
        }

        /// Rejects every frame index because the synthetic index is empty.
        @Override
        public long frameCompressedSize(int frameIndex) {
            throw new IndexOutOfBoundsException(frameIndex);
        }

        /// Rejects every frame index because the synthetic index is empty.
        @Override
        public long frameUncompressedOffset(int frameIndex) {
            throw new IndexOutOfBoundsException(frameIndex);
        }

        /// Rejects every frame index because the synthetic index is empty.
        @Override
        public long frameUncompressedSize(int frameIndex) {
            throw new IndexOutOfBoundsException(frameIndex);
        }

        /// Records the explicit source and ownership policy and returns the source unchanged.
        @Override
        public SeekableByteChannel newReadableByteChannel(
                SeekableByteChannel source,
                ResourceOwnership ownership
        ) {
            this.source = Objects.requireNonNull(source, "source");
            this.ownership = Objects.requireNonNull(ownership, "ownership");
            return source;
        }

        /// Returns the most recently supplied source channel.
        private SeekableByteChannel source() {
            return Objects.requireNonNull(source, "No source channel was recorded");
        }

        /// Returns the most recently supplied ownership policy.
        private ResourceOwnership ownership() {
            return Objects.requireNonNull(ownership, "No ownership policy was recorded");
        }
    }

    /// Copies uncompressed bytes directly to encoded output.
    @NotNullByDefault
    private static class CopyEncoder implements CompressionEncoder {
        /// Copies as many bytes as the target can accept.
        @Override
        public CodecOutcome encode(ByteBuffer source, ByteBuffer target) {
            copy(source, target);
            return source.hasRemaining() ? CodecOutcome.NEEDS_OUTPUT : CodecOutcome.NEEDS_INPUT;
        }

        /// Completes encoding without a trailer.
        @Override
        public CodecOutcome finish(ByteBuffer target) {
            return CodecOutcome.FINISHED;
        }

        /// Restores no mutable state.
        @Override
        public void reset() {
        }

        /// Releases no external resources.
        @Override
        public void close() {
        }
    }

    /// Adds a no-output flush boundary to the copying encoder.
    @NotNullByDefault
    private static final class FlushEncoder extends CopyEncoder implements CompressionEncoder.Flushable {
        /// Completes a flush without producing output.
        @Override
        public CodecOutcome flush(ByteBuffer target) {
            Objects.requireNonNull(target, "target");
            return CodecOutcome.FLUSHED;
        }
    }

    /// Adds explicit frame boundaries to the copying encoder.
    @NotNullByDefault
    private static class FrameEncoder extends CopyEncoder implements CompressionEncoder.Framed {
        /// Options supplied to the most recently started frame.
        private @Nullable EncodingOptions lastFrameOptions;

        /// Records the options for an explicitly started frame.
        @Override
        public void startFrame(EncodingOptions options) {
            lastFrameOptions = Objects.requireNonNull(options, "options");
        }

        /// Completes a frame without producing output.
        @Override
        public CodecOutcome finishFrame(ByteBuffer target) {
            Objects.requireNonNull(target, "target");
            return CodecOutcome.BOUNDARY_REACHED;
        }

        /// Returns the most recently supplied frame options.
        private EncodingOptions lastFrameOptions() {
            return Objects.requireNonNull(lastFrameOptions, "No frame options were recorded");
        }
    }

    /// Combines no-output flushes with explicit frame boundaries.
    @NotNullByDefault
    private static final class CombinedEncoder extends FrameEncoder implements CompressionEncoder.FlushableFramed {
        /// Completes a flush without producing output.
        @Override
        public CodecOutcome flush(ByteBuffer target) {
            Objects.requireNonNull(target, "target");
            return CodecOutcome.FLUSHED;
        }
    }

    /// Copies encoded bytes directly to decoded output.
    @NotNullByDefault
    private static class CopyDecoder implements CompressionDecoder {
        /// Copies available bytes while permitting more input.
        @Override
        public CodecOutcome decode(ByteBuffer source, ByteBuffer target) {
            copy(source, target);
            return source.hasRemaining() ? CodecOutcome.NEEDS_OUTPUT : CodecOutcome.NEEDS_INPUT;
        }

        /// Copies the final bytes and reports completion when exhausted.
        @Override
        public CodecOutcome finish(ByteBuffer source, ByteBuffer target) {
            copy(source, target);
            return source.hasRemaining() ? CodecOutcome.NEEDS_OUTPUT : CodecOutcome.FINISHED;
        }

        /// Restores no mutable state.
        @Override
        public void reset() {
        }

        /// Releases no external resources.
        @Override
        public void close() {
        }
    }

    /// Marks the byte-copying decoder as capable of independent frame decoding.
    @NotNullByDefault
    private static final class FrameDecoder extends CopyDecoder implements CompressionDecoder.Framed {
    }

    /// Copies the shared prefix of source and target remaining ranges.
    private static void copy(ByteBuffer source, ByteBuffer target) {
        int count = Math.min(source.remaining(), target.remaining());
        ByteBuffer chunk = source.slice();
        chunk.limit(count);
        target.put(chunk);
        source.position(source.position() + count);
    }

    /// Implements an empty in-memory seekable channel for default-method delegation tests.
    @NotNullByDefault
    private static final class EmptySeekableByteChannel implements SeekableByteChannel {
        /// Current logical position.
        private long position;

        /// Whether the channel remains open.
        private boolean open = true;

        /// Reports end-of-input for every read while open.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            requireOpen();
            return -1;
        }

        /// Rejects writes because this test channel represents an immutable empty source.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            requireOpen();
            throw new UnsupportedOperationException("read-only test channel");
        }

        /// Returns the current logical position.
        @Override
        public long position() throws IOException {
            requireOpen();
            return position;
        }

        /// Sets the nonnegative logical position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            requireOpen();
            if (newPosition < 0L) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            position = newPosition;
            return this;
        }

        /// Returns zero because this test channel has no content.
        @Override
        public long size() throws IOException {
            requireOpen();
            return 0L;
        }

        /// Accepts a nonnegative truncation size without changing the empty content.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            requireOpen();
            if (size < 0L) {
                throw new IllegalArgumentException("size must not be negative");
            }
            return this;
        }

        /// Returns whether the channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes the channel.
        @Override
        public void close() {
            open = false;
        }

        /// Rejects operations after closure.
        private void requireOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Records close calls while retaining written bytes for assertions.
    @NotNullByDefault
    private static final class TrackingOutputStream extends ByteArrayOutputStream {
        /// Number of close calls.
        private int closeCalls;

        /// Records one close call.
        @Override
        public void close() throws IOException {
            closeCalls++;
            super.close();
        }

        /// Returns the close-call count.
        private int closeCalls() {
            return closeCalls;
        }
    }

    /// Records close calls while serving a fixed byte array.
    @NotNullByDefault
    private static final class TrackingInputStream extends ByteArrayInputStream {
        /// Number of close calls.
        private int closeCalls;

        /// Creates a stream over the given bytes.
        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        /// Records one close call.
        @Override
        public void close() throws IOException {
            closeCalls++;
            super.close();
        }

        /// Returns the close-call count.
        private int closeCalls() {
            return closeCalls;
        }
    }
}
