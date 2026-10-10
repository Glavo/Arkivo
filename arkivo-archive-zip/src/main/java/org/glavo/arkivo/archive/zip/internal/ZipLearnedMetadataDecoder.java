// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.zip.ZipLegacyMetadataDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/// Applies a trained short-field model with immutable, bounded ZIP-specific evidence.
@NotNullByDefault
public final class ZipLearnedMetadataDecoder implements ZipLegacyMetadataDecoder {
    /// Maximum distinct fields selected independently for names and comments.
    static final int SAMPLES_PER_KIND = 256;

    /// Maximum copied raw field bytes per metadata kind.
    static final int BYTES_PER_KIND = 128 * 1024;

    /// Long fields are decoded completely but are not used to influence other entries.
    static final int MAX_FIELD = 4096;

    /// Maximum number of private raw parent prefixes retained across the whole archive.
    private static final int MAX_PARENTS = 64;

    /// Maximum total raw parent-key bytes retained across the whole archive.
    private static final int MAX_PARENT_BYTES = 16 * 1024;

    /// Read-only scoring data shared by all prepared instances.
    private final @Nullable ZipMetadataModel model;

    /// Frozen vectors keyed by creator system and logical metadata kind.
    private final @Unmodifiable Map<Integer, Group> groups;

    /// Creates a stateless decoder suitable for direct and forward-only calls.
    /// @param model shared immutable scoring coefficients
    public ZipLearnedMetadataDecoder(ZipMetadataModel model) {
        this(model, Map.of());
    }

    /// Creates a decoder whose bundled coefficients are loaded only when non-ASCII evidence is needed.
    public ZipLearnedMetadataDecoder() {
        this(null, Map.of());
    }

    /// Creates a prepared instance without retaining central-directory storage.
    private ZipLearnedMetadataDecoder(@Nullable ZipMetadataModel model, Map<Integer, Group> groups) {
        this.model = model;
        this.groups = Map.copyOf(groups);
    }

    /// Resolves the supplied model or the lazily loaded bundled coefficients.
    private ZipMetadataModel model() {
        return model == null ? ZipMetadataModel.bundled() : model;
    }

    @Override
    public String decode(Context context) {
        if (ZipMetadataModel.ascii(context.bytes())) return asciiText(context.bytes());
        int key = context.creatorSystem() * 2 + (context.metadataKind() == MetadataKind.ENTRY_COMMENT ? 1 : 0);
        @Nullable Group group = groups.get(key);
        return model().decode(context.bytes(), group == null ? null : group.prior(context.bytes())).text();
    }

    @Override
    public String decode(@UnmodifiableView ByteBuffer bytes) {
        return ZipMetadataModel.ascii(bytes) ? asciiText(bytes) : model().decode(bytes, null).text();
    }

    /// Converts known ASCII bytes without loading model data or optional charset providers.
    private static String asciiText(ByteBuffer bytes) {
        return java.nio.charset.StandardCharsets.ISO_8859_1.decode(bytes.duplicate()).toString();
    }

    /// Samples the complete central directory before freezing evidence; does not change or retain the buffer.
    /// @param centralDirectory complete central-directory records, excluding end records
    /// @return an independent decoder with immutable archive evidence
    /// @throws IOException if records or authoritative Unicode fields are malformed
    public ZipLearnedMetadataDecoder prepare(ByteBuffer centralDirectory) throws IOException {
        ByteBuffer buffer = centralDirectory.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        Sampler names = new Sampler(buffer);
        Sampler comments = new Sampler(buffer);
        ZipMetadataModel.Workspace workspace = new ZipMetadataModel.Workspace(MAX_FIELD);
        while (buffer.hasRemaining()) {
            int offset = buffer.position();
            if (buffer.remaining() < 46 || buffer.getInt(offset) != ZipConstants.CENTRAL_DIRECTORY_HEADER_SIGNATURE) {
                throw new IOException("Invalid ZIP central directory header");
            }
            int nameLength = Short.toUnsignedInt(buffer.getShort(offset + 28));
            int extraLength = Short.toUnsignedInt(buffer.getShort(offset + 30));
            int commentLength = Short.toUnsignedInt(buffer.getShort(offset + 32));
            int variable = nameLength + extraLength + commentLength;
            if (variable > buffer.remaining() - 46) throw new IOException("Invalid ZIP central directory variable data length");
            int start = offset + 46;
            buffer.position(start + variable);
            if ((buffer.getShort(offset + 8) & ZipEntryNameDecoder.UTF_8_FLAG) != 0) continue;
            int creator = Short.toUnsignedInt(buffer.getShort(offset + 4)) >>> 8;
            names.accept(start, nameLength, creator, start + nameLength, extraLength, true, workspace);
            comments.accept(start + nameLength + extraLength, commentLength, creator,
                    start + nameLength, extraLength, false, workspace);
        }
        HashMap<Integer, Evidence> evidence = new HashMap<>();
        HashMap<Integer, Map<ByteBuffer, Evidence>> parents = new HashMap<>();
        int[] parentBudget = {MAX_PARENTS, MAX_PARENT_BYTES};
        collect(names, false, workspace, evidence, parents, parentBudget);
        collect(comments, true, workspace, evidence, parents, parentBudget);
        HashMap<Integer, Group> result = new HashMap<>();
        evidence.forEach((key, value) -> {
            HashMap<ByteBuffer, float[]> local = new HashMap<>();
            parents.getOrDefault(key, Map.of()).forEach((prefix, scores) -> local.put(prefix, scores.freeze(model().parameters().archiveStrength())));
            result.put(key, new Group(value.freeze(model().parameters().archiveStrength()), Map.copyOf(local)));
        });
        return new ZipLearnedMetadataDecoder(model, result);
    }

