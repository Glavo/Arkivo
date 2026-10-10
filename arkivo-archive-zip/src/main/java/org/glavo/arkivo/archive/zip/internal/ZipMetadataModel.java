// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.Arrays;
import java.util.List;

/// Scores complete, independent metadata fields with an immutable quantized linear model.
/// This internal model does not override ZIP Unicode declarations or validate archive paths.
@NotNullByDefault
public final class ZipMetadataModel {
    /// The number of hashed feature coordinates; the hash algorithm is part of model version 1.
    public static final int FEATURES = 65_536;

    /// The stable training-label order, independent of optional runtime charset availability.
    public static final @Unmodifiable List<String> ENCODINGS = List.of(
            "UTF-8", "GB18030", "Big5", "Big5-HKSCS", "windows-31j", "EUC-JP", "x-windows-949",
            "IBM437", "IBM850", "IBM852", "IBM866", "windows-1250", "windows-1251", "windows-1252",
            "windows-1253", "windows-1254", "windows-1255", "windows-1256", "windows-1257", "windows-1258",
            "KOI8-R", "KOI8-U", "x-MacRoman", "x-windows-874", "ISO-8859-1", "ISO-8859-2",
            "ISO-8859-5", "ISO-8859-7", "ISO-8859-9", "ISO-8859-15");

    /// The deterministic fallback label.
    public static final int CP437 = 7;

    /// The largest number of starting positions used for statistical scoring, not validity checks.
    private static final int SAMPLE_POSITIONS = 256;

    /// The fixed point multiplier shared by the writer and reader.
    private static final float SCALE = 512;

    /// Class-major fixed-point coefficients, privately owned and never changed after construction.
    private final short @Unmodifiable [] weights;

    /// Class intercepts, separately retained to avoid accumulating quantization errors.
    private final float @Unmodifiable [] biases;

    /// Decision parameters selected using calibration data rather than the final test set.
    private final Parameters parameters;

    /// Validates and privately copies a model's parameters.
    /// @param weights class-major fixed-point coefficients, scaled by 512
    /// @param biases one finite intercept per encoding
    /// @param parameters calibrated decision bounds
    /// @throws IllegalArgumentException if dimensions or intercepts are invalid
    public ZipMetadataModel(short[] weights, float[] biases, Parameters parameters) {
        if (weights.length != FEATURES * ENCODINGS.size() || biases.length != ENCODINGS.size()) {
            throw new IllegalArgumentException("Invalid metadata model dimensions");
        }
        for (float bias : biases) {
            if (!Float.isFinite(bias)) throw new IllegalArgumentException("Non-finite model intercept");
        }
        this.weights = weights.clone();
        this.biases = biases.clone();
        this.parameters = java.util.Objects.requireNonNull(parameters);
    }

    /// Returns the calibrated decision parameters.
    /// @return immutable decision parameters
    public Parameters parameters() {
        return parameters;
    }

    /// Returns the immutable coefficient payload's byte count, excluding object headers.
    /// @return bytes occupied by coefficients and intercepts
    public int weightBytes() {
        return weights.length * Short.BYTES + biases.length * Float.BYTES;
    }

    /// Returns the bundled immutable model, loading its coefficients only on the first call.
    /// @return the shared model
    /// @throws IllegalStateException if its resource is missing or malformed
    public static ZipMetadataModel bundled() {
        return Bundled.MODEL;
    }

