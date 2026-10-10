// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: MIT

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.Arrays;

/// Reuses a strict decoder and bounded feature workspace for complete metadata fields.
@NotNullByDefault
final class MetadataScorer {
    /// Maximum scored scalars in a comment or path component; validation has no such limit.
    private static final int MAX_SCORED_CHARACTERS = 4096;
    /// Sentinel for an absent surrogate or delimiter index.
    private static final int ABSENT = -1;
    /// Fixed slots for exact feature keys; full tables stop admitting new features.
    private final int[] features = new int[512];
    /// Occurrence caps associated with feature keys.
    private final byte[] repetitions = new byte[512];
    /// Encoding evaluated by this workspace.
    final Charset charset;
    /// Decoder reused for validation and output equivalence checks.
    final CharsetDecoder decoder;
    /// Single-byte pair table, or null for multibyte candidates.
    private final @Nullable SingleByteData model;
    /// Character repertoire used for multibyte evidence.
    private final Family family;
    /// Whether the Japanese candidate uses EUC byte sequences rather than Windows-31J.
    private final boolean eucJapanese;
    /// Role of the current field.
    private EncodingDetector.Kind kind = EncodingDetector.Kind.NAME;
    /// Current segment's statistical score.
    private long score;
    /// Current segment's contributing feature count.
    private int evidence;
    /// Current segment's lexical inconsistencies.
    private int suspicious;
    /// Current segment's admitted scalar count.
    private int characters;
    /// Previous single-byte character class.
    private int previous;
    /// Whether the previous class came from a non-ASCII scalar.
    private boolean previousNonAscii;
    /// Non-ASCII scalars in the current segment.
    private int nonAscii;
    /// Unclosed parentheses, brackets, braces, guillemets, and curly quotes.
    private final int[] delimiters = new int[5];
    /// Order-sensitive hash of admitted features.
    private long fingerprint;
    /// Last non-ASCII parent component, used when the basename supplies no non-ASCII text.
    private @Nullable EncodingScore parent;

    /// Selects a repertoire for multibyte scoring.
    @NotNullByDefault
    enum Family {
        /// Single-byte class-pair statistics.
        SINGLE_BYTE,
        /// Well-formed UTF-8 scalar structure.
        UTF8,
        /// Simplified Chinese repertoire and character frequencies.
        CHINESE,
        /// Traditional Chinese repertoire.
        BIG5,
        /// Japanese kana and ideograph frequencies.
        JAPANESE,
        /// Korean syllables and ideographs.
        KOREAN
    }

    /// Creates a scorer for an installed charset.
    MetadataScorer(Charset charset, @Nullable SingleByteData model, Family family) {
        this.charset = charset;
        this.decoder = charset.newDecoder();
        this.model = model;
        this.family = family;
        this.eucJapanese = charset.name().equalsIgnoreCase("EUC-JP");
    }

    /// Validates the entire field and returns evidence, or null on strict decoding failure.
    /// Source storage is neither changed nor retained; the output workspace is overwritten.
    /// The writable workspace must hold at least two UTF-16 units for supplementary characters.
    @Nullable EncodingScore score(@UnmodifiableView ByteBuffer source, EncodingDetector.Kind kind, CharBuffer output) {
        if (output.capacity() < 2) throw new IllegalArgumentException("Output workspace is too small");
        this.kind = kind;
        parent = null;
        resetSegment();
        decoder.reset();
        var input = source.duplicate();
        int rawPosition = source.position();
        int pending = ABSENT;
        boolean decoding = true;
        while (true) {
            output.clear();
            var result = decoding ? decoder.decode(input, output, true) : decoder.flush(output);
            output.flip();
            while (output.hasRemaining()) {
                char current = output.get();
                int point = current;
                if (pending != ABSENT) {
                    if (Character.isLowSurrogate(current)) {
                        point = Character.toCodePoint((char) pending, current);
                        pending = ABSENT;
                    } else {
                        accept(pending, 0);
                        pending = ABSENT;
                    }
                }
                if (Character.isHighSurrogate(current)) {
                    pending = current;
                    continue;
                }
                int classification = 0;
                if (model != null) {
                    classification = model.classify(source.get(rawPosition++) & 255);
                } else if (family != Family.UTF8) {
                    // JDK decoding determines validity; byte ranges only identify repertoire levels.
                    int lead = source.get(rawPosition++) & 255;
                    if (lead >= 128 && !(family == Family.JAPANESE && !eucJapanese && lead >= 0xA1 && lead <= 0xDF)) {
                        int trail = source.get(rawPosition++) & 255;
                        classification = (lead << 8) | trail;
                        if (family == Family.CHINESE && trail >= '0' && trail <= '9') {
                            rawPosition += 2;
                            classification = 0;
                        } else if (eucJapanese && lead == 0x8F) {
                            rawPosition++;
                            classification = 0;
                        }
                    }
                }
                accept(point, classification);
            }
            if (result.isError()) return null;
            if (result.isUnderflow()) {
                if (!decoding) break;
                decoding = false;
            }
        }
        if (pending != ABSENT) accept(pending, 0);
        finishSegment();
        return nonAscii == 0 && parent != null ? parent : snapshot();
    }

    /// Resets field-local or path-component-local scoring state.
    private void resetSegment() {
        score = 0;
        evidence = 0;
        suspicious = 0;
        characters = 0;
        previous = 0;
        previousNonAscii = false;
        nonAscii = 0;
        fingerprint = 0;
        Arrays.fill(features, 0);
        Arrays.fill(repetitions, (byte) 0);
        Arrays.fill(delimiters, 0);
    }

