// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.InflaterInputStream;

/// Loads Kala's byte-bigram models into sparse, immutable tables for metadata experiments.
/// The cosine calculation follows Kala's ModelStore; ASCII-only coordinates are projected out
/// of both vectors, and model norms are recomputed for that projection. No weights are trained here.
@NotNullByDefault
final class KalaModels {
    /// Number of possible adjacent byte pairs.
    static final int DIMENSIONS = 1 << 16;
    /// Selected ASCII-compatible model identities and their JDK validation encodings.
    /// EUC-JP shares the byte layout of the modeled EUC-JIS-2004 subset, but validation remains EUC-JP.
    private static final @Unmodifiable Map<String, String> CHARSETS = Map.ofEntries(
            Map.entry("utf-8", "UTF-8"), Map.entry("gb18030", "GB18030"),
            Map.entry("big5hkscs", "Big5-HKSCS"), Map.entry("cp932", "windows-31j"),
            Map.entry("euc_jis_2004", "EUC-JP"), Map.entry("cp949", "x-windows-949"),
            Map.entry("cp437", "IBM437"), Map.entry("cp850", "IBM850"), Map.entry("cp852", "IBM852"),
            Map.entry("cp866", "IBM866"), Map.entry("cp1250", "windows-1250"),
            Map.entry("cp1251", "windows-1251"), Map.entry("cp1252", "windows-1252"),
            Map.entry("cp1253", "windows-1253"), Map.entry("cp1254", "windows-1254"),
            Map.entry("cp1255", "windows-1255"), Map.entry("cp1256", "windows-1256"),
            Map.entry("cp1257", "windows-1257"), Map.entry("cp1258", "windows-1258"),
            Map.entry("cp874", "x-windows-874"), Map.entry("koi8-r", "KOI8-R"),
            Map.entry("koi8-u", "KOI8-U"), Map.entry("mac-roman", "x-MacRoman"),
            Map.entry("iso8859-1", "ISO-8859-1"), Map.entry("iso8859-2", "ISO-8859-2"),
            Map.entry("iso8859-5", "ISO-8859-5"), Map.entry("iso8859-7", "ISO-8859-7"),
            Map.entry("iso8859-9", "ISO-8859-9"), Map.entry("iso8859-15", "ISO-8859-15"));
    /// Shared IDF multipliers, indexed by the original byte pair.
    private final byte @Unmodifiable [] idf;
    /// Language variants grouped by installed charset, in canonical charset-name order.
    final @Unmodifiable Map<Charset, @Unmodifiable List<Model>> candidates;

    /// Takes ownership of freshly loaded arrays and freezes candidate lists.
    private KalaModels(byte[] idf, Map<Charset, List<Model>> candidates) {
        this.idf = idf;
        this.candidates = java.util.Collections.unmodifiableMap(new TreeMap<>(candidates));
    }

    /// Returns lazily loaded shared tables; ASCII paths need not call this method.
    static KalaModels instance() {
        return Holder.VALUE;
    }

    /// Returns an unsigned inverse-document-frequency multiplier.
    int idf(int pair) {
        return idf[pair] & 255;
    }

    /// Counts retained primitive array payload, excluding object headers and loading workspaces.
    long payloadBytes() {
        long total = idf.length;
        for (var variants : candidates.values()) for (var model : variants) total += model.keys.length * 3L;
        return total;
    }

