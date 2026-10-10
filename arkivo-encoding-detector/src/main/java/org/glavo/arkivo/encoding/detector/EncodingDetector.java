// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector;

import org.glavo.arkivo.encoding.detector.internal.DetectionEngine;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// Guesses the character encoding of one continuous byte sequence.
///
/// Input is supplied by [#feed(ByteBuffer, boolean)] or [#feed(byte[], boolean)].
/// Chunk boundaries do not delimit fields or reset decoding state. A detector for several independent
/// names must not be fed their concatenation; each name requires a separate detector or a call to [#reset()].
/// Input storage is never retained.
///
/// [#guess()] may be called before, during, or after input. A guess is heuristic, not a validation result
/// or a guarantee that every byte can be decoded with the returned charset. Explicit encoding declarations
/// should take precedence over detection. Short or ambiguous sequences can be misidentified.
///
/// Instances are mutable and are not safe for concurrent use. Independent instances share immutable
/// scoring tables but no input state. ASCII input without enabled escape-based detection
/// does not load those tables.
///
/// @implNote The statistical rules and tables are adapted from chardetng 1.0.0, without browser
/// domain preferences. Multibyte validation uses installed JDK charsets, not the WHATWG decoding
/// tables. Unavailable optional charsets are excluded. DOS encodings other than IBM866 are not
/// candidates. Windows-1252 must be available for [#guess(boolean)] when UTF-8 is not selected.
@NotNullByDefault
public final class EncodingDetector {
    /// Indicates that no ASCII context byte has been retained.
    private static final int NO_BYTE = -1;
    /// Whether ISO-2022-JP escape sequences participate in detection.
    private final boolean allowIso2022Jp;
    /// Lazily allocated non-ASCII scoring state.
    private @Nullable DetectionEngine engine;
    /// Whether end of input has been supplied.
    private boolean finished;
    /// Whether the stream has contained a non-ASCII byte.
    private boolean nonAscii;
    /// Whether the stream has contained an escape byte.
    private boolean escape;
    /// Older ASCII byte retained before scoring starts, or -1.
    private int beforePrevious = NO_BYTE;
    /// Most recent ASCII byte retained before scoring starts, or -1.
    private int previous = NO_BYTE;

    /// Creates a detector with ISO-2022-JP detection disabled.
    public EncodingDetector() {
        this(false);
    }

    /// Creates a detector with optional escape-based Japanese detection.
    ///
    /// @param allowIso2022Jp whether ISO-2022-JP is a candidate
    public EncodingDetector(boolean allowIso2022Jp) {
        this.allowIso2022Jp = allowIso2022Jp;
    }

    /// Guesses the encoding of an entire byte array, allowing UTF-8.
    ///
    /// The array is not modified or retained. ISO-2022-JP detection is disabled.
    ///
    /// @param input complete input
    /// @return the guessed charset; UTF-8 for empty or ASCII-only input
    /// @throws NullPointerException if input is null
    public static Charset detect(byte[] input) {
        var detector = new EncodingDetector();
        detector.feed(input, true);
        return detector.guess();
    }

    /// Guesses the encoding of the remaining bytes without changing buffer state.
    ///
    /// The buffer is not modified or retained. ISO-2022-JP detection is disabled.
    ///
    /// @param input complete input between position and limit
    /// @return the guessed charset; UTF-8 for empty or ASCII-only input
    /// @throws NullPointerException if input is null
    public static Charset detect(ByteBuffer input) {
        var detector = new EncodingDetector();
        detector.feed(input.duplicate(), true);
        return detector.guess();
    }

    /// Supplies a complete array as the next chunk.
    ///
    /// The array is not modified or retained. The end-of-input and return-value semantics
    /// are the same as [#feed(ByteBuffer, boolean)].
    ///
    /// @param input      next input chunk
    /// @param endOfInput whether this is the final chunk
    /// @return whether any non-ASCII byte has been seen in this sequence
    /// @throws NullPointerException  if input is null
    /// @throws IllegalStateException if end of input was already supplied
    public boolean feed(byte[] input, boolean endOfInput) {
        Objects.requireNonNull(input, "input");
        ensureAccepting();
        for (byte value : input) {
            accept(value & 255);
        }
        finishIfRequested(endOfInput);
        return nonAscii;
    }

