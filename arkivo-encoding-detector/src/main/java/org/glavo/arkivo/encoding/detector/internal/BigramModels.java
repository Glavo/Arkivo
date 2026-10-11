// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/// Loads the lossless metadata projection of Kala's byte-bigram tables.
/// Full-model norms and all nonzero weights are retained. Name profiles omit ASCII-only coordinates;
/// comments also use those coordinates as continuous-text evidence.
@NotNullByDefault
final class BigramModels {
    /// Number of possible adjacent byte pairs.
    static final int DIMENSIONS = 1 << 16;
    /// Shared immutable tables, loaded when this class is initialized.
    static final BigramModels INSTANCE;

    static {
        try {
            INSTANCE = load();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /// Shared IDF multipliers.
    private final byte @Unmodifiable [] idf;
    /// Installed candidates and their immutable language variants.
    final @Unmodifiable Map<Charset, @Unmodifiable List<Model>> candidates;

    /// Takes ownership of fresh tables and freezes candidate collections.
    private BigramModels(byte[] idf, Map<Charset, List<Model>> candidates) {
        this.idf = idf;
        this.candidates = java.util.Collections.unmodifiableMap(new TreeMap<>(candidates));
    }

    /// Returns the unsigned IDF weight for a byte pair.
    int idf(int pair) {
        return idf[pair] & 255;
    }

    /// Reads the uncompressed AMD1 resource through a bounded input buffer.
    private static BigramModels load() throws IOException {
        @Nullable InputStream resource = BigramModels.class.getResourceAsStream("bigram-models.bin");
        if (resource == null) throw new IOException("Missing metadata model resource");
        try (var data = new DataInputStream(new BufferedInputStream(resource))) {
            if (data.readInt() != 0x414D4431) throw new IOException("Invalid metadata model header");
            byte[] idf = new byte[DIMENSIONS];
            data.readFully(idf);
            int count = data.readInt();
            if (count < 1 || count > 10000) throw new IOException("Invalid metadata model count");
            var result = new TreeMap<Charset, List<Model>>();
            for (int row = 0; row < count; row++) {
                String charset = data.readUTF();
                String name = data.readUTF();
                double norm = data.readDouble();
                int length = data.readInt();
                if (!Double.isFinite(norm) || norm < 0 || length < 0 || length > DIMENSIONS)
                    throw new IOException("Invalid metadata model dimensions");
                char[] keys = new char[length];
                byte[] weights = new byte[length];
                int pair = 0;
                for (int index = 0; index < length; index++) {
                    pair = Math.addExact(pair, data.readUnsignedShort());
                    if (pair >= DIMENSIONS
                            || index != 0 && pair <= keys[index - 1])
                        throw new IOException("Invalid metadata model coordinate");
                    keys[index] = (char) pair;
                }
                data.readFully(weights);
                if (Charset.isSupported(charset)) result.computeIfAbsent(Charset.forName(charset),
                        ignored -> new ArrayList<>()).add(new Model(name, keys, weights, norm));
            }
            if (data.read() != -1) throw new IOException("Trailing metadata model data");
            result.replaceAll((ignored, models) -> List.copyOf(models));
            return new BigramModels(idf, result);
        }
    }

    /// Stores nonzero byte-pair weights without changing the model scale.
    /// @param name upstream language/encoding identity
    /// @param keys sorted unsigned byte-pair indices
    /// @param weights corresponding unsigned quantized weights
    /// @param norm original full-model L2 norm
    @NotNullByDefault
    record Model(String name, char @Unmodifiable [] keys, byte @Unmodifiable [] weights, double norm) {
        /// Looks up a quantized weight without expanding the sparse table.
        int weight(int pair) {
            int index = Arrays.binarySearch(keys, (char) pair);
            return index < 0 ? 0 : weights[index] & 255;
        }

        /// Computes Kala's cosine similarity over the supplied sparse IDF-weighted profile.
        double score(char[] pairs, int[] frequencies, double inputNorm) {
            if (inputNorm == 0 || norm == 0) return 0;
            long dot = 0;
            for (int i = 0; i < pairs.length; i++) dot += (long) weight(pairs[i]) * frequencies[i];
            return dot / (norm * inputNorm);
        }
    }
}