    /// Reads a compact build-generated table and rejects truncation, wrong labels, and trailing data.
    /// Does not close the supplied stream.
    /// @param stream complete binary table
    /// @return the validated model
    /// @throws IOException if the input is malformed or cannot be read
    public static ZipMetadataModel readBinary(InputStream stream) throws IOException {
        DataInputStream input = new DataInputStream(stream);
        if (input.readInt() != 0x415a4d31) throw new IOException("Unknown binary metadata model version");
        try {
            Parameters parameters = new Parameters(input.readFloat(), input.readFloat(), input.readFloat());
            float[] biases = new float[ENCODINGS.size()];
            for (int label = 0; label < biases.length; label++) {
                if (!input.readUTF().equals(ENCODINGS.get(label))) throw new IOException("Invalid binary metadata label order");
                biases[label] = input.readFloat();
            }
            short[] weights = new short[FEATURES * ENCODINGS.size()];
            for (int index = 0; index < weights.length; index++) weights[index] = input.readShort();
            if (input.read() != -1) throw new IOException("Trailing binary metadata model data");
            return new ZipMetadataModel(weights, biases, parameters);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid binary metadata model", exception);
        }
    }

    /// Returns a model sharing the same values but using another calibrated decision policy.
    /// @param value replacement decision parameters
    /// @return an independent immutable model with identical coefficients
    public ZipMetadataModel withParameters(Parameters value) {
        return new ZipMetadataModel(weights, biases, value);
    }

    /// Quantizes finite floating-point coefficients, rejecting overflow instead of silently clipping it.
    /// @param weights class-major unquantized coefficients
    /// @param biases finite intercepts
    /// @param parameters decision parameters
    /// @return a fixed-point model
    /// @throws IllegalArgumentException if dimensions or coefficients are invalid
    public static ZipMetadataModel quantize(float[] weights, float[] biases, Parameters parameters) {
        short[] quantized = new short[weights.length];
        for (int index = 0; index < weights.length; index++) {
            float value = weights[index] * SCALE;
            if (!Float.isFinite(value) || value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Metadata coefficient exceeds fixed-point range at " + index + ": " + weights[index]);
            }
            quantized[index] = (short) StrictMath.round(value);
        }
        return new ZipMetadataModel(quantized, biases, parameters);
    }

    /// Extracts bounded features without consuming or retaining input; windows never cross field boundaries.
    /// @param bytes one complete metadata field
    /// @return indices and a deduplication fingerprint
    public static Features extract(ByteBuffer bytes) {
        int length = bytes.remaining();
        int positions = Math.min(length, SAMPLE_POSITIONS);
        int[] slots = new int[positions * 4 + 8];
        int[] signature = new int[positions * 4];
        int size = 0;
        int signatures = 0;
        for (int sample = 0; sample < positions; sample++) {
            int offset = length <= SAMPLE_POSITIONS || sample < SAMPLE_POSITIONS / 2
                    ? sample : length - SAMPLE_POSITIONS + sample;
            int hash = 0x811c9dc5;
            boolean nonAscii = false;
            boolean entirelyNonAscii = true;
            for (int width = 1; width <= 4 && offset + width <= length; width++) {
                int value = bytes.get(bytes.position() + offset + width - 1) & 0xff;
                hash = (hash ^ value) * 0x01000193;
                nonAscii |= value >= 128;
                entirelyNonAscii &= value >= 128;
                if (nonAscii) slots[size++] = feature(hash, width);
                if (entirelyNonAscii) signature[signatures++] = feature(hash, width);
            }
        }
        // Mark actual field boundaries, not the artificial ends of the scoring sample.
        for (int side = 0; side < 2; side++) {
            int hash = side == 0 ? 0x24b19da3 : 0x53df39c7;
            boolean nonAscii = false;
            for (int width = 1; width <= 4 && width <= length; width++) {
                int offset = side == 0 ? width - 1 : length - width;
                int value = bytes.get(bytes.position() + offset) & 0xff;
                hash = (hash ^ value) * 0x01000193;
                nonAscii |= value >= 128;
                if (nonAscii) slots[size++] = feature(hash, width + 4);
            }
        }
        Arrays.sort(slots, 0, size);
        int unique = 0;
        int previous = -1;
        int count = 0;
        for (int index = 0; index < size; index++) {
            int slot = slots[index];
            count = slot == previous ? count + 1 : 1;
            previous = slot;
            if (count <= 2) slots[unique++] = slot;
        }
        Arrays.sort(signature, 0, signatures);
        long fingerprint = 0xcbf29ce484222325L;
        previous = -1;
        for (int index = 0; index < signatures; index++) {
            int slot = signature[index];
            if (slot != previous) fingerprint = (fingerprint ^ slot) * 0x100000001b3L;
            previous = slot;
        }
        return new Features(Arrays.copyOf(slots, unique), fingerprint);
    }

