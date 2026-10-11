// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.zip.ZipLegacyMetadataDecoder;
import org.glavo.arkivo.encoding.detector.internal.EncodingDetector;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/// Decodes undeclared ZIP metadata with one frozen legacy encoding per indexed archive.
/// Direct and streaming calls use independent single-field inference. Format declarations are handled
/// by the caller before this strategy. Instances retain no archive buffers or mutable scoring state.
@NotNullByDefault
public final class ZipAutoMetadataDecoder implements ZipLegacyMetadataDecoder {
    /// Stateless strategy for direct and forward-only reads.
    public static final ZipAutoMetadataDecoder DEFAULT = new ZipAutoMetadataDecoder(null);
    /// ZIP fallback for absent, contradictory, or indistinguishable evidence.
    private static final Charset CP437 = Charset.forName("IBM437");
    /// Sentinel for a candidate that has not decoded every required field successfully.
    private static final double INVALID_SCORE = -1;
    /// Maximum distinct sampled names.
    static final int MAX_SAMPLES = 256;
    /// Maximum retained raw name sample bytes.
    static final int MAX_SAMPLE_BYTES = 128 * 1024;
    /// Larger fields are validated but do not contribute statistical evidence.
    static final int MAX_FIELD_BYTES = EncodingDetector.MAX_EVIDENCE_FIELD_BYTES;
    /// Frozen archive selection, or null for independent single-field inference.
    private final @Nullable Charset charset;

    /// Stores only a charset, never a decoder or borrowed archive storage.
    private ZipAutoMetadataDecoder(@Nullable Charset charset) {
        this.charset = charset;
    }

    /// Selects and validates one encoding before path indexing; custom strategies are returned untouched.
    /// Only central-directory bytes are read. Successful legacy decodes are transferred by absolute field offset
    /// to the caller's temporary index-building map, which is cleared before selection and on candidate failure.
    /// The frozen strategy retains neither this map nor its strings. Explicit Unicode fields are handled by the reader.
    static ArchiveMetadataDecoder prepare(ArchiveMetadataDecoder configured, ByteBuffer centralDirectory,
                                          Map<Integer, String> decodedFields) throws IOException {
        decodedFields.clear();
        if (configured != DEFAULT) return configured;
        var detector = new EncodingDetector();
        var names = new Samples();
        var evidence = new Evidence();
        scan(centralDirectory, field -> {
            if (field.unicode != null) {
                var matches = detector.matching(field.bytes, field.unicode);
                if (!matches.isEmpty()) {
                    if (evidence.anchors == null) evidence.anchors = new HashSet<>(matches);
                    else evidence.anchors.retainAll(matches);
                }
            } else if (!EncodingDetector.isAscii(field.bytes)) {
                names.accept(field.bytes);
            }
            if (!EncodingDetector.isAscii(field.bytes)) {
                evidence.preferences |= switch (field.preference) {
                    case NONE -> 0;
                    case OEM -> 1;
                    case ANSI -> 2;
                };
            }
            return true;
        });
        var preference = evidence.preferences == 1 ? EncodingDetector.Preference.OEM
                : evidence.preferences == 2 ? EncodingDetector.Preference.ANSI : EncodingDetector.Preference.NONE;
        if (evidence.anchors != null && evidence.anchors.isEmpty()) return new ZipAutoMetadataDecoder(CP437);
        var ranked = detector.rank(names.values(), EncodingDetector.Kind.NAME, preference);
        var candidates = new ArrayList<EncodingDetector.Score>();
        if (evidence.anchors != null) {
            // Exact Unicode counterparts constrain the common encoding, not the output of their own entry.
            for (var score : ranked) if (evidence.anchors.contains(score.charset())) candidates.add(score);
            evidence.anchors.stream().sorted()
                    .filter(value -> candidates.stream().noneMatch(score -> score.charset().equals(value)))
                    .forEach(value -> candidates.add(new EncodingDetector.Score(value, 0)));
        } else {
            candidates.addAll(ranked);
        }
        @Nullable Charset selected = null;
        @Nullable Map<Integer, String> selectedFields = null;
        Map<Integer, String> fields = decodedFields;
        double best = INVALID_SCORE;
        boolean ambiguous = false;
        for (var candidate : candidates) {
            // The character multiplier never exceeds one; lower raw scores cannot overtake the winner.
            if (candidate.similarity() < best) break;
            if (fields == selectedFields) fields = new HashMap<>();
            double quality = decodeFields(centralDirectory, detector, candidate.charset(), fields);
            if (quality < 0) continue;
            double adjusted = candidate.similarity() * quality;
            if (selected == null || adjusted > best || adjusted == best
                    && preference.accepts(candidate.charset()) && !preference.accepts(selected)) {
                selected = candidate.charset();
                selectedFields = fields;
                best = adjusted;
                ambiguous = false;
            } else if (evidence.anchors == null && adjusted == best
                    && preference.accepts(candidate.charset()) == preference.accepts(selected)
                    && !fields.equals(selectedFields)) {
                ambiguous = true;
            }
        }
        if (selected != null && !ambiguous) {
            if (selectedFields != decodedFields) {
                decodedFields.clear();
                decodedFields.putAll(java.util.Objects.requireNonNull(selectedFields));
            }
            return new ZipAutoMetadataDecoder(selected);
        }
        decodedFields.clear();
        return new ZipAutoMetadataDecoder(CP437);
    }

