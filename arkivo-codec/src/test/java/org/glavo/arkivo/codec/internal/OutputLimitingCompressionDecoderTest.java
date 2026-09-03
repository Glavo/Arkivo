// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.DictionaryRequest;
import org.glavo.arkivo.codec.RawCompressionDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies output limiting around buffer decoders with internally staged output.
@NotNullByDefault
final class OutputLimitingCompressionDecoderTest {
    /// Dictionary request exposed by the synthetic dictionary-aware decoders.
    private static final DictionaryRequest<RawCompressionDictionary> DICTIONARY_REQUEST =
            dictionary -> dictionary.size() > 0;

    /// Verifies an exact limit finishes immediately when the caller's target still has unused space.
    @Test
    void acceptsExactLimitWhenDelegateRequestsFinalOutputCall() throws Exception {
        StagedDecoder delegate = new StagedDecoder(new byte[]{1, 2, 3});
        CompressionDecoder decoder = CompressionDecoderSupport.limitEngineOutput(
                delegate,
                3L
        );
        ByteBuffer target = ByteBuffer.allocate(8);
        target.limit(7);

        assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.allocate(0), target));
        assertEquals(3, target.position());
        assertEquals(7, target.limit());

        decoder.reset();
        target.clear();
        target.limit(7);
        assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.allocate(0), target));
        assertEquals(3, target.position());

        decoder.close();
        assertTrue(delegate.closed());
    }

    /// Verifies the hidden probe rejects the first byte beyond the limit in the operation that reaches it.
    @Test
    void rejectsStagedExcessOutputAfterPrefix() throws Exception {
        CompressionDecoder decoder = CompressionDecoderSupport.limitEngineOutput(
                new StagedDecoder(new byte[]{1, 2, 3, 4}),
                3L
        );
        ByteBuffer target = ByteBuffer.allocate(8);

        DecompressionLimitException exception = assertThrows(
                DecompressionLimitException.class,
                () -> decoder.finish(ByteBuffer.allocate(0), target)
        );
        assertEquals(3, target.position());
        assertEquals(8, target.limit());
        assertEquals(3L, exception.maximum());
        assertThrows(
                DecompressionLimitException.class,
                () -> decoder.finish(ByteBuffer.allocate(0), target)
        );
    }

    /// Verifies all typed factory overloads retain framed and dictionary capabilities.
    @Test
    void preservesDecoderCapabilitiesAndDictionaryDelegation() throws IOException {
        CompressionDecoder genericFramed = CompressionDecoderSupport.limitEngineOutput(
                (CompressionDecoder) new FramedStagedDecoder(new byte[0]),
                1L
        );
        assertInstanceOf(CompressionDecoder.Framed.class, genericFramed);

        CompressionDecoder genericDictionary = CompressionDecoderSupport.limitEngineOutput(
                (CompressionDecoder) new DictionaryStagedDecoder(new byte[0]),
                1L
        );
        assertInstanceOf(CompressionDecoder.DictionaryAware.class, genericDictionary);
        assertFalse(genericDictionary instanceof CompressionDecoder.Framed);

        CompressionDecoder genericCombined = CompressionDecoderSupport.limitEngineOutput(
                (CompressionDecoder) new FramedDictionaryStagedDecoder(new byte[0]),
                1L
        );
        assertInstanceOf(CompressionDecoder.FramedDictionaryAware.class, genericCombined);

        FramedStagedDecoder framedDelegate = new FramedStagedDecoder(new byte[0]);
        CompressionDecoder.Framed framed = CompressionDecoderSupport.limitEngineOutput(framedDelegate, 1L);
        assertInstanceOf(CompressionDecoder.Framed.class, framed);

        DictionaryStagedDecoder dictionaryDelegate = new DictionaryStagedDecoder(new byte[0]);
        CompressionDecoder.DictionaryAware<RawCompressionDictionary, DictionaryRequest<RawCompressionDictionary>>
                dictionary = CompressionDecoderSupport.limitEngineOutput(dictionaryDelegate, 1L);
        assertSame(DICTIONARY_REQUEST, dictionary.dictionaryRequest());
        RawCompressionDictionary suppliedDictionary = RawCompressionDictionary.of(new byte[]{9});
        dictionary.provideDictionary(suppliedDictionary);
        assertSame(suppliedDictionary, dictionaryDelegate.providedDictionary());

        FramedDictionaryStagedDecoder combinedDelegate = new FramedDictionaryStagedDecoder(new byte[0]);
        CompressionDecoder.Framed framedView = combinedDelegate;
        assertInstanceOf(
                CompressionDecoder.FramedDictionaryAware.class,
                CompressionDecoderSupport.limitEngineOutput(framedView, 1L)
        );
        CompressionDecoder.DictionaryAware<RawCompressionDictionary, DictionaryRequest<RawCompressionDictionary>>
                dictionaryView = combinedDelegate;
        assertInstanceOf(
                CompressionDecoder.FramedDictionaryAware.class,
                CompressionDecoderSupport.limitEngineOutput(dictionaryView, 1L)
        );
        CompressionDecoder.FramedDictionaryAware<
                RawCompressionDictionary,
                DictionaryRequest<RawCompressionDictionary>
                > combined = CompressionDecoderSupport.limitEngineOutput(combinedDelegate, 1L);
        assertInstanceOf(CompressionDecoder.FramedDictionaryAware.class, combined);
    }

    /// Verifies typed dictionary overloads preserve delegate identity when output is unrestricted.
    @Test
    void preservesTypedDictionaryDelegatesWithoutLimit() {
        DictionaryStagedDecoder dictionary = new DictionaryStagedDecoder(new byte[0]);
        assertSame(
                dictionary,
                CompressionDecoderSupport.limitEngineOutput(
                        dictionary,
                        CompressionCodec.UNLIMITED_SIZE
                )
        );

        FramedDictionaryStagedDecoder combined = new FramedDictionaryStagedDecoder(new byte[0]);
        assertSame(
                combined,
                CompressionDecoderSupport.limitEngineOutput(
                        combined,
                        CompressionCodec.UNLIMITED_SIZE
                )
        );
    }

    /// Verifies an empty target bypasses the delegate and ordinary decode uses the same exact-limit probe.
    @Test
    void handlesEmptyTargetsAndIncrementalDecode() throws IOException {
        CompressionDecoder decoder = CompressionDecoderSupport.limitEngineOutput(
                new StagedDecoder(new byte[]{5}),
                1L
        );

        assertEquals(
                CodecOutcome.NEEDS_OUTPUT,
                decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(0))
        );
        ByteBuffer target = ByteBuffer.allocate(4);
        target.limit(3);
        assertEquals(CodecOutcome.FINISHED, decoder.decode(ByteBuffer.allocate(0), target));
        assertEquals(1, target.position());
        assertEquals(3, target.limit());
        assertEquals(5, Byte.toUnsignedInt(target.get(0)));
    }

    /// Verifies a delegate cannot request more probe output without producing the required probe byte.
    @Test
    void rejectsOutputRequestsWithoutProbeProgress() {
        CompressionDecoder decoding = CompressionDecoderSupport.limitEngineOutput(new NoOutputDecoder(), 0L);
        IOException decodeFailure = assertThrows(
                IOException.class,
                () -> decoding.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertEquals(
                "Compression decoder requested output without producing a probe byte",
                decodeFailure.getMessage()
        );

        CompressionDecoder finishing = CompressionDecoderSupport.limitEngineOutput(new NoOutputDecoder(), 0L);
        IOException finishFailure = assertThrows(
                IOException.class,
                () -> finishing.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertEquals(
                "Compression decoder requested output without producing a probe byte",
                finishFailure.getMessage()
        );
    }

    /// Verifies temporary target limits are restored when the delegate fails after partial output.
    @Test
    void restoresTargetLimitAfterDelegateFailure() {
        IOException injected = new IOException("injected decoder failure");
        CompressionDecoder decoder = CompressionDecoderSupport.limitEngineOutput(
                new FailingDecoder(injected),
                1L
        );
        ByteBuffer target = ByteBuffer.allocate(6);
        target.position(1);
        target.limit(4);

        assertSame(
                injected,
                assertThrows(
                        IOException.class,
                        () -> decoder.finish(ByteBuffer.allocate(0), target)
                )
        );
        assertEquals(2, target.position());
        assertEquals(4, target.limit());
        assertEquals(73, Byte.toUnsignedInt(target.get(1)));
    }

    /// Verifies a zero limit accepts a decoder that confirms no output is present.
    @Test
    void acceptsEmptyOutputAtZeroLimit() throws IOException {
        CompressionDecoder decoder = CompressionDecoderSupport.limitEngineOutput(
                new StagedDecoder(new byte[0]),
                0L
        );

        assertEquals(
                CodecOutcome.FINISHED,
                decoder.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
    }

    /// Verifies a target smaller than the remaining allowance can be resumed without an early probe.
    @Test
    void resumesOutputBelowTheConfiguredLimit() throws IOException {
        CompressionDecoder decoder = CompressionDecoderSupport.limitEngineOutput(
                new StagedDecoder(new byte[]{17, 18}),
                3L
        );
        ByteBuffer first = ByteBuffer.allocate(1);

        assertEquals(CodecOutcome.NEEDS_OUTPUT, decoder.finish(ByteBuffer.allocate(0), first));
        assertEquals(1, first.position());
        assertEquals(17, Byte.toUnsignedInt(first.get(0)));

        ByteBuffer second = ByteBuffer.allocate(4);
        assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.allocate(0), second));
        assertEquals(1, second.position());
        assertEquals(18, Byte.toUnsignedInt(second.get(0)));
    }

    /// Supplies fixed output while deliberately requiring an empty final decode call.
    @NotNullByDefault
    private static class StagedDecoder implements CompressionDecoder {
        /// The internally staged decoded bytes.
        private final ByteBuffer content;

        /// Whether the decoder has been closed.
        private boolean closed;

        /// Creates a decoder over fixed staged bytes.
        private StagedDecoder(byte[] content) {
            this.content = ByteBuffer.wrap(Objects.requireNonNull(content, "content"));
        }

        /// Copies staged bytes and requests output whenever the supplied target becomes full.
        @Override
        public CodecOutcome decode(ByteBuffer source, ByteBuffer target) {
            return decodeInternal(source, target, false);
        }

        /// Finishes decoding after all source bytes have been supplied.
        @Override
        public CodecOutcome finish(ByteBuffer source, ByteBuffer target) {
            return decodeInternal(source, target, true);
        }

        /// Implements decoding with the selected source-completion state.
        private CodecOutcome decodeInternal(ByteBuffer source, ByteBuffer target, boolean endOfInput) {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            if (closed) {
                throw new IllegalStateException("Staged decoder is closed");
            }
            if (!content.hasRemaining()) {
                return CodecOutcome.FINISHED;
            }
            int copied = Math.min(content.remaining(), target.remaining());
            ByteBuffer chunk = content.duplicate();
            chunk.limit(chunk.position() + copied);
            target.put(chunk);
            content.position(content.position() + copied);
            return target.hasRemaining() ? CodecOutcome.FINISHED : CodecOutcome.NEEDS_OUTPUT;
        }

        /// Restores all staged bytes.
        @Override
        public void reset() {
            if (closed) {
                throw new IllegalStateException("Staged decoder is closed");
            }
            content.position(0);
        }

        /// Closes this decoder.
        @Override
        public void close() {
            closed = true;
        }

        /// Returns whether this decoder has been closed.
        private boolean closed() {
            return closed;
        }
    }

    /// Adds concatenated-frame capability to a staged decoder.
    @NotNullByDefault
    private static final class FramedStagedDecoder
            extends StagedDecoder
            implements CompressionDecoder.Framed {
        /// Creates a framed decoder over fixed staged bytes.
        private FramedStagedDecoder(byte[] content) {
            super(content);
        }
    }

    /// Adds dictionary binding to a staged decoder and records the supplied dictionary.
    @NotNullByDefault
    private static class DictionaryStagedDecoder
            extends StagedDecoder
            implements CompressionDecoder.DictionaryAware<
                    RawCompressionDictionary,
                    DictionaryRequest<RawCompressionDictionary>
                    > {
        /// The most recently supplied dictionary, or `null` before provision.
        private @Nullable RawCompressionDictionary providedDictionary;

        /// Creates a dictionary-aware decoder over fixed staged bytes.
        private DictionaryStagedDecoder(byte[] content) {
            super(content);
        }

        /// Returns the shared synthetic dictionary request.
        @Override
        public DictionaryRequest<RawCompressionDictionary> dictionaryRequest() {
            return DICTIONARY_REQUEST;
        }

        /// Records the supplied dictionary.
        @Override
        public void provideDictionary(RawCompressionDictionary dictionary) {
            providedDictionary = Objects.requireNonNull(dictionary, "dictionary");
        }

        /// Returns the most recently supplied dictionary, or `null` when none was supplied.
        private @Nullable RawCompressionDictionary providedDictionary() {
            return providedDictionary;
        }
    }

    /// Adds both frame and dictionary capabilities to a staged decoder.
    @NotNullByDefault
    private static final class FramedDictionaryStagedDecoder
            extends DictionaryStagedDecoder
            implements CompressionDecoder.FramedDictionaryAware<
                    RawCompressionDictionary,
                    DictionaryRequest<RawCompressionDictionary>
                    > {
        /// Creates a framed dictionary-aware decoder over fixed staged bytes.
        private FramedDictionaryStagedDecoder(byte[] content) {
            super(content);
        }
    }

    /// Requests output without producing bytes to model an invalid decoder implementation.
    @NotNullByDefault
    private static final class NoOutputDecoder implements CompressionDecoder {
        /// Requests output without consuming input or producing bytes.
        @Override
        public CodecOutcome decode(ByteBuffer source, ByteBuffer target) {
            return CodecOutcome.NEEDS_OUTPUT;
        }

        /// Requests output without consuming input or producing bytes at end of input.
        @Override
        public CodecOutcome finish(ByteBuffer source, ByteBuffer target) {
            return CodecOutcome.NEEDS_OUTPUT;
        }

        /// Restores no state.
        @Override
        public void reset() {
        }

        /// Releases no resources.
        @Override
        public void close() {
        }
    }

    /// Produces one byte before throwing a fixed checked failure.
    @NotNullByDefault
    private static final class FailingDecoder implements CompressionDecoder {
        /// Failure thrown by every decoding operation.
        private final IOException failure;

        /// Creates a decoder that throws the supplied failure.
        private FailingDecoder(IOException failure) {
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /// Produces one byte and fails.
        @Override
        public CodecOutcome decode(ByteBuffer source, ByteBuffer target) throws IOException {
            return fail(target);
        }

        /// Produces one byte and fails at end of input.
        @Override
        public CodecOutcome finish(ByteBuffer source, ByteBuffer target) throws IOException {
            return fail(target);
        }

        /// Produces the synthetic byte and throws the configured failure.
        private CodecOutcome fail(ByteBuffer target) throws IOException {
            target.put((byte) 73);
            throw failure;
        }

        /// Restores no state.
        @Override
        public void reset() {
        }

        /// Releases no resources.
        @Override
        public void close() {
        }
    }
}
