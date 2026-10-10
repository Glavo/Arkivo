// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.util.Arrays;

import static org.glavo.arkivo.encoding.detector.internal.Weights.IMPLAUSIBILITY_PENALTY;

/// Maps bytes to character classes and scores adjacent classes.
///
/// @param charsetName   JDK charset name
/// @param lower         classes for ASCII bytes
/// @param upper         classes for bytes with the high bit set
/// @param probabilities compressed pair score matrix
/// @param ascii         number of stored ASCII classes
/// @param nonAscii      number of stored non-ASCII classes
/// @param decodable     two bit masks for high bytes accepted by the JDK decoder
@NotNullByDefault
record SingleByteData(String charsetName, byte @Unmodifiable [] lower, byte @Unmodifiable [] upper,
                      byte @Unmodifiable [] probabilities, int ascii, int nonAscii,
                      long @Unmodifiable [] decodable) {
    /// Creates a shared model restricted to bytes supported by the installed charset.
    SingleByteData(String charsetName, byte @Unmodifiable [] lower, byte @Unmodifiable [] upper,
                   byte @Unmodifiable [] probabilities, int ascii, int nonAscii) {
        this(charsetName, lower, upper, probabilities, ascii, nonAscii, decodableBytes(charsetName));
    }

    /// Computes JDK validity independently of the upstream WHATWG character classes.
    private static long[] decodableBytes(String name) {
        var result = new long[2];
        if (!Charset.isSupported(name)) {
            return result;
        }
        var decoder = Charset.forName(name).newDecoder();
        var input = ByteBuffer.allocate(1);
        var output = CharBuffer.allocate(2);
        for (int value = 128; value < 256; value++) {
            decoder.reset();
            input.clear().put((byte) value).flip();
            output.clear();
            var status = decoder.decode(input, output, true);
            if (status.isUnderflow() && !input.hasRemaining() && decoder.flush(output).isUnderflow()) {
                result[(value - 128) >>> 6] |= 1L << (value & 63);
            }
        }
        return result;
    }

    /// Reuses this model's classes and pair weights for another ASCII-compatible single-byte charset.
    /// Characters absent from this model receive the neutral punctuation class, not a language score.
    /// Unavailable charsets remain excluded by their empty validity masks.
    SingleByteData remap(String name) {
        byte[] mapped = new byte[128];
        Arrays.fill(mapped, (byte) (ascii + nonAscii));
        if (Charset.isSupported(name) && Charset.isSupported(charsetName)) {
            var source = Charset.forName(charsetName).newDecoder();
            var target = Charset.forName(name).newDecoder();
            var classes = new java.util.HashMap<Character, Byte>();
            for (int value = 0; value < 256; value++) {
                try {
                    var decoded = source.decode(ByteBuffer.wrap(new byte[]{(byte) value}));
                    if (decoded.remaining() == 1 && classify(value) != 255) {
                        classes.put(decoded.get(), (byte) classify(value));
                    }
                } catch (CharacterCodingException ignored) {
                    // Undefined source bytes have no character class to transfer.
                }
            }
            for (int value = 128; value < 256; value++) {
                try {
                    var decoded = target.decode(ByteBuffer.wrap(new byte[]{(byte) value}));
                    if (decoded.remaining() == 1) {
                        mapped[value - 128] = classes.getOrDefault(decoded.get(), (byte) (ascii + nonAscii));
                    }
                } catch (CharacterCodingException ignored) {
                    // The validity mask rejects undefined bytes independently of the language model.
                }
            }
        }
        return new SingleByteData(name, lower, mapped, probabilities, ascii, nonAscii);
    }

    /// Classifies an unsigned byte.
    int classify(int value) {
        if (value >= 128 && (decodable[(value - 128) >>> 6] & (1L << (value & 63))) == 0) {
            return 255;
        }
        return (value < 128 ? lower[value] : upper[value & 127]) & 255;
    }

    /// Tests whether a class is a stored Latin letter class.
    boolean isLatinAlphabetic(int value) {
        return value > 0 && value < ascii + nonAscii;
    }

    /// Tests whether a class represents a non-Latin letter.
    boolean isNonLatinAlphabetic(int value, boolean windows1256) {
        return value > (windows1256 ? 2 : 1) && value < ascii + nonAscii;
    }

    /// Scores a pair, including the unstored punctuation classes.
    long score(int current, int previous, boolean windows1256) {
        int boundary = ascii + nonAscii;
        if (current < boundary && previous < boundary) {
            if (current < ascii && previous < ascii) {
                return 0;
            }
            int index = current >= ascii
                    ? ascii * nonAscii + boundary * (current - ascii) + previous
                    : current * nonAscii + previous - ascii;
            int value = probabilities[index] & 255;
            return value == 255 ? IMPLAUSIBILITY_PENALTY : value;
        }
        if (current < boundary) {
            if (current == 0 || (windows1256 && current == 2)) {
                return 0;
            }
            return punctuationScore(previous - boundary, current < ascii, true);
        }
        if (previous < boundary) {
            if (previous == 0 || (windows1256 && previous == 2)) {
                return 0;
            }
            return punctuationScore(current - boundary, previous < ascii, false);
        }
        return current == 100 || previous == 100 ? 0 : IMPLAUSIBILITY_PENALTY;
    }

    /// Scores an unstored class before or after a letter.
    private static long punctuationScore(int value, boolean asciiLetter, boolean before) {
        return switch (value) {
            case 0 -> 0;
            case 1 -> IMPLAUSIBILITY_PENALTY;
            case 2 -> before ? IMPLAUSIBILITY_PENALTY : 0;
            case 3 -> before ? 0 : IMPLAUSIBILITY_PENALTY;
            case 4 -> asciiLetter ? IMPLAUSIBILITY_PENALTY : 0;
            case 5 -> asciiLetter ? 0 : IMPLAUSIBILITY_PENALTY;
            default -> 0; // ASCII digits are not stored in the matrix.
        };
    }
}