    /// Mixes the sequence hash and feature width into a stable coordinate.
    private static int feature(int hash, int width) {
        hash ^= width * 0x9e3779b9;
        hash ^= hash >>> 16;
        hash *= 0x85ebca6b;
        return (hash ^ hash >>> 13) & (FEATURES - 1);
    }

    /// Writes all class scores into caller-owned storage; ASCII alone contributes no evidence.
    /// @param features bounded feature indices
    /// @param scores output array with one element per encoding
    /// @throws IllegalArgumentException if the output dimension is incorrect
    public void score(Features features, float[] scores) {
        if (scores.length != ENCODINGS.size()) throw new IllegalArgumentException("Invalid score vector");
        float factor = features.indices.length == 0 ? 0 : 1f / (float) StrictMath.sqrt(features.indices.length);
        for (int label = 0; label < scores.length; label++) {
            int sum = 0;
            int base = label * FEATURES;
            for (int slot : features.indices) sum += weights[base + slot];
            scores[label] = biases[label] + sum * factor / SCALE;
        }
        scores[0] += parameters.utf8Prior;
    }

    /// Selects a strictly decodable output; only the winning output is materialized as a string.
    /// Neither consumes nor retains the supplied bytes or prior.
    /// @param bytes complete metadata bytes
    /// @param prior bounded archive evidence, or {@code null} for independent decoding
    /// @return selected text and internal decision metadata
    /// @throws IllegalArgumentException if the prior dimension is incorrect
    public Decision decode(ByteBuffer bytes, float @Nullable [] prior) {
        if (ascii(bytes)) return new Decision(java.nio.charset.StandardCharsets.ISO_8859_1.decode(bytes.duplicate()).toString(), CP437, false);
        Features features = extract(bytes);
        float[] scores = new float[ENCODINGS.size()];
        score(features, scores);
        if (prior != null) {
            if (prior.length != scores.length) throw new IllegalArgumentException("Invalid prior vector");
            for (int index = 0; index < scores.length; index++) scores[index] += prior[index];
        }
        Workspace workspace = new Workspace(bytes.remaining());
        char[] bestText = new char[bytes.remaining()];
        int bestLength = 0;
        int best = -1;
        float bestScore = 0;
        float runnerUp = Float.NEGATIVE_INFINITY;
        for (int attempt = 0; attempt < scores.length; attempt++) {
            int label = -1;
            for (int index = 0; index < scores.length; index++) {
                if (scores[index] != Float.NEGATIVE_INFINITY && (label < 0 || scores[index] > scores[label])) label = index;
            }
            if (label < 0) break;
            float score = scores[label];
            scores[label] = Float.NEGATIVE_INFINITY;
            if (!workspace.decode(bytes, label)) continue;
            if (best < 0) {
                best = label;
                bestScore = score;
                bestLength = workspace.output.position();
                System.arraycopy(workspace.output.array(), 0, bestText, 0, bestLength);
            } else if (workspace.output.position() != bestLength
                    || !Arrays.equals(bestText, 0, bestLength, workspace.output.array(), 0, bestLength)) {
                runnerUp = score;
                break;
            }
        }
        if (best >= 0 && features.indices.length != 0 && bestScore - runnerUp > parameters.minimumMargin) {
            return new Decision(new String(bestText, 0, bestLength), best, false);
        }
        return new Decision(Charsets.VALUES[CP437].decode(bytes.duplicate()).toString(), CP437, true);
    }

