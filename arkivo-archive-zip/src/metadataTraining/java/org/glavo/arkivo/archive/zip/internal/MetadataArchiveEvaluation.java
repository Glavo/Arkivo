// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.all.LibarchiveUuDecoder;
import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
import org.glavo.arkivo.archive.zip.ZipLegacyMetadataDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

/// Calibrates archive evidence independently of the final holdout and checks external ZIP fixtures.
@NotNullByDefault
public final class MetadataArchiveEvaluation {
    /// Creates no instances.
    private MetadataArchiveEvaluation() {
    }

    /// Chooses an evidence bound using calibration archives whose target sources are absent from their support sets.
    public static ZipMetadataModel calibrate(ZipMetadataModel model, List<MetadataDataset.Example> calibration) throws IOException {
        float selected = 0;
        double accuracy = -1;
        for (float strength : new float[]{0, 0.5f, 1, 2, 4}) {
            var parameters = model.parameters();
            ZipMetadataModel candidate = model.withParameters(new ZipMetadataModel.Parameters(parameters.utf8Prior(), parameters.minimumMargin(), strength));
            JointResult result = joint(candidate, calibration);
            System.out.println("Archive calibration strength=" + strength + " macroAccuracy=" + result.macroAccuracy());
            if (result.macroAccuracy() > accuracy) { accuracy = result.macroAccuracy(); selected = strength; }
        }
        return model.withParameters(new ZipMetadataModel.Parameters(model.parameters().utf8Prior(), model.parameters().minimumMargin(), selected));
    }

    /// Evaluates held-out homogeneous synthetic archives; each target is excluded from its support archive.
    public static JointResult joint(ZipMetadataModel model, List<MetadataDataset.Example> examples) throws IOException {
        TreeMap<String, List<MetadataDataset.Example>> groups = new TreeMap<>();
        for (var example : examples) groups.computeIfAbsent(example.label() + ":" + example.language(), ignored -> new ArrayList<>()).add(example);
        long[][] totals = new long[ZipMetadataModel.ENCODINGS.size()][2];
        long archives = 0;
        long complete = 0;
        long individual = 0;
        for (var group : groups.values()) {
            HashSet<Long> sources = new HashSet<>();
            List<MetadataDataset.Example> support = new ArrayList<>();
            List<MetadataDataset.Example> targets = new ArrayList<>();
            for (var example : group) {
                if (sources.contains(example.source())) continue;
                if (support.size() < 8) { sources.add(example.source()); support.add(example); }
                else targets.add(example);
            }
            if (targets.isEmpty()) continue;
            var decoder = new ZipLearnedMetadataDecoder(model).prepare(central(support));
            boolean all = true;
            for (var example : targets) {
                String decoded = decoder.decode(context(example.bytes()));
                boolean correct = decoded.equals(example.text());
                if (correct) totals[example.label()][0]++;
                totals[example.label()][1]++;
                if (model.decode(ByteBuffer.wrap(example.bytes()), null).text().equals(example.text())) individual++;
                all &= correct;
            }
            archives++;
            if (all) complete++;
        }
        double macro = 0;
        int present = 0;
        long count = 0;
        long correct = 0;
        for (long[] total : totals) if (total[1] != 0) {
            present++;
            macro += (double) total[0] / total[1];
            count += total[1];
            correct += total[0];
        }
        return new JointResult(present == 0 ? 0 : macro / present, correct, count, individual, complete, archives);
    }