    /// Applies boundaries to decoded scalars, never to undecoded bytes.
    private void accept(int point, int classification) {
        if (kind == EncodingDetector.Kind.NAME && (point == '/' || point == '\\')) {
            finishSegment();
            if (nonAscii > 0) parent = snapshot();
            resetSegment();
            return;
        }
        if (characters++ >= MAX_SCORED_CHARACTERS) return;
        if (point >= 128) nonAscii++;
        int type = Character.getType(point);
        if (type == Character.CONTROL && !(kind == EncodingDetector.Kind.COMMENT
                && (point == '\r' || point == '\n' || point == '\t'))
                || type == Character.PRIVATE_USE || type == Character.UNASSIGNED || type == Character.SURROGATE) {
            suspicious++;
        }
        if (kind == EncodingDetector.Kind.NAME) {
            delimiter(point);
            if (point == '.' || point == '_' || point == '-' || point >= '0' && point <= '9') {
                boundary();
                return;
            }
        }
        if (model != null) {
            if (classification == 255) {
                suspicious++;
                boundary();
                return;
            }
            int current = classification & 127;
            if (point >= 128 || previousNonAscii) {
                add(1 + (current << 8) + previous,
                        model.score(current, previous, model.charsetName().equalsIgnoreCase("WINDOWS-1256")));
            }
            previous = current;
            previousNonAscii = point >= 128;
        } else if (point >= 128) {
            long value = repertoireScore(point, classification);
            if (value > 0) add(0x10000 + point, value);
            else if (Character.isLetter(point)) suspicious++;
        }
    }

    /// Scores repertoire evidence without prose capitalization or Latin-adjacency penalties.
    private long repertoireScore(int point, int bytes) {
        int lead = bytes >>> 8;
        int trail = bytes & 255;
        if (family == Family.UTF8) return Character.isDefined(point) ? 41 : 0;
        if (family == Family.JAPANESE && point >= 0x3040 && point < 0x3100) return eucJapanese ? 54 : 20;
        if (family == Family.JAPANESE && point >= 0xFF61 && point <= 0xFF9F) return 1;
        if (family == Family.KOREAN && point >= 0xAC00 && point <= 0xD7A3) {
            return lead >= 0xA1 && trail >= 0xA1 ? 42 + frequency(point, Models.FREQUENT_HANGUL) : 4;
        }
        if (Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN) {
            return switch (family) {
                case CHINESE -> trail >= 0xA1 && lead >= 0xA1
                        ? (lead <= 0xD7 ? 41 + frequency(point, Models.FREQUENT_SIMPLIFIED) : 20) : 5;
                case JAPANESE -> bytes == 0 ? 5 : (eucJapanese ? lead < 0xD0 : bytes < 0x9873)
                        ? 41 + frequency(point, Models.FREQUENT_KANJI) : 20;
                case BIG5 -> lead >= 0xA4 && lead <= 0xC6 ? 41 : 20;
                case KOREAN -> 10;
                default -> 0;
            };
        }
        return 0;
    }

    /// Returns the retained rank bonus for a frequent character.
    private static int frequency(int point, char @Unmodifiable [] table) {
        for (int i = 0; i < table.length; i++) {
            if (point == table[i]) return (128 - i) / 16;
        }
        return 0;
    }

    /// Caps exact repeated features before updating scores and fingerprints.
    private void add(int key, long value) {
        if (value == 0) return;
        int slot = (key * 0x9E3779B9) >>> 23;
        for (int inspected = 0; inspected < features.length; inspected++) {
            if (features[slot] == 0 || features[slot] == key) {
                features[slot] = key;
                int limit = kind == EncodingDetector.Kind.NAME ? 2 : 4;
                if (repetitions[slot] < limit) {
                    repetitions[slot]++;
                    score += value;
                    evidence++;
                    fingerprint = Long.rotateLeft(fingerprint, 11) ^ (key * 0x9E3779B97F4A7C15L);
                }
                return;
            }
            slot = (slot + 1) & (features.length - 1);
        }
    }

    /// Ends a statistical word without adding ASCII-only evidence.
    private void boundary() {
        if (model != null && previousNonAscii) add(1 + previous, model.score(0, previous, false));
        previous = 0;
        previousNonAscii = false;
    }

    /// Counts unpaired punctuation as lexical inconsistency rather than a decoding error.
    private void delimiter(int point) {
        int opening = switch (point) {
            case '(' -> 0;
            case '[' -> 1;
            case '{' -> 2;
            case '«' -> 3;
            case '“' -> 4;
            default -> ABSENT;
        };
        int closing = switch (point) {
            case ')' -> 0;
            case ']' -> 1;
            case '}' -> 2;
            case '»' -> 3;
            case '”' -> 4;
            default -> ABSENT;
        };
        if (opening >= 0) delimiters[opening]++;
        if (closing >= 0) {
            if (delimiters[closing] == 0) suspicious++;
            else delimiters[closing]--;
        }
    }

    /// Completes the current segment's word and delimiter state.
    private void finishSegment() {
        boundary();
        for (int count : delimiters) suspicious += count;
    }

    /// Copies metrics without exposing mutable workspace storage.
    private EncodingScore snapshot() {
        return new EncodingScore(charset, score, evidence, suspicious, fingerprint);
    }
}