    /// Returns whether every remaining byte is ASCII without changing buffer state.
    /// @param bytes bytes to inspect
    /// @return {@code true} if all bytes are nonnegative, including empty input
    public static boolean ascii(ByteBuffer bytes) {
        for (int index = bytes.position(); index < bytes.limit(); index++) if (bytes.get(index) < 0) return false;
        return true;
    }

    /// Reads the versioned sparse text representation; duplicate coordinates and malformed values are rejected.
    /// Does not close the supplied reader.
    /// @param reader complete text model
    /// @return the validated model
    /// @throws IOException if the model is malformed or cannot be read
    public static ZipMetadataModel read(Reader reader) throws IOException {
        BufferedReader input = reader instanceof BufferedReader buffered ? buffered : new BufferedReader(reader);
        if (!"arkivo-zip-metadata-model\t1".equals(input.readLine())) throw new IOException("Unknown metadata model version");
        @Nullable String header = input.readLine();
        if (header == null) throw new IOException("Missing metadata model parameters");
        try {
            String[] values = header.split("\t", -1);
            if (values.length != 3) throw new IOException("Invalid metadata model parameters");
            Parameters parameters = new Parameters(Float.parseFloat(values[0]), Float.parseFloat(values[1]), Float.parseFloat(values[2]));
            short[] weights = new short[FEATURES * ENCODINGS.size()];
            float[] biases = new float[ENCODINGS.size()];
            for (int label = 0; label < ENCODINGS.size(); label++) {
                @Nullable String row = input.readLine();
                if (row == null) throw new IOException("Missing metadata model intercept");
                String[] parts = row.split("\t", -1);
                if (parts.length != 2 || !parts[0].equals(ENCODINGS.get(label))) throw new IOException("Invalid metadata model label order");
                biases[label] = Float.parseFloat(parts[1]);
            }
            int previous = -1;
            for (@Nullable String row; (row = input.readLine()) != null;) {
                String[] parts = row.split("\t", -1);
                if (parts.length != 2) throw new IOException("Invalid metadata model coefficient");
                int index = Integer.parseInt(parts[0]);
                if (index <= previous || index >= weights.length) throw new IOException("Invalid metadata model coordinate order");
                weights[index] = Short.parseShort(parts[1]);
                previous = index;
            }
            return new ZipMetadataModel(weights, biases, parameters);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid metadata model", exception);
        }
    }

    /// Writes a canonical, locale-independent text model without closing the supplied writer.
    /// @param writer destination for the model
    /// @throws IOException if writing fails
    public void write(Writer writer) throws IOException {
        writer.write("arkivo-zip-metadata-model\t1\n" + parameters.utf8Prior + "\t" + parameters.minimumMargin + "\t" + parameters.archiveStrength + "\n");
        for (int label = 0; label < ENCODINGS.size(); label++) writer.write(ENCODINGS.get(label) + "\t" + biases[label] + "\n");
        for (int index = 0; index < weights.length; index++) if (weights[index] != 0) writer.write(index + "\t" + weights[index] + "\n");
    }

    /// Holds immutable feature indices, including at most two occurrences of each coordinate.
    /// @param indices privately owned indices; must not be modified by consumers
    /// @param fingerprint signature excluding ASCII bytes and repeated features, used only to reduce evidence weight
    @NotNullByDefault
    public record Features(int @Unmodifiable [] indices, long fingerprint) {
    }

    /// Defines calibrated offsets and the minimum score separation between distinct decoded texts.
    /// @param utf8Prior additional UTF-8 score when its complete bytes are valid
    /// @param minimumMargin nonnegative rejection margin, not a probability
    /// @param archiveStrength nonnegative maximum magnitude of archive evidence
    @NotNullByDefault
    public record Parameters(float utf8Prior, float minimumMargin, float archiveStrength) {
        /// Rejects non-finite parameters and negative evidence bounds.
        public Parameters {
            if (!Float.isFinite(utf8Prior) || !Float.isFinite(minimumMargin) || !Float.isFinite(archiveStrength)
                    || minimumMargin < 0 || archiveStrength < 0) throw new IllegalArgumentException("Invalid decision parameters");
        }
    }

