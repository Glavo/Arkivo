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
    /// Maximum distinct sampled names, or comments when no name supplies evidence.
    static final int MAX_SAMPLES = 256;
    /// Maximum retained raw sample bytes per role.
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
        var comments = new Samples();
        var evidence = new Evidence();
        scan(centralDirectory, field -> {
            if (field.unicode != null) {
                var matches = detector.matching(field.bytes, field.unicode);
                if (!matches.isEmpty()) {
                    if (evidence.anchors == null) evidence.anchors = new HashSet<>(matches);
                    else evidence.anchors.retainAll(matches);
                }
            } else if (!EncodingDetector.isAscii(field.bytes)) {
                (field.kind == EncodingDetector.Kind.NAME ? names : comments).accept(field.bytes);
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
        if (ranked.isEmpty()) ranked = detector.rank(comments.values(), EncodingDetector.Kind.COMMENT, preference);
        var candidates = new ArrayList<Charset>();
        if (evidence.anchors != null) {
            // Exact Unicode counterparts constrain the common encoding, not the output of their own entry.
            for (var score : ranked) if (evidence.anchors.contains(score.charset())) candidates.add(score.charset());
            evidence.anchors.stream().sorted().filter(value -> !candidates.contains(value)).forEach(candidates::add);
        } else {
            for (var score : ranked) candidates.add(score.charset());
        }
        for (var candidate : candidates) {
            if (!decodeFields(centralDirectory, detector, candidate, decodedFields)) continue;
            // With no counterpart, a statistical tie between different outputs remains unresolved.
            if (evidence.anchors == null) {
                double score = 0;
                for (var value : ranked) if (value.charset().equals(candidate)) score = value.similarity();
                for (var alternative : ranked) {
                    if (alternative.similarity() != score || alternative.charset().equals(candidate)
                            || preference.accepts(candidate) && !preference.accepts(alternative.charset())) continue;
                    Charset other = alternative.charset();
                    var alternativeFields = new HashMap<Integer, String>();
                    if (decodeFields(centralDirectory, detector, other, alternativeFields)
                            && !alternativeFields.equals(decodedFields)) {
                        decodedFields.clear();
                        return new ZipAutoMetadataDecoder(CP437);
                    }
                }
            }
            return new ZipAutoMetadataDecoder(candidate);
        }
        decodedFields.clear();
        return new ZipAutoMetadataDecoder(CP437);
    }

    /// Collects each successful legacy field once; failed candidates leave no partial results.
    private static boolean decodeFields(ByteBuffer central, EncodingDetector detector, Charset charset,
                                        Map<Integer, String> decodedFields) throws IOException {
        decodedFields.clear();
        boolean valid = scan(central, field -> {
            if (field.unicode != null || EncodingDetector.isAscii(field.bytes)) return true;
            @Nullable String text = detector.decode(field.bytes, field.kind, charset);
            if (text == null) return false;
            decodedFields.put(field.offset, text);
            return true;
        });
        if (!valid) decodedFields.clear();
        return valid;
    }

    @Override
    public String decode(Context context) throws IOException {
        return decode(context.bytes(), context.metadataKind(), context.versionMadeBy(), context.externalAttributes(),
                context.headerSource());
    }

    @Override
    public String decode(@UnmodifiableView ByteBuffer bytes) throws IOException {
        return decode(bytes, MetadataKind.UNKNOWN, UNKNOWN_HEADER_VALUE, UNKNOWN_HEADER_VALUE, HeaderSource.UNKNOWN);
    }

    /// Decodes reader-owned bytes without retaining them or constructing a public context.
    String decode(byte[] bytes, MetadataKind kind, int madeBy, long attributes, HeaderSource source) throws IOException {
        return decode(ByteBuffer.wrap(bytes), kind, madeBy, attributes, source);
    }

    /// Uses the frozen choice without further guessing; a changed or inconsistent field fails strictly.
    private String decode(ByteBuffer bytes, MetadataKind kind, int madeBy, long attributes, HeaderSource source)
            throws IOException {
        if (EncodingDetector.isAscii(bytes)) return StandardCharsets.US_ASCII.decode(bytes.duplicate()).toString();
        if (charset == null) {
            @Nullable String decoded = new EncodingDetector().decode(bytes, kind == MetadataKind.ENTRY_COMMENT
                    ? EncodingDetector.Kind.COMMENT : EncodingDetector.Kind.NAME,
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
            for (var kind : EncodingDetector.Kind.values()) {
                int length = kind == EncodingDetector.Kind.NAME ? nameLength : commentLength;
                if (length == 0) continue;
                int fieldOffset = kind == EncodingDetector.Kind.NAME ? start : start + nameLength + extraLength;
                var bytes = buffer.slice(fieldOffset, length);
                @Nullable String unicode = null;
                if (extraLength != 0) {
                    byte[] raw = new byte[length];
                    bytes.get(0, raw);
                    unicode = ZipEntryNameDecoder.decodeUnicodeExtraField(raw, extra,
                            kind == EncodingDetector.Kind.NAME ? ZipEntryNameDecoder.UNICODE_PATH_EXTRA_FIELD_ID
                                    : ZipEntryNameDecoder.UNICODE_COMMENT_EXTRA_FIELD_ID);
                }
                if (!visitor.visit(new Field(fieldOffset, bytes, kind, preference, unicode))) return false;
            }
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
    /// @param kind name or comment
    /// @param preference producer code-page family
    /// @param unicode authoritative text, or null when legacy decoding is required
    @NotNullByDefault
    private record Field(int offset, @UnmodifiableView ByteBuffer bytes, EncodingDetector.Kind kind,
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