    /// Evaluates heterogeneous groups without using known labels to select archive evidence.
    public static JointResult mixed(ZipMetadataModel model, List<MetadataDataset.Example> examples) throws IOException {
        List<MetadataDataset.Example> ordered = new ArrayList<>(examples);
        ordered.sort(java.util.Comparator.comparingLong(example -> MetadataDataset.hash(example.text() + ":" + example.label())));
        long correct = 0;
        long individual = 0;
        long complete = 0;
        long archives = 0;
        long[][] totals = new long[ZipMetadataModel.ENCODINGS.size()][2];
        for (int offset = 0; offset < ordered.size(); offset += 64) {
            List<MetadataDataset.Example> group = ordered.subList(offset, Math.min(ordered.size(), offset + 64));
            var decoder = new ZipLearnedMetadataDecoder(model).prepare(central(group));
            boolean all = true;
            for (var example : group) {
                boolean match = decoder.decode(context(example.bytes())).equals(example.text());
                if (match) {
                    correct++;
                    totals[example.label()][0]++;
                }
                totals[example.label()][1]++;
                if (model.decode(ByteBuffer.wrap(example.bytes()), null).text().equals(example.text())) individual++;
                all &= match;
            }
            if (all) complete++;
            archives++;
        }
        double macro = 0;
        int present = 0;
        for (long[] total : totals) if (total[1] != 0) {
            macro += (double) total[0] / total[1];
            present++;
        }
        return new JointResult(present == 0 ? 0 : macro / present, correct, ordered.size(), individual, complete, archives);
    }

    /// Encodes only the central-directory metadata required by the prepared decoder.
    private static ByteBuffer central(List<MetadataDataset.Example> examples) {
        int size = examples.stream().mapToInt(example -> 46 + example.bytes().length).sum();
        ByteBuffer result = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        for (var example : examples) {
            int start = result.position();
            result.putInt(ZipConstants.CENTRAL_DIRECTORY_HEADER_SIGNATURE);
            result.putShort(start + 4, (short) 20);
            result.putShort(start + 28, (short) example.bytes().length);
            result.position(start + 46).put(example.bytes());
        }
        return result.flip();
    }

    /// Supplies a non-Unicode DOS central-directory name without inventing a language hint.
    private static ZipLegacyMetadataDecoder.Context context(byte[] bytes) {
        return new ZipLegacyMetadataDecoder.Context(ByteBuffer.wrap(bytes), ZipLegacyMetadataDecoder.MetadataKind.ENTRY_NAME,
                ZipLegacyMetadataDecoder.HeaderSource.CENTRAL_DIRECTORY, 0, 20, 20, 0, ByteBuffer.allocate(0));
    }