    /// Collects decoded fields and returns their lowest character-quality multiplier, or -1 for invalid bytes.
    /// Using the lowest multiplier prevents repeated names from compounding a control penalty.
    /// Failed candidates leave no partial results; controls remain text rather than decoding errors.
    private static double decodeFields(ByteBuffer central, EncodingDetector detector, Charset charset,
                                       Map<Integer, String> decodedFields) throws IOException {
        decodedFields.clear();
        double[] quality = {1};
        boolean valid = scan(central, field -> {
            if (field.unicode != null || EncodingDetector.isAscii(field.bytes)) return true;
            @Nullable String text = detector.decode(field.bytes, EncodingDetector.Kind.NAME, charset);
            if (text == null) return false;
            quality[0] = Math.min(quality[0], EncodingDetector.textQuality(text, EncodingDetector.Kind.NAME));
            decodedFields.put(field.offset, text);
            return true;
        });
        if (!valid) decodedFields.clear();
        return valid ? quality[0] : INVALID_SCORE;
    }

    @Override
    public String decode(Context context) throws IOException {
        return decode(context.bytes(), context.versionMadeBy(), context.externalAttributes(),
                context.headerSource());
    }

    @Override
    public String decode(@UnmodifiableView ByteBuffer bytes) throws IOException {
        return decode(bytes, UNKNOWN_HEADER_VALUE, UNKNOWN_HEADER_VALUE, HeaderSource.UNKNOWN);
    }

    /// Decodes reader-owned bytes without retaining them or constructing a public context.
    String decode(byte[] bytes, int madeBy, long attributes, HeaderSource source) throws IOException {
        return decode(ByteBuffer.wrap(bytes), madeBy, attributes, source);
    }

    /// Uses the frozen choice without further guessing; a changed or inconsistent field fails strictly.
    private String decode(ByteBuffer bytes, int madeBy, long attributes, HeaderSource source)
            throws IOException {
        if (EncodingDetector.isAscii(bytes)) return StandardCharsets.US_ASCII.decode(bytes.duplicate()).toString();
        if (charset == null) {
            @Nullable String decoded = new EncodingDetector().decode(bytes, EncodingDetector.Kind.NAME,
                    ZipLegacyCodePages.preference(madeBy, attributes, source));
            if (decoded != null) return decoded;
        }
        return (charset == null ? CP437 : charset).newDecoder().decode(bytes.duplicate()).toString();
    }

