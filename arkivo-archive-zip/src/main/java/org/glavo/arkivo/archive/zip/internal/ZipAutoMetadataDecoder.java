// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.zip.ZipLegacyMetadataDecoder;
import org.glavo.arkivo.encoding.detector.internal.EncodingDetector;
import org.glavo.arkivo.encoding.detector.internal.EncodingEvidence;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/// Decodes undeclared ZIP names and comments using strict charset validation and bounded field evidence.
/// Instances retain only immutable archive summaries. Calls do not share mutable decoding state.
@NotNullByDefault
public final class ZipAutoMetadataDecoder implements ZipLegacyMetadataDecoder {
    /// Stateless strategy for direct and forward-only reads.
    public static final ZipAutoMetadataDecoder DEFAULT = new ZipAutoMetadataDecoder(Map.of());
    /// Fallback when different decoded texts remain ambiguous.
    private static final Charset CP437 = Charset.forName("IBM437");
    /// Maximum retained fields per metadata kind, after exact deduplication.
    static final int MAX_SAMPLES = 256;
    /// Maximum sampled raw bytes per metadata kind.
    static final int MAX_SAMPLE_BYTES = 128 * 1024;
    /// Long fields are validated and decoded but do not influence other entries.
    static final int MAX_FIELD_BYTES = EncodingDetector.MAX_EVIDENCE_FIELD_BYTES;
    /// Maximum copied directory prefixes across all creator groups.
    private static final int MAX_PARENTS = 64;
    /// Maximum copied directory-prefix bytes across all creator groups.
    private static final int MAX_PARENT_BYTES = 16 * 1024;
    /// Evidence grouped by creator system and logical metadata role, not by inferred language.
    private final @Unmodifiable Map<Integer, Group> groups;

    /// Freezes archive summaries without retaining borrowed central-directory storage.
    private ZipAutoMetadataDecoder(Map<Integer, Group> groups) {
        this.groups = Map.copyOf(groups);
    }