    /// Evaluates already pinned real producer archives; missing fixtures fail rather than shrinking the denominator.
    public static boolean realArchives(Path project, Path report, ZipMetadataModel model) throws IOException {
        List<Fixture> fixtures = new ArrayList<>();
        for (String producer : List.of("7zip", "infozip", "osx", "winrar", "winzip")) {
            fixtures.add(new Fixture("go/1.25.0/src/archive/zip/testdata/utf8-" + producer + ".zip", "UTF-8"));
        }
        fixtures.add(new Fixture("zip4j/2.11.5/src/test/resources/test-archives/testfile_with_chinese_filename_by_7zip.zip", "GBK"));
        fixtures.add(new Fixture("jszip/3.10.1/test/ref/local_encoding_in_name.zip", "IBM866"));
        for (String[] variant : new String[][]{{"cp932", "windows-31j"}, {"cp866", "IBM866"},
                {"koi8r", "KOI8-R"}, {"utf8_jp", "UTF-8"}, {"utf8_ru", "UTF-8"}, {"utf8_ru2", "KOI8-R"}}) {
            fixtures.add(new Fixture("libarchive/3.8.7/fixtures/test_read_format_zip_filename_" + variant[0] + ".zip.uu", variant[1]));
        }
        boolean gate = true;
        try (var writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
            writer.write("fixture\tmethod\tmode\tcorrect\ttotal\tregressions\tstatus\n");
            for (Fixture fixture : fixtures) {
                Path file = project.resolve("build/test-data").resolve(fixture.path());
                if (file.toString().endsWith(".uu")) {
                    byte[] archive = LibarchiveUuDecoder.decode(file);
                    Path decoded = report.getParent().resolve("real-fixtures").resolve(file.getFileName().toString().replace(".uu", ""));
                    Files.createDirectories(decoded.getParent());
                    Files.write(decoded, archive);
                    file = decoded;
                }
                List<Expected> expected = new ArrayList<>();
                var known = ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(ArchiveMetadataDecoder.forCharset(Charset.forName(fixture.encoding())));
                try (var reader = ZipArkivoStreamingReader.open(file, known)) {
                    while (reader.next()) {
                        var attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                        expected.add(new Expected(attributes.path(), attributes.rawPath()));
                    }
                }
                boolean[][] baseline = new boolean[2][expected.size()];
                for (int method = 0; method < 2; method++) {
                    var decoder = method == 0 ? ZipAutoMetadataDecoder.DEFAULT : new ZipLearnedMetadataDecoder(model);
                    var options = ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(decoder);
                    int streaming = 0;
                    int indexed = 0;
                    boolean[][] outcomes = new boolean[2][expected.size()];
                    String streamStatus = "ok";
                    String indexStatus = "ok";
                    try (var reader = ZipArkivoStreamingReader.open(file, options)) {
                        int index = 0;
                        while (reader.next()) {
                            if (index >= expected.size()) throw new IOException("Unexpected entry count");
                            if (reader.readAttributes().path().equals(expected.get(index).path())) {
                                streaming++;
                                outcomes[0][index] = true;
                            }
                            index++;
                        }
                    } catch (IOException exception) { streamStatus = exception.getClass().getSimpleName(); }
                    try (var fs = ZipArkivoFileSystem.open(file, options)) {
                        for (int index = 0; index < expected.size(); index++) {
                            Expected entry = expected.get(index);
                            Path path = fs.getPath(entry.path());
                            if (Files.exists(path) && Arrays.equals(Files.readAttributes(path, ZipArkivoEntryAttributes.class).rawPath(), entry.raw())) {
                                indexed++;
                                outcomes[1][index] = true;
                            }
                        }
                    } catch (IOException exception) { indexStatus = exception.getClass().getSimpleName(); }
                    String name = method == 0 ? "baseline" : "learned";
                    int[] regressions = new int[2];
                    if (method == 0) baseline = outcomes;
                    else for (int mode = 0; mode < 2; mode++) {
                        for (int entry = 0; entry < expected.size(); entry++) {
                            if (baseline[mode][entry] && !outcomes[mode][entry]) regressions[mode]++;
                        }
                    }
                    writer.write(fixture.path() + "\t" + name + "\tstreaming\t" + streaming + "\t" + expected.size() + "\t" + regressions[0] + "\t" + streamStatus + "\n");
                    writer.write(fixture.path() + "\t" + name + "\tindexed\t" + indexed + "\t" + expected.size() + "\t" + regressions[1] + "\t" + indexStatus + "\n");
                    if (method == 1) gate &= regressions[0] == 0 && regressions[1] == 0;
                }
            }
        }
        return gate;
    }

    /// Summarizes held-out archive and per-field accuracy over the same target fields.
    /// @param macroAccuracy mean exact-text accuracy across represented encodings
    /// @param correct fields decoded correctly with joint evidence
    /// @param total target fields
    /// @param individualCorrect the same fields decoded without archive evidence
    /// @param completeArchives groups whose targets are all correct
    /// @param archives evaluated groups
    @NotNullByDefault
    public record JointResult(double macroAccuracy, long correct, long total, long individualCorrect,
                              long completeArchives, long archives) {
    }

    /// Names an independently versioned fixture and its upstream-specified fallback encoding.
    /// @param path path relative to the shared extracted corpus directory
    /// @param encoding known code page, not another detector's prediction
    @NotNullByDefault
    private record Fixture(String path, String encoding) {
    }

    /// Holds independently decoded names and original bytes for archive-level comparisons.
    /// @param path expected complete entry name
    /// @param raw original header bytes
    @NotNullByDefault
    private record Expected(String path, byte @Unmodifiable [] raw) {
    }
}
