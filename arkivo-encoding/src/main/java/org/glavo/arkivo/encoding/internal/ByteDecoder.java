// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

/// Bridges incremental JDK decoding to character-at-a-time scoring.
@NotNullByDefault
final class ByteDecoder {
    /// Strict decoder for the candidate charset.
    private final CharsetDecoder decoder;
    /// Unconsumed prefix of a character or escape sequence.
    private final ByteBuffer input = ByteBuffer.allocate(16);
    /// UTF-16 output of a completed character.
    private final CharBuffer output = CharBuffer.allocate(4);
    /// Whether malformed or unmappable input has been observed.
    private boolean failed;

    /// Creates a strict decoder with no caller-owned storage.
    ByteDecoder(Charset charset) {
        decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    /// Decodes one byte; returns the UTF-16 length, or -1 on rejection.
    int accept(int value) {
        if (failed || !input.hasRemaining()) {
            failed = true;
            return -1;
        }
        input.put((byte) value).flip();
        output.clear();
        var result = decoder.decode(input, output, false);
        input.compact();
        if (result.isError() || result.isOverflow()) {
            failed = true;
            return -1;
        }
        return output.position();
    }

    /// Returns the first UTF-16 code unit emitted by the last successful call.
    char first() {
        return output.get(0);
    }

    /// Rejects incomplete characters and completes the JDK decoder.
    boolean finish() {
        if (failed) {
            return false;
        }
        input.flip();
        output.clear();
        var result = decoder.decode(input, output, true);
        if (!result.isUnderflow() || input.hasRemaining()) {
            failed = true;
            return false;
        }
        result = decoder.flush(output);
        failed = !result.isUnderflow();
        return !failed;
    }
}