    /// Aggregates selected references in canonical order, independently of central-directory entry order.
    private void collect(Sampler sampler, boolean comment, ZipMetadataModel.Workspace workspace,
                         Map<Integer, Evidence> groups, Map<Integer, Map<ByteBuffer, Evidence>> parents, int[] budget) {
        int copied = 0;
        for (Sample sample : sampler.samples.values()) {
            // Keep a prefix of the hash order: greedily skipping oversized samples would bias variable-length data.
            if (sample.length > BYTES_PER_KIND - copied) break;
            copied += sample.length;
            ByteBuffer bytes = sampler.buffer.slice(sample.offset, sample.length);
            ZipMetadataModel.Features features = ZipMetadataModel.extract(bytes);
            float[] vector = new float[ZipMetadataModel.ENCODINGS.size()];
            model().score(features, vector);
            long valid = workspace.matching(bytes, null);
            float maximum = Float.NEGATIVE_INFINITY;
            for (int label = 0; label < vector.length; label++) {
                if ((valid & (1L << label)) == 0) vector[label] = Float.NEGATIVE_INFINITY;
                else if ((sample.anchorMask & (1L << label)) != 0) vector[label] += 2;
                maximum = Math.max(maximum, vector[label]);
            }
            for (int label = 0; label < vector.length; label++) vector[label] = Math.max(-4, vector[label] - maximum);
            int key = sample.creator * 2 + (comment ? 1 : 0);
            groups.computeIfAbsent(key, ignored -> new Evidence()).add(features.fingerprint(), vector);
            if (comment) continue;
            Map<ByteBuffer, Evidence> directory = parents.computeIfAbsent(key, ignored -> new HashMap<>());
            boolean nonAscii = false;
            for (int index = 0; index < sample.length; index++) {
                int value = bytes.get(index) & 0xff;
                nonAscii |= value >= 128;
                // Backslash can be a trail byte and is deliberately not a raw-byte delimiter.
                if (value != '/' || !nonAscii) continue;
                ByteBuffer prefix = bytes.slice(0, index + 1);
                @Nullable Evidence parent = directory.get(prefix);
                if (parent == null && budget[0] > 0 && prefix.remaining() <= budget[1]) {
                    byte[] owned = new byte[prefix.remaining()];
                    prefix.get(owned);
                    budget[0]--;
                    budget[1] -= owned.length;
                    parent = new Evidence();
                    directory.put(ByteBuffer.wrap(owned).asReadOnlyBuffer(), parent);
                }
                if (parent != null) parent.add(features.fingerprint(), vector);
            }
        }
    }

    /// Maintains the smallest stable hashes using only short-lived references into the central directory.
    @NotNullByDefault
    private static final class Sampler {
        /// Borrowed storage, used only during preparation and never included in the returned decoder.
        private final ByteBuffer buffer;

        /// References ordered by hash, creator, and exact bytes; offsets never decide a tie.
        private final TreeMap<Sample, Sample> samples;

        /// Creates a sampler over one shared central-directory view.
        private Sampler(ByteBuffer buffer) {
            this.buffer = buffer;
            samples = new TreeMap<>((first, second) -> {
                int result = Long.compareUnsigned(first.hash, second.hash);
                if (result == 0) result = Integer.compare(first.creator, second.creator);
                if (result == 0) result = buffer.slice(first.offset, first.length).compareTo(buffer.slice(second.offset, second.length));
                return result;
            });
        }