    /// Describes an internal decision without presenting a score as a calibrated probability.
    /// @param text the complete decoded metadata
    /// @param label the selected charset index
    /// @param fallback whether insufficient evidence selected CP437
    @NotNullByDefault
    public record Decision(String text, int label, boolean fallback) {
    }

    /// Reuses character storage across strict candidate checks within one operation; not thread-safe.
    @NotNullByDefault
    public static final class Workspace {
        /// Reusable output for one complete field, including bytes outside the statistical sample.
        private final CharBuffer output;

        /// Decoders are local to this workspace; no mutable state is shared between readers.
        private final @Nullable CharsetDecoder[] decoders = new CharsetDecoder[ENCODINGS.size()];

        /// Creates sufficient character storage for the supplied maximum byte length.
        /// @param maximumBytes largest field to be tested with this workspace
        public Workspace(int maximumBytes) {
            output = CharBuffer.allocate(maximumBytes);
        }

        /// Strictly decodes a complete value without creating a string or retaining the supplied bytes.
        /// @param bytes complete field, no larger than the workspace capacity
        /// @param label index in [#ENCODINGS]
        /// @return whether the charset exists and decodes the complete field within the workspace
        public boolean decode(ByteBuffer bytes, int label) {
            @Nullable Charset charset = Charsets.VALUES[label];
            if (charset == null) return false;
            @Nullable CharsetDecoder decoder = decoders[label];
            if (decoder == null) decoders[label] = decoder = charset.newDecoder();
            decoder.reset();
            output.clear();
            return decoder.decode(bytes.duplicate(), output, true).isUnderflow() && decoder.flush(output).isUnderflow();
        }

        /// Returns available candidates that strictly decode the input, optionally requiring an exact text match.
        /// @param bytes complete field, no larger than the workspace capacity
        /// @param expected required decoded text, or {@code null} to accept any valid text
        /// @return a bit mask indexed by [#ENCODINGS]
        public long matching(ByteBuffer bytes, @Nullable String expected) {
            long mask = 0;
            for (int label = 0; label < ENCODINGS.size(); label++) {
                if (!decode(bytes, label)) continue;
                if (expected != null) {
                    if (expected.length() != output.position()) continue;
                    boolean equal = true;
                    for (int index = 0; index < expected.length(); index++) if (expected.charAt(index) != output.get(index)) { equal = false; break; }
                    if (!equal) continue;
                }
                mask |= 1L << label;
            }
            return mask;
        }
    }

    /// Resolves optional charset providers only when a non-ASCII operation needs them.
    @NotNullByDefault
    private static final class Charsets {
        /// Available strict decoders, preserving the model's label indices even in reduced runtime images.
        private static final @Nullable Charset @Unmodifiable [] VALUES = create();

        /// Loads only charsets supplied by the current runtime.
        private static @Nullable Charset[] create() {
            @Nullable Charset[] result = new Charset[ENCODINGS.size()];
            for (int index = 0; index < result.length; index++) {
                String name = ENCODINGS.get(index);
                if (Charset.isSupported(name)) result[index] = Charset.forName(name);
            }
            return result;
        }
    }

    /// Defers resource loading until non-ASCII model evaluation is explicitly requested.
    @NotNullByDefault
    private static final class Bundled {
        /// The model is immutable; no decoder, source buffer, or archive evidence is cached here.
        private static final ZipMetadataModel MODEL = load();

        /// Loads the packaged table without reading external files or contacting a service.
        private static ZipMetadataModel load() {
            @Nullable InputStream resource = ZipMetadataModel.class.getResourceAsStream("metadata-model.bin");
            if (resource == null) throw new IllegalStateException("Missing bundled ZIP metadata model");
            try (InputStream stream = new BufferedInputStream(resource)) {
                return readBinary(stream);
            } catch (IOException exception) {
                throw new IllegalStateException("Invalid bundled ZIP metadata model", exception);
            }
        }
    }
}