    /// Consumes all remaining bytes as the next chunk.
    ///
    /// On normal return the position equals the limit; the limit and content are unchanged.
    /// Heap, direct, and read-only buffers are accepted. Empty chunks are permitted.
    /// This method retains neither the buffer nor a view of it.
    ///
    /// If endOfInput is true, incomplete characters are rejected and no more input may be
    /// supplied until [#reset()] is called. To inspect only a prefix of an unfinished stream,
    /// pass false and call [#guess()] without finishing the detector.
    ///
    /// @param input      next input chunk
    /// @param endOfInput whether the byte sequence ends after this chunk
    /// @return whether any non-ASCII byte has been seen; this is not a confidence indicator
    /// @throws NullPointerException  if input is null
    /// @throws IllegalStateException if end of input was already supplied, without consuming input
    public boolean feed(ByteBuffer input, boolean endOfInput) {
        Objects.requireNonNull(input, "input");
        ensureAccepting();
        while (input.hasRemaining()) {
            accept(input.get() & 255);
        }
        finishIfRequested(endOfInput);
        return nonAscii;
    }

    /// Returns the current guess, allowing UTF-8.
    ///
    /// This method is equivalent to calling [#guess(boolean)] with true.
    ///
    /// @return the guessed charset
    public Charset guess() {
        return guess(true);
    }

    /// Returns a guess without changing detector state.
    ///
    /// If allowed and still valid, UTF-8 takes precedence over statistical candidates.
    /// An enabled, valid ISO-2022-JP candidate takes precedence for ASCII-only input containing
    /// an escape byte. Otherwise the highest positive eligible score wins; Windows-1252 is
    /// the fallback when no candidate has a positive score. The fallback is not a validity guarantee.
    ///
    /// A guess made before end of input may change when more input arrives or when an incomplete
    /// character is rejected at end of input. Repeated calls with the same argument and no intervening
    /// feed or reset return the same charset.
    ///
    /// @param allowUtf8 whether UTF-8 may be returned
    /// @return the guessed charset; UTF-8 for empty input if allowed, otherwise Windows-1252
    /// @throws java.nio.charset.UnsupportedCharsetException if Windows-1252 is needed but unavailable
    public Charset guess(boolean allowUtf8) {
        return engine == null
                ? allowUtf8 ? StandardCharsets.UTF_8 : Charset.forName("windows-1252")
                : engine.guess(allowUtf8, nonAscii, escape);
    }

    /// Discards all input state and starts an independent byte sequence.
    ///
    /// ISO-2022-JP configuration is preserved. This method may be called before or after end of input.
    public void reset() {
        engine = null;
        finished = false;
        nonAscii = false;
        escape = false;
        beforePrevious = NO_BYTE;
        previous = NO_BYTE;
    }

    /// Rejects input after the final chunk.
    private void ensureAccepting() {
        if (finished) {
            throw new IllegalStateException("End of input has already been supplied");
        }
    }

    /// Retains only two ASCII context bytes until statistical scoring is necessary.
    private void accept(int value) {
        nonAscii |= value >= 128;
        escape |= value == 27;
        if (engine == null) {
            if (value < 128 && !(allowIso2022Jp && value == 27)) {
                beforePrevious = previous;
                previous = value;
                return;
            }
            engine = new DetectionEngine(allowIso2022Jp);
            if (beforePrevious >= 0) {
                engine.accept(beforePrevious);
            }
            if (previous >= 0) {
                engine.accept(previous);
            }
        }
        engine.accept(value);
    }

    /// Completes validation exactly once at the end of a sequence.
    private void finishIfRequested(boolean endOfInput) {
        if (endOfInput) {
            if (engine != null) {
                engine.finish();
            }
            finished = true;
        }
    }
}
