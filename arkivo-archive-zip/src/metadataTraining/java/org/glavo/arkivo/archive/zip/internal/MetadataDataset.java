// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/// Produces deterministic encoding examples with source-level separation between all data partitions.
@NotNullByDefault
public final class MetadataDataset {
    /// Fixed language coverage; missing inputs are an error rather than a silently smaller training set.
    public static final @Unmodifiable List<String> LANGUAGES = List.of(
            "zh", "ja", "ko", "de", "fr", "es", "pl", "cs", "ru", "uk", "el", "tr", "he", "ar", "th", "vi", "lv");

    /// The immutable upstream snapshot date.
    public static final String SNAPSHOT = "20261001";

    /// Creates no instances.
    private MetadataDataset() {
    }

    /// Loads a bounded, hash-selected set of titles per language and encodes their filename variants.
    public static List<Example> load(Path downloads, int titlesPerLanguage) throws IOException {
        if (titlesPerLanguage < 1) throw new IllegalArgumentException("Nonpositive title limit");
        TreeMap<String, String> titles = new TreeMap<>();
        Comparator<String> order = Comparator.comparingLong(MetadataDataset::hash).thenComparing(Comparator.naturalOrder());
        for (String language : LANGUAGES) {
            TreeSet<String> sample = new TreeSet<>(order);
            Path file = downloads.resolve(language + "wiki-" + SNAPSHOT + "-all-titles-in-ns0.gz");
            try (var input = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8.newDecoder()))) {
                for (@Nullable String title; (title = input.readLine()) != null;) {
                    if (title.length() > 80 || title.isEmpty() || title.codePoints().noneMatch(ch -> ch >= 128)) continue;
                    title = title.replace('_', ' ');
                    if (title.codePoints().anyMatch(ch -> Character.isISOControl(ch) || ch == '/' || ch == '\\')) continue;
                    if (sample.size() < titlesPerLanguage || order.compare(title, sample.last()) < 0) {
                        sample.add(title);
                        if (sample.size() > titlesPerLanguage) sample.pollLast();
                    }
                }
            }
            for (String title : sample) titles.putIfAbsent(title, language);
            System.out.println("Sampled " + sample.size() + " titles for " + language);
        }
        List<Example> result = new ArrayList<>();
        ZipMetadataModel.Workspace workspace = new ZipMetadataModel.Workspace(1024);
        for (var entry : titles.entrySet()) {
            String title = entry.getKey();
            long source = hash(title);
            int partition = (int) Long.remainderUnsigned(source, 100);
            partition = partition < 80 ? 0 : partition < 90 ? 1 : 2;
            int firstNonAscii = 0;
            while (firstNonAscii < title.length() && title.charAt(firstNonAscii) < 128) firstNonAscii++;
            int points = title.codePointCount(firstNonAscii, title.length());
            int count = Math.min(points, 1 + (int) Long.remainderUnsigned(source >>> 8, 4));
            String shortTitle = title.substring(firstNonAscii, title.offsetByCodePoints(firstNonAscii, count));
            for (String text : List.of(title, shortTitle + ".txt", "data/" + title + "001.jpg")) {
                for (int label = 0; label < ZipMetadataModel.ENCODINGS.size(); label++) {
                    String name = ZipMetadataModel.ENCODINGS.get(label);
                    if (!Charset.isSupported(name)) throw new IOException("Training runtime lacks " + name);
                    Charset charset = Charset.forName(name);
                    try {
                        ByteBuffer encoded = charset.newEncoder().encode(CharBuffer.wrap(text));
                        // Some encoders use non-round-tripping compatibility mappings.
                        if (!charset.newDecoder().decode(encoded.duplicate()).toString().equals(text)) continue;
                        byte[] raw = new byte[encoded.remaining()];
                        encoded.get(raw);
                        if (ZipMetadataModel.ascii(ByteBuffer.wrap(raw))) continue;
                        long correct = workspace.matching(ByteBuffer.wrap(raw), text);
                        long valid = workspace.matching(ByteBuffer.wrap(raw), null);
                        result.add(new Example(source, entry.getValue(), partition, text, raw, label, correct, valid,
                                ZipMetadataModel.extract(ByteBuffer.wrap(raw))));
                    } catch (CharacterCodingException ignored) {
                        // A title is never replacement-encoded to manufacture a training label.
                    }
                }
            }
        }
        return result;
    }

    /// Hashes UTF-16 code units without consulting the locale or the platform default charset.
    public static long hash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) hash = (hash ^ value.charAt(index)) * 0x100000001b3L;
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        return hash ^ hash >>> 33;
    }

    /// Holds one strictly round-tripping filename example; all derived variants share a source and partition.
    /// @param source stable original-title identifier
    /// @param language the first language source containing the title in the fixed source order
    /// @param partition zero for training, one for calibration, two for final evaluation
    /// @param text the independently known decoded text
    /// @param bytes privately owned encoded bytes
    /// @param label the generating encoding, used for stratified reporting
    /// @param correct candidate mask producing exactly the expected text
    /// @param valid candidate mask accepting every input byte
    /// @param features bounded runtime-compatible features
    @NotNullByDefault
    public record Example(long source, String language, int partition, String text, byte @Unmodifiable [] bytes,
                          int label, long correct, long valid, ZipMetadataModel.Features features) {
    }
}