    /// Visits complete central fields without changing the caller's buffer; false stops a validation pass.
    private static boolean scan(ByteBuffer central, FieldVisitor visitor) throws IOException {
        var buffer = central.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            int offset = buffer.position();
            if (buffer.remaining() < 46 || buffer.getInt(offset) != ZipConstants.CENTRAL_DIRECTORY_HEADER_SIGNATURE)
                throw new IOException("Invalid ZIP central directory header");
            int nameLength = Short.toUnsignedInt(buffer.getShort(offset + 28));
            int extraLength = Short.toUnsignedInt(buffer.getShort(offset + 30));
            int commentLength = Short.toUnsignedInt(buffer.getShort(offset + 32));
            int variable = nameLength + extraLength + commentLength;
            if (variable > buffer.remaining() - 46) throw new IOException("Invalid ZIP central directory variable data length");
            int start = offset + 46;
            buffer.position(start + variable);
            if ((buffer.getShort(offset + 8) & ZipEntryNameDecoder.UTF_8_FLAG) != 0) continue;
            int madeBy = Short.toUnsignedInt(buffer.getShort(offset + 4));
            var preference = ZipLegacyCodePages.preference(madeBy, Integer.toUnsignedLong(buffer.getInt(offset + 38)),
                    HeaderSource.CENTRAL_DIRECTORY);
            byte[] extra = new byte[extraLength];
            buffer.get(start + nameLength, extra);
            if (nameLength == 0) continue;
            var bytes = buffer.slice(start, nameLength);
            @Nullable String unicode = null;
            if (extraLength != 0) {
                byte[] raw = new byte[nameLength];
                bytes.get(0, raw);
                unicode = ZipEntryNameDecoder.decodeUnicodeExtraField(raw, extra,
                        ZipEntryNameDecoder.UNICODE_PATH_EXTRA_FIELD_ID);
            }
            if (!visitor.visit(new Field(start, bytes, preference, unicode))) return false;
        }
        return true;
    }

    /// Receives a field valid only during the current preparation pass.
    @NotNullByDefault
    private interface FieldVisitor {
        /// Returns whether scanning should continue.
        boolean visit(Field field) throws IOException;
    }

    /// Describes borrowed metadata and its optional authoritative Unicode counterpart.
    /// @param offset absolute field offset in the supplied central-directory buffer
    /// @param bytes complete raw bytes
    /// @param preference producer code-page family
    /// @param unicode authoritative text, or null when legacy decoding is required
    @NotNullByDefault
    private record Field(int offset, @UnmodifiableView ByteBuffer bytes,
                         EncodingDetector.Preference preference, @Nullable String unicode) {
    }

    /// Holds temporary global counterpart constraints and compatibility hints.
    @NotNullByDefault
    private static final class Evidence {
        /// Intersection of lossless counterpart matches, or null if no counterpart supplied evidence.
        private @Nullable Set<Charset> anchors;
        /// Bit set of observed OEM and ANSI preferences.
        private int preferences;

        /// Creates empty evidence for one preparation call.
        private Evidence() {
        }
    }

    /// Selects content-ranked, distinct fields independently of central-directory order.
    @NotNullByDefault
    private static final class Samples {
        /// Borrowed samples ordered by stable unsigned hash, then signed byte content.
        private final TreeMap<ByteBuffer, ByteBuffer> fields = new TreeMap<>((left, right) -> {
            int order = Long.compareUnsigned(hash(left), hash(right));
            return order == 0 ? left.compareTo(right) : order;
        });

        /// Creates an empty bounded sampler.
        private Samples() {
        }

        /// Retains a field only if it falls within the count and per-field limits.
        private void accept(ByteBuffer bytes) {
            if (bytes.remaining() > MAX_FIELD_BYTES || bytes.remaining() == 0) return;
            fields.putIfAbsent(bytes, bytes);
            if (fields.size() > MAX_SAMPLES) fields.pollLastEntry();
        }

        /// Borrows the deterministically budgeted samples for immediate scoring.
        private List<ByteBuffer> values() {
            var values = new ArrayList<ByteBuffer>();
            int total = 0;
            for (var value : fields.values()) {
                if (value.remaining() > MAX_SAMPLE_BYTES - total) continue;
                total += value.remaining();
                values.add(value);
            }
            return values;
        }

        /// Hashes unchanged raw bytes, including digits that may belong to multibyte sequences.
        private static long hash(ByteBuffer bytes) {
            long result = 0xcbf29ce484222325L;
            for (int i = bytes.position(); i < bytes.limit(); i++) result = (result ^ (bytes.get(i) & 255)) * 0x100000001b3L;
            return result;
        }
    }
}