        /// Adds a non-ASCII field if its stable rank is retained; duplicate Unicode evidence is combined symmetrically.
        private void accept(int offset, int length, int creator, int extraOffset, int extraLength,
                            boolean name, ZipMetadataModel.Workspace workspace) throws IOException {
            if (length == 0 || length > MAX_FIELD) return;
            ByteBuffer bytes = buffer.slice(offset, length);
            if (ZipMetadataModel.ascii(bytes)) return;
            long hash = 0xcbf29ce484222325L ^ creator;
            for (int index = 0; index < length; index++) hash = (hash ^ (bytes.get(index) & 0xff)) * 0x100000001b3L;
            Sample sample = new Sample(offset, length, creator, hash);
            @Nullable Sample retained = samples.get(sample);
            if (retained == null) {
                if (samples.size() == SAMPLES_PER_KIND && samples.comparator().compare(sample, samples.lastKey()) >= 0) return;
                samples.put(sample, sample);
                if (samples.size() > SAMPLES_PER_KIND) samples.pollLastEntry();
                retained = sample;
            }
            if (extraLength != 0) {
                byte[] raw = new byte[length];
                buffer.get(offset, raw);
                byte[] extra = new byte[extraLength];
                buffer.get(extraOffset, extra);
                @Nullable String unicode = ZipEntryNameDecoder.decodeUnicodeExtraField(raw, extra,
                        name ? ZipEntryNameDecoder.UNICODE_PATH_EXTRA_FIELD_ID : ZipEntryNameDecoder.UNICODE_COMMENT_EXTRA_FIELD_ID);
                if (unicode != null) retained.anchorMask |= workspace.matching(bytes, unicode);
            }
        }
    }

    /// Retains raw field coordinates and matching Unicode candidates only during sampling.
    @NotNullByDefault
    private static final class Sample {
        /// Offset into the borrowed central directory.
        private final int offset;
        /// Complete raw field length.
        private final int length;
        /// Creator-system group identifier, not a language label.
        private final int creator;
        /// Content rank independent of physical entry order.
        private final long hash;
        /// Union of exact Unicode counterpart matches; conflicts cannot eliminate unrelated candidates.
        private long anchorMask;

        /// Creates a reference without copying the field.
        private Sample(int offset, int length, int creator, long hash) {
            this.offset = offset;
            this.length = length;
            this.creator = creator;
            this.hash = hash;
        }
    }

    /// Accumulates bounded score vectors, counting each non-ASCII feature fingerprint at most once.
    @NotNullByDefault
    private static final class Evidence {
        /// Sum in canonical sample order; no mutable vectors escape preparation.
        private final float[] sum = new float[ZipMetadataModel.ENCODINGS.size()];
        /// Distinct non-ASCII evidence, independent of duplicate counts and most ASCII suffix changes.
        private long[] fingerprints = new long[8];

        /// Number of occupied primitive signature slots; bounded by the selected field count.
        private int count;

        /// Adds a vector only for a distinct signature.
        private void add(long fingerprint, float[] scores) {
            for (int index = 0; index < count; index++) if (fingerprints[index] == fingerprint) return;
            if (count == fingerprints.length) fingerprints = Arrays.copyOf(fingerprints, count * 2);
            fingerprints[count++] = fingerprint;
            for (int label = 0; label < sum.length; label++) sum[label] += scores[label];
        }

        /// Produces bounded relative evidence, with limited influence from very small sample sets.
        private float[] freeze(float strength) {
            float[] result = new float[sum.length];
            float maximum = Float.NEGATIVE_INFINITY;
            for (float value : sum) maximum = Math.max(maximum, value);
            float scale = strength * Math.min(1f, count / 4f) / (4 * Math.max(1, count));
            for (int label = 0; label < result.length; label++) result[label] = (sum[label] - maximum) * scale;
            return result;
        }
    }

    /// Holds private immutable archive and directory vectors for one creator and metadata kind.
    /// @param archive capped archive-wide prior
    /// @param parents private read-only prefix keys and capped directory priors
    @NotNullByDefault
    private record Group(float @Unmodifiable [] archive,
                         @Unmodifiable Map<@UnmodifiableView ByteBuffer, float @Unmodifiable []> parents) {
        /// Blends the longest matching directory's evidence with the archive prior, keeping the original cap.
        private float[] prior(ByteBuffer bytes) {
            for (int index = bytes.limit() - 1; index >= bytes.position(); index--) {
                if (bytes.get(index) != '/') continue;
                @Nullable float[] local = parents.get(bytes.slice(bytes.position(), index - bytes.position() + 1));
                if (local == null) continue;
                float[] result = Arrays.copyOf(archive, archive.length);
                for (int label = 0; label < result.length; label++) result[label] = result[label] * 0.25f + local[label] * 0.75f;
                return result;
            }
            return archive;
        }
    }
}
