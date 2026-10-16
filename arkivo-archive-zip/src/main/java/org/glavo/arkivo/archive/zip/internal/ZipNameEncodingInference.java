// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.zip.ZipLegacyMetadataDecoder;
import org.glavo.arkivo.encoding.detector.internal.EncodingDetector;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/// Infers one legacy name encoding from central-directory evidence before index construction.
/// Sampling and validation are confined to this call; the result does not retain archive buffers.
@NotNullByDefault
final class ZipNameEncodingInference {
    /// Prevents instantiation of this archive-level inference operation.
    private ZipNameEncodingInference() {
    }

    /// Sentinel for a candidate that has not decoded every required field successfully.
    private static final double INVALID_SCORE = -1;
    /// Maximum distinct sampled names.
    static final int MAX_SAMPLES = 256;
    /// Maximum retained raw name sample bytes.
    static final int MAX_SAMPLE_BYTES = 128 * 1024;
    /// Larger fields are validated but do not contribute statistical evidence.
    static final int MAX_FIELD_BYTES = EncodingDetector.MAX_EVIDENCE_FIELD_BYTES;

    /// Selects and validates one encoding before path indexing.
    /// Only central-directory bytes are read. Successful legacy decodes are transferred by absolute field offset
    /// to the caller's temporary index-building map, which is cleared before selection and on candidate failure.
    /// The returned charset retains neither this map nor its strings. Explicit Unicode fields are handled by the reader.
    static Charset select(ByteBuffer centralDirectory, Map<Integer, String> decodedFields) throws IOException {
        return select(centralDirectory, decodedFields, false);
    }

    /// Selects an encoding, excluding malformed authoritative text when the reader has enabled recovery.
    static Charset select(ByteBuffer centralDirectory, Map<Integer, String> decodedFields,
                          boolean recoverNames) throws IOException {
        decodedFields.clear();
        var detector = new EncodingDetector();
        var names = new Samples();
        var legacyFields = new LegacyFields();
        var evidence = new Evidence();
        scan(centralDirectory, recoverNames, field -> {
            if (field.unicode != null) {
                var matches = detector.matching(field.bytes, field.unicode);
                if (!matches.isEmpty()) {
                    if (evidence.anchors == null) evidence.anchors = new HashSet<>(matches);
                    else evidence.anchors.retainAll(matches);
                }
            } else if (!EncodingDetector.isAscii(field.bytes)) {
                names.accept(field.bytes);
                legacyFields.add(field.offset, field.bytes.remaining());
            }
            if (!EncodingDetector.isAscii(field.bytes)) {
                evidence.preferences |= switch (field.preference) {
                    case NONE -> 0;
                    case OEM -> 1;
                    case ANSI -> 2;
                };
            }
        });
        var preference = evidence.preferences == 1 ? EncodingDetector.Preference.OEM
                : evidence.preferences == 2 ? EncodingDetector.Preference.ANSI : EncodingDetector.Preference.NONE;
        if (evidence.anchors != null && evidence.anchors.isEmpty()) return ZipAutoMetadataDecoder.CP437;
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
            double quality = legacyFields.decode(centralDirectory, detector, candidate.charset(), fields);
            if (quality < 0) continue;
            double adjusted = candidate.similarity() * quality;
            if (selected == null || adjusted > best || adjusted == best
                    && preference.accepts(candidate.charset()) && !preference.accepts(selected)) {
                selected = candidate.charset();
                selectedFields = fields;
                best = adjusted;
                ambiguous = false;
            } else if (adjusted == best
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
            return selected;
        }
        decodedFields.clear();
        return ZipAutoMetadataDecoder.CP437;
    }

    /// Visits complete central fields once without changing the caller's buffer.
    private static void scan(ByteBuffer central, boolean recoverNames, FieldVisitor visitor) throws IOException {
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
                    ZipLegacyMetadataDecoder.HeaderSource.CENTRAL_DIRECTORY);
            if (nameLength == 0) continue;
            var bytes = buffer.slice(start, nameLength);
            @Nullable String unicode = null;
            if (extraLength != 0) {
                byte[] extra = new byte[extraLength];
                buffer.get(start + nameLength, extra);
                byte[] raw = new byte[nameLength];
                bytes.get(0, raw);
                try {
                    unicode = ZipEntryNameDecoder.decodeUnicodeExtraField(raw, extra,
                            ZipEntryNameDecoder.UNICODE_PATH_EXTRA_FIELD_ID);
                } catch (CharacterCodingException exception) {
                    if (!recoverNames) throw exception;
                    continue;
                }
            }
            visitor.visit(new Field(start, bytes, preference, unicode));
        }
    }

    /// Receives a field valid only during the current preparation pass.
    @NotNullByDefault
    private interface FieldVisitor {
        /// Collects evidence and decoding ranges from one borrowed central-directory field.
        void visit(Field field) throws IOException;
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

    /// Stores only ranges requiring legacy decoding; extra fields are parsed once during collection.
    @NotNullByDefault
    private static final class LegacyFields {
        /// Packed absolute offsets and lengths, allocated only for non-ASCII legacy names.
        private long[] ranges = new long[0];
        /// Number of occupied ranges, in central-directory order.
        private int size;

        /// Creates an empty range list for one preparation call.
        private LegacyFields() {
        }

        /// Appends a range without retaining the central-directory buffer or its views.
        private void add(int offset, int length) {
            if (size == ranges.length) ranges = Arrays.copyOf(ranges, Math.max(16, size * 2));
            ranges[size++] = (long) offset << 32 | length;
        }

        /// Decodes every range and returns the lowest character-quality multiplier, or -1 on failure.
        /// Failed candidates leave no partial results. The source buffer's state is preserved.
        private double decode(ByteBuffer central, EncodingDetector detector, Charset charset,
                              Map<Integer, String> decodedFields) {
            decodedFields.clear();
            double quality = 1;
            var bytes = central.duplicate();
            for (int index = 0; index < size; index++) {
                long range = ranges[index];
                int offset = (int) (range >>> 32);
                bytes.limit(offset + (int) range).position(offset);
                @Nullable String text = detector.decode(bytes, EncodingDetector.Kind.NAME, charset);
                if (text == null) {
                    decodedFields.clear();
                    return INVALID_SCORE;
                }
                quality = Math.min(quality, EncodingDetector.textQuality(text, EncodingDetector.Kind.NAME));
                decodedFields.put(offset, text);
            }
            return quality;
        }
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
        private final TreeSet<Sample> fields = new TreeSet<>();

        /// Creates an empty bounded sampler.
        private Samples() {
        }

        /// Retains a field only if it falls within the count and per-field limits.
        private void accept(ByteBuffer bytes) {
            if (bytes.remaining() > MAX_FIELD_BYTES || bytes.remaining() == 0) return;
            fields.add(new Sample(hash(bytes), bytes));
            if (fields.size() > MAX_SAMPLES) fields.pollLast();
        }

        /// Borrows the deterministically budgeted samples for immediate scoring.
        private List<ByteBuffer> values() {
            var values = new ArrayList<ByteBuffer>();
            int total = 0;
            for (var sample : fields) {
                var value = sample.bytes;
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

        /// Borrows one sample with a hash calculated only once.
        /// @param hash stable unsigned content hash
        /// @param bytes complete field, borrowed until preparation finishes
        @NotNullByDefault
        private record Sample(long hash, @UnmodifiableView ByteBuffer bytes) implements Comparable<Sample> {
            @Override
            public int compareTo(Sample other) {
                int order = Long.compareUnsigned(hash, other.hash);
                return order == 0 ? bytes.compareTo(other.bytes) : order;
            }
        }
    }
}