    /// Parses verified model resources, using one 64 KiB row instead of a dense 22 MiB expansion.
    private static KalaModels load() throws IOException {
        byte[] idf;
        try (var input = resource("idf.bin")) {
            idf = input.readNBytes(DIMENSIONS);
            if (idf.length != DIMENSIONS || input.read() != -1) throw new IOException("Invalid Kala IDF length");
        }
        try (var input = new DataInputStream(resource("models.bin"))) {
            if (input.readInt() != 0x434d4432) throw new IOException("Invalid Kala model magic");
            int count = input.readInt();
            if (count < 1 || count > 10000) throw new IOException("Invalid Kala model count");
            var headers = new ArrayList<Header>(count);
            for (int i = 0; i < count; i++) {
                int length = input.readInt();
                if (length < 1 || length > 256) throw new IOException("Invalid Kala model name length");
                byte[] bytes = new byte[length];
                input.readFully(bytes);
                String name = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                int slash = name.indexOf('/');
                if (slash < 1 || slash == name.length() - 1) throw new IOException("Invalid Kala model name");
                String key = name.substring(slash + 1);
                @Nullable String javaName = CHARSETS.get(key);
                @Nullable Charset charset = javaName != null && Charset.isSupported(javaName)
                        ? Charset.forName(javaName) : null;
                headers.add(new Header(name, charset));
                double norm = input.readDouble();
                if (!Double.isFinite(norm) || norm < 0) throw new IOException("Invalid Kala model norm");
            }
            var result = new TreeMap<Charset, List<Model>>();
            byte[] row = new byte[DIMENSIONS];
            try (var inflated = new DataInputStream(new InflaterInputStream(input))) {
                for (var header : headers) {
                    inflated.readFully(row);
                    if (header.charset == null) continue;
                    Model model = Model.sparse(header.name, row);
                    result.computeIfAbsent(header.charset, ignored -> new ArrayList<>()).add(model);
                }
                if (inflated.read() != -1) throw new IOException("Trailing Kala model rows");
            }
            result.replaceAll((ignored, value) -> List.copyOf(value));
            return new KalaModels(idf, result);
        }
    }

    /// Opens a generated test resource, failing visibly instead of silently changing the algorithm.
    private static InputStream resource(String name) throws IOException {
        @Nullable var input = KalaModels.class.getResourceAsStream("/kala/encdet/internal/" + name);
        if (input == null) throw new IOException("Missing Kala model resource: " + name);
        return input;
    }

    /// Tests whether a byte pair contains any encoding evidence beyond ASCII.
    static boolean informative(int pair) {
        return (pair & 0x8080) != 0;
    }

    /// Stores parsed header coordinates before streaming the model rows.
    /// @param name upstream language/encoding identity
    /// @param charset installed candidate, or null for excluded or unavailable encodings
    @NotNullByDefault
    private record Header(String name, @Nullable Charset charset) {
    }

    /// Stores nonzero projected weights for one language variant.
    /// @param name upstream language/encoding identity
    /// @param keys sorted unsigned byte-pair indices
    /// @param weights corresponding unsigned quantized weights
    /// @param norm L2 norm after removing ASCII-only pairs
    @NotNullByDefault
    record Model(String name, char @Unmodifiable [] keys, byte @Unmodifiable [] weights, double norm) {
        /// Copies only nonzero, non-ASCII coordinates and computes their exact projected norm.
        static Model sparse(String name, byte[] dense) {
            int count = 0;
            long squared = 0;
            for (int i = 0; i < DIMENSIONS; i++) {
                if (informative(i) && dense[i] != 0) {
                    count++;
                    int weight = dense[i] & 255;
                    squared += (long) weight * weight;
                }
            }
            char[] keys = new char[count];
            byte[] weights = new byte[count];
            int index = 0;
            for (int i = 0; i < DIMENSIONS; i++) {
                if (informative(i) && dense[i] != 0) {
                    keys[index] = (char) i;
                    weights[index++] = dense[i];
                }
            }
            return new Model(name, keys, weights, Math.sqrt(squared));
        }

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

    /// Defers model I/O until non-ASCII statistical evidence is actually requested.
    @NotNullByDefault
    private static final class Holder {
        /// Immutable model data shared across independent detector workspaces.
        private static final KalaModels VALUE;

        static {
            try {
                VALUE = load();
            } catch (IOException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        /// Prevents instantiation.
        private Holder() {
        }
    }
}