    /// Prepares the default strategy; custom decoders are neither invoked nor inspected.
    /// Samples the complete central directory without reading local headers or entry content.
    static ArchiveMetadataDecoder prepare(ArchiveMetadataDecoder configured, ByteBuffer centralDirectory) throws IOException {
        if (configured != DEFAULT) return configured;
        ByteBuffer buffer = centralDirectory.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        var detector = new EncodingDetector();
        var names = new Sampler(buffer, true, detector);
        var comments = new Sampler(buffer, false, detector);
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
            names.accept(start, nameLength, madeBy >>> 8, preference, start + nameLength, extraLength);
            comments.accept(start + nameLength + extraLength, commentLength, madeBy >>> 8, preference,
                    start + nameLength, extraLength);
        }
        var result = new HashMap<Integer, Group>();
        int[] parentBudget = {MAX_PARENTS, MAX_PARENT_BYTES};
        names.collect(result, parentBudget);
        comments.collect(result, parentBudget);
        return result.isEmpty() ? DEFAULT : new ZipAutoMetadataDecoder(result);
    }

    @Override
    public String decode(Context context) {
        return decode(context.bytes(), context.metadataKind(), context.versionMadeBy(), context.externalAttributes(),
                context.headerSource());
    }

    @Override
    public String decode(@UnmodifiableView ByteBuffer bytes) {
        return decode(bytes, MetadataKind.UNKNOWN, UNKNOWN_HEADER_VALUE, UNKNOWN_HEADER_VALUE, HeaderSource.UNKNOWN);
    }

    /// Decodes reader-owned bytes without constructing a public context or retaining its storage.
    String decode(byte[] bytes, MetadataKind kind, int madeBy, long attributes, HeaderSource source) {
        return decode(ByteBuffer.wrap(bytes), kind, madeBy, attributes, source);
    }

    /// Resolves local evidence before archive hints; all chosen charsets must decode the complete field strictly.
    private String decode(ByteBuffer bytes, MetadataKind metadataKind, int madeBy, long attributes, HeaderSource source) {
        if (EncodingDetector.isAscii(bytes)) return StandardCharsets.US_ASCII.decode(bytes.duplicate()).toString();
        var kind = metadataKind == MetadataKind.ENTRY_COMMENT ? EncodingDetector.Kind.COMMENT : EncodingDetector.Kind.NAME;
        int creator = madeBy == UNKNOWN_HEADER_VALUE ? UNKNOWN_HEADER_VALUE : madeBy >>> 8;
        @Nullable Group group = groups.get(creator * 2 + (kind == EncodingDetector.Kind.COMMENT ? 1 : 0));
        var preference = ZipLegacyCodePages.preference(madeBy, attributes, source);
        var detector = new EncodingDetector();
        @Nullable Charset selected = detector.detect(bytes, kind, null, preference);
        if (selected != null) return decodeStrict(bytes, selected);
        if (group != null) {
            @Nullable Charset anchored = detector.agreement(bytes, group.anchors);
            if (anchored != null) return decodeStrict(bytes, anchored);
            if (kind == EncodingDetector.Kind.NAME) {
                @Nullable List<Charset> parents = group.parent(bytes);
                if (parents != null) {
                    @Nullable Charset local = detector.agreement(bytes, parents);
                    return decodeStrict(bytes, local == null ? CP437 : local);
                }
            }
            if (!group.conflictingAnchors) selected = detector.detect(bytes, kind, group.evidence, preference);
        }
        return decodeStrict(bytes, selected == null ? CP437 : selected);
    }

    /// Materializes only a validated selection; CP437 maps every byte.
    private static String decodeStrict(ByteBuffer bytes, Charset charset) {
        try {
            return charset.newDecoder().decode(bytes.duplicate()).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException("Selected charset cannot decode validated metadata", exception);
        }
    }

    /// Selects the smallest stable content hashes, borrowing central-directory storage only during preparation.
    @NotNullByDefault
    private static final class Sampler {
        /// Borrowed complete directory view.
        private final ByteBuffer buffer;
        /// Whether this sampler gathers names rather than comments.
        private final boolean name;
        /// Shared call-local decoding workspace.
        private final EncodingDetector detector;
        /// Samples ordered by content and creator; physical offsets never break ties.
        private final TreeMap<Sample, Sample> samples;

        /// Creates an independent budget for one logical metadata kind.
        private Sampler(ByteBuffer buffer, boolean name, EncodingDetector detector) {
            this.buffer = buffer;
            this.name = name;
            this.detector = detector;
            samples = new TreeMap<>((left, right) -> {
                int result = Long.compareUnsigned(left.hash, right.hash);
                if (result == 0) result = Integer.compare(left.creator, right.creator);
                if (result == 0) result = left.preference.compareTo(right.preference);
                return result != 0 ? result : buffer.slice(left.offset, left.length).compareTo(buffer.slice(right.offset, right.length));
            });
        }

        /// Retains a bounded non-ASCII field and merges duplicate Unicode evidence symmetrically.
        private void accept(int offset, int length, int creator, EncodingDetector.Preference preference,
                            int extraOffset, int extraLength) throws IOException {
            if (length == 0 || length > MAX_FIELD_BYTES) return;
            ByteBuffer bytes = buffer.slice(offset, length);
            if (EncodingDetector.isAscii(bytes)) return;
            long hash = 0xcbf29ce484222325L ^ creator;
            for (int index = 0; index < length; index++) hash = (hash ^ (bytes.get(index) & 255)) * 0x100000001b3L;
            var sample = new Sample(offset, length, creator, hash, preference);
            @Nullable Sample retained = samples.get(sample);
            if (retained == null) {
                if (samples.size() == MAX_SAMPLES && samples.comparator().compare(sample, samples.lastKey()) >= 0) return;
                samples.put(sample, sample);
                if (samples.size() > MAX_SAMPLES) samples.pollLastEntry();
                retained = sample;
            }
            if (extraLength != 0) {
                byte[] raw = new byte[length];
                buffer.get(offset, raw);
                byte[] extra = new byte[extraLength];
                buffer.get(extraOffset, extra);
                @Nullable String unicode = ZipEntryNameDecoder.decodeUnicodeExtraField(raw, extra,
                        name ? ZipEntryNameDecoder.UNICODE_PATH_EXTRA_FIELD_ID : ZipEntryNameDecoder.UNICODE_COMMENT_EXTRA_FIELD_ID);
                if (unicode != null) {
                    var matches = detector.matching(bytes, unicode);
                    // A lossy legacy spelling does not prove anything about other entries' code pages.
                    if (!matches.isEmpty()) {
                        retained.anchored = true;
                        retained.anchors.addAll(matches);
                    }
                }
            }
        }

        /// Freezes each creator's score vector and bounded directory hints in canonical sample order.
        private void collect(Map<Integer, Group> result, int[] budget) {
            var fields = new TreeMap<Integer, List<ByteBuffer>>();
            var anchors = new HashMap<Integer, Set<Charset>>();
            var parents = new HashMap<Integer, Map<ByteBuffer, Set<Charset>>>();
            int size = 0;
            var kind = name ? EncodingDetector.Kind.NAME : EncodingDetector.Kind.COMMENT;
            for (var sample : samples.values()) {
                if (sample.length > MAX_SAMPLE_BYTES - size) break;
                size += sample.length;
                int key = sample.creator * 2 + (name ? 0 : 1);
                ByteBuffer bytes = buffer.slice(sample.offset, sample.length);
                fields.computeIfAbsent(key, ignored -> new ArrayList<>()).add(bytes);
                if (sample.anchored) anchors.merge(key, new HashSet<>(sample.anchors), (left, right) -> {
                    left.retainAll(right);
                    return left;
                });
                if (!name) continue;
                @Nullable Charset selected = detector.detect(bytes, kind, null, sample.preference);
                List<Charset> candidates = sample.anchored ? List.copyOf(sample.anchors)
                        : selected == null ? List.of() : List.of(selected);
                if (candidates.isEmpty()) continue;
                var directory = parents.computeIfAbsent(key, ignored -> new HashMap<>());
                boolean nonAscii = false;
                for (int index = 0; index < bytes.remaining(); index++) {
                    byte value = bytes.get(index);
                    nonAscii |= value < 0;
                    // Slash cannot occur inside a character in the candidate set; backslash can.
                    if (value != '/' || !nonAscii) continue;
                    ByteBuffer prefix = bytes.slice(0, index + 1);
                    @Nullable Set<Charset> prior = directory.get(prefix);
                    if (prior == null && budget[0] > 0 && prefix.remaining() <= budget[1]) {
                        byte[] owned = new byte[prefix.remaining()];
                        prefix.get(owned);
                        budget[0]--;
                        budget[1] -= owned.length;
                        prior = new HashSet<>();
                        directory.put(ByteBuffer.wrap(owned).asReadOnlyBuffer(), prior);
                    }
                    if (prior != null) prior.addAll(candidates);
                }
            }
            fields.forEach((key, values) -> {
                var directory = new HashMap<ByteBuffer, List<Charset>>();
                parents.getOrDefault(key, Map.of()).forEach((prefix, candidates) ->
                        directory.put(prefix, candidates.stream().sorted().toList()));
                result.put(key, new Group(detector.prepare(values, kind),
                        anchors.getOrDefault(key, Set.of()).stream().sorted().toList(),
                        anchors.containsKey(key) && anchors.get(key).isEmpty(), Map.copyOf(directory)));
            });
        }
    }

    /// Retains borrowed coordinates and validated counterpart candidates until preparation finishes.
    @NotNullByDefault
    private static final class Sample {
        /// Complete field offset in the borrowed directory.
        private final int offset;
        /// Raw byte length.
        private final int length;
        /// Creator-system group.
        private final int creator;
        /// Stable sample rank.
        private final long hash;
        /// Legacy code-page family inferred only from compatibility rules.
        private final EncodingDetector.Preference preference;
        /// Whether a Unicode counterpart has an exact representation in at least one candidate.
        private boolean anchored;
        /// Union of duplicate fields' exact counterpart matches.
        private final Set<Charset> anchors = new HashSet<>();

        /// Stores coordinates without copying raw name bytes.
        private Sample(int offset, int length, int creator, long hash, EncodingDetector.Preference preference) {
            this.offset = offset;
            this.length = length;
            this.creator = creator;
            this.hash = hash;
            this.preference = preference;
        }
    }

    /// Stores immutable evidence without central-directory aliases.
    /// @param evidence bounded field-score summary
    /// @param anchors candidates consistent with the sampled Unicode counterparts
    /// @param conflictingAnchors whether counterpart evidence disproves a common legacy encoding
    /// @param parents privately owned read-only raw prefixes and their candidate sets
    @NotNullByDefault
    private record Group(EncodingEvidence evidence, @Unmodifiable List<Charset> anchors, boolean conflictingAnchors,
                         @Unmodifiable Map<@UnmodifiableView ByteBuffer, @Unmodifiable List<Charset>> parents) {
        /// Returns the longest retained raw prefix's candidates, or null when none was sampled.
        private @Nullable List<Charset> parent(ByteBuffer bytes) {
            for (int index = bytes.limit() - 1; index >= bytes.position(); index--) {
                if (bytes.get(index) == '/') {
                    @Nullable var found = parents.get(bytes.slice(bytes.position(), index - bytes.position() + 1));
                    if (found != null) return found;
                }
            }
            return null;
        }
    }
}
