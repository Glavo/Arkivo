// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Properties;
import java.util.HexFormat;
import java.security.MessageDigest;

/// Trains a reproducible sparse linear classifier and evaluates untouched source-level holdouts.
/// Running this tool writes only into its explicitly supplied build directory.
@NotNullByDefault
public final class MetadataTrainer {
    /// Fixed optimizer pass count; neither test accuracy nor fixture names control early stopping.
    private static final int EPOCHS = 8;

    /// The number of output labels, including equivalent encodings.
    private static final int CLASSES = ZipMetadataModel.ENCODINGS.size();

    /// Runtime build used to fix charset mappings and floating-point library behavior for this model version.
    private static final String TRAINING_RUNTIME = "25+37-LTS";

    /// Creates no instances.
    private MetadataTrainer() {
    }

    /// Trains candidate regularizations, calibrates only on partition one, and writes final holdout reports.
    public static void main(String[] arguments) throws Exception {
        if (!TRAINING_RUNTIME.equals(System.getProperty("java.runtime.version"))
                || !"BellSoft".equals(System.getProperty("java.vendor"))) {
            throw new IllegalStateException("Metadata training and evaluation require BellSoft JDK " + TRAINING_RUNTIME);
        }
        if (arguments.length < 1 || arguments.length > 2) throw new IllegalArgumentException("Expected build directory and optional title limit");
        Path directory = Path.of(arguments[0]);
        boolean evaluationOnly = arguments.length == 2 && arguments[1].equals("--evaluate");
        int limit;
        if (evaluationOnly) {
            Properties manifest = new Properties();
            try (var reader = Files.newBufferedReader(directory.resolve("training.properties"))) { manifest.load(reader); }
            limit = Integer.parseInt(manifest.getProperty("titlesPerLanguage"));
        } else {
            limit = arguments.length == 2 ? Integer.parseInt(arguments[1]) : 512;
        }
        Files.createDirectories(directory);
        List<MetadataDataset.Example> examples = MetadataDataset.load(directory.resolve("downloads"), limit);
        List<MetadataDataset.Example> training = examples.stream().filter(example -> example.partition() == 0).toList();
        List<MetadataDataset.Example> calibration = examples.stream().filter(example -> example.partition() == 1).toList();
        List<MetadataDataset.Example> test = examples.stream().filter(example -> example.partition() == 2).toList();
        if (training.isEmpty() || calibration.isEmpty() || test.isEmpty()) throw new IOException("Empty data partition");
        if (evaluationOnly) {
            try (var reader = Files.newBufferedReader(directory.resolve("model.tsv"))) {
                evaluateAll(directory, test, ZipMetadataModel.read(reader), null, null);
            }
            return;
        }
        System.out.printf(java.util.Locale.ROOT, "Examples: train=%d calibration=%d test=%d%n", training.size(), calibration.size(), test.size());
        @Nullable ZipMetadataModel best = null;
        double bestAccuracy = -1;
        float bestRegularization = 0;
        float[] bestWeights = new float[0];
        float[] bestBiases = new float[0];
        for (float regularization : new float[]{0.00001f, 0.0001f}) {
            float[] weights = new float[CLASSES * ZipMetadataModel.FEATURES];
            float[] biases = new float[CLASSES];
            train(training, weights, biases, regularization);
            for (float utf8Prior : new float[]{0, 0.5f, 1f, 2f}) {
                for (float margin : new float[]{0, 0.125f, 0.25f, 0.5f}) {
                    ZipMetadataModel.Parameters parameters = new ZipMetadataModel.Parameters(utf8Prior, margin, 0);
                    double accuracy = accuracy(calibration, weights, biases, parameters);
                    if (accuracy > bestAccuracy) {
                        bestAccuracy = accuracy;
                        bestRegularization = regularization;
                        bestWeights = weights;
                        bestBiases = biases;
                        best = ZipMetadataModel.quantize(weights, biases, parameters);
                    }
                }
            }
            System.out.printf(java.util.Locale.ROOT, "Calibration best=%.6f regularization=%g%n", bestAccuracy, bestRegularization);
        }
        if (best == null) throw new IOException("No trained model");
        best = MetadataArchiveEvaluation.calibrate(best, calibration);
        try (var writer = Files.newBufferedWriter(directory.resolve("model.tsv"), StandardCharsets.UTF_8)) { best.write(writer); }
        String parameters = "snapshot=" + MetadataDataset.SNAPSHOT + "\nseed=20261009\ntitlesPerLanguage=" + limit
                + "\nepochs=" + EPOCHS + "\nregularization=" + bestRegularization
                + "\njavaRuntime=" + System.getProperty("java.runtime.version")
                + "\njavaVendor=" + System.getProperty("java.vendor")
                + "\ncalibrationMacroAccuracy=" + bestAccuracy + "\nweightBytes=" + best.weightBytes()
                + "\nmodelSha256=" + sha256(directory.resolve("model.tsv"))
                + "\nsourceManifestSha256=" + sha256(directory.resolve("downloads/zip-metadata-titles.properties"))
                + "\nutf8Prior=" + best.parameters().utf8Prior() + "\nminimumMargin=" + best.parameters().minimumMargin()
                + "\narchiveStrength=" + best.parameters().archiveStrength() + "\n";
        Files.writeString(directory.resolve("training.properties"), parameters, StandardCharsets.UTF_8);
        evaluateAll(directory, test, best, bestWeights, bestBiases);
    }

    /// Computes a digest without loading a corpus or model file into a second full-size array.
    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = new java.security.DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /// Reports frozen-model performance without changing parameters or selecting new decision thresholds.
    private static void evaluateAll(Path directory, List<MetadataDataset.Example> test, ZipMetadataModel model,
                                    float @Nullable [] weights, float @Nullable [] biases) throws IOException {
        evaluate(directory, test, model, weights, biases);
        var joint = MetadataArchiveEvaluation.joint(model, test);
        var mixed = MetadataArchiveEvaluation.mixed(model, test);
        boolean real = MetadataArchiveEvaluation.realArchives(directory.getParent().getParent(), directory.resolve("real-archives.tsv"), model);
        Files.writeString(directory.resolve("archive-evaluation.properties"),
                "macroAccuracy=" + joint.macroAccuracy() + "\ncorrect=" + joint.correct() + "\ntotal=" + joint.total()
                        + "\nindividualCorrect=" + joint.individualCorrect() + "\ncompleteArchives=" + joint.completeArchives()
                        + "\narchives=" + joint.archives() + "\nrealArchiveGate=" + real
                        + "\nmixed.correct=" + mixed.correct() + "\nmixed.total=" + mixed.total()
                        + "\nmixed.individualCorrect=" + mixed.individualCorrect()
                        + "\nmixed.completeArchives=" + mixed.completeArchives() + "\nmixed.archives=" + mixed.archives()
                        + "\njointImprovementGate=" + (joint.correct() > joint.individualCorrect()
                        && mixed.correct() >= mixed.individualCorrect()) + "\n");
        System.out.println("Archive holdout: " + joint + "; real archive gate=" + real);
    }

    /// Applies deterministic SGD with equivalent-output likelihood and per-source-encoding balancing.
    private static void train(List<MetadataDataset.Example> examples, float[] weights, float[] biases, float regularization) {
        List<MetadataDataset.Example> order = new ArrayList<>(examples);
        int[] totals = new int[CLASSES];
        for (var example : examples) totals[example.label()]++;
        float[] scores = new float[CLASSES];
        double[] probabilities = new double[CLASSES];
        for (int epoch = 0; epoch < EPOCHS; epoch++) {
            Collections.shuffle(order, new Random(20261009L + epoch));
            float rate = 0.08f / (1 + epoch * 0.35f);
            for (var example : order) {
                dot(weights, biases, example.features(), scores);
                float maximum = Float.NEGATIVE_INFINITY;
                for (int label = 0; label < CLASSES; label++) if ((example.valid() & (1L << label)) != 0) maximum = Math.max(maximum, scores[label]);
                double total = 0;
                double correct = 0;
                for (int label = 0; label < CLASSES; label++) {
                    double probability = (example.valid() & (1L << label)) == 0 ? 0 : StrictMath.exp(scores[label] - maximum);
                    probabilities[label] = probability;
                    total += probability;
                    if ((example.correct() & (1L << label)) != 0) correct += probability;
                }
                float factor = 1f / (float) StrictMath.sqrt(example.features().indices().length);
                float balance = Math.min(4f, (float) examples.size() / (CLASSES * totals[example.label()]));
                for (int label = 0; label < CLASSES; label++) {
                    double target = (example.correct() & (1L << label)) != 0 ? probabilities[label] / correct : 0;
                    float gradient = (float) (probabilities[label] / total - target) * balance;
                    biases[label] -= rate * gradient;
                    int base = label * ZipMetadataModel.FEATURES;
                    for (int slot : example.features().indices()) weights[base + slot] -= rate * gradient * factor;
                }
            }
            // Uniform per-epoch decay applies L2 to every coefficient, including inactive features.
            float decay = (float) StrictMath.exp(-rate * regularization * examples.size());
            for (int index = 0; index < weights.length; index++) weights[index] *= decay;
            System.out.println("Trained epoch " + (epoch + 1) + " with regularization " + regularization);
        }
    }

    /// Computes unquantized model scores with the same feature normalization as the runtime.
    private static void dot(float[] weights, float[] biases, ZipMetadataModel.Features features, float[] scores) {
        float factor = 1f / (float) StrictMath.sqrt(features.indices().length);
        for (int label = 0; label < CLASSES; label++) {
            float sum = 0;
            int base = label * ZipMetadataModel.FEATURES;
            for (int slot : features.indices()) sum += weights[base + slot];
            scores[label] = biases[label] + sum * factor;
        }
    }

    /// Returns macro accuracy over source encodings, using actual decoded-output equivalence for rejection.
    private static double accuracy(List<MetadataDataset.Example> examples, float[] weights, float[] biases, ZipMetadataModel.Parameters parameters) {
        long[][] counts = new long[CLASSES][2];
        float[] scores = new float[CLASSES];
        for (var example : examples) {
            dot(weights, biases, example.features(), scores);
            scores[0] += parameters.utf8Prior();
            int best = bestLabel(scores, example.valid());
            // Calibration compares the runner-up with a different output, not merely a different charset name.
            String bestText = new String(example.bytes(), Charset.forName(ZipMetadataModel.ENCODINGS.get(best)));
            long equivalent = new ZipMetadataModel.Workspace(example.bytes().length).matching(ByteBuffer.wrap(example.bytes()), bestText);
            int runner = bestLabel(scores, example.valid() & ~equivalent);
            if (runner >= 0 && scores[best] - scores[runner] <= parameters.minimumMargin()) best = ZipMetadataModel.CP437;
            counts[example.label()][1]++;
            if ((example.correct() & (1L << best)) != 0) counts[example.label()][0]++;
        }
        return macro(counts);
    }

    /// Returns the highest-scoring valid label, or -1 for an empty mask.
    private static int bestLabel(float[] scores, long valid) {
        int best = -1;
        for (int label = 0; label < CLASSES; label++) if ((valid & (1L << label)) != 0 && (best < 0 || scores[label] > scores[best])) best = label;
        return best;
    }

    /// Computes an unweighted mean over represented source encodings.
    private static double macro(long[][] counts) {
        double sum = 0;
        int present = 0;
        for (long[] count : counts) if (count[1] != 0) { sum += (double) count[0] / count[1]; present++; }
        return sum / present;
    }

    /// Writes held-out decoded-text accuracy for the unchanged default, learned model, and two independent detectors.
    private static void evaluate(Path directory, List<MetadataDataset.Example> test, ZipMetadataModel model,
                                 float @Nullable [] weights, float @Nullable [] biases) throws IOException {
        String[] methods = {"baseline", "learned", "juniversalchardet", "icu4j"};
        long[][][] totals = new long[methods.length][CLASSES * 5][3];
        long quantizationChanges = 0;
        long quantizationTextChanges = 0;
        float[] original = new float[CLASSES];
        float[] quantized = new float[CLASSES];
        for (var example : test) {
            int significant = (int) example.text().codePoints().filter(ch -> ch >= 128).count();
            int bucket = Math.min(significant, 5) - 1;
            ZipMetadataModel.Decision decision = model.decode(ByteBuffer.wrap(example.bytes()), null);
            if (weights != null && biases != null) {
                dot(weights, biases, example.features(), original);
                original[0] += model.parameters().utf8Prior();
                model.score(example.features(), quantized);
                int best = bestLabel(original, example.valid());
                if (best != bestLabel(quantized, example.valid())) quantizationChanges++;
                String text = new String(example.bytes(), Charset.forName(ZipMetadataModel.ENCODINGS.get(best)));
                long equivalent = new ZipMetadataModel.Workspace(example.bytes().length).matching(ByteBuffer.wrap(example.bytes()), text);
                int runner = bestLabel(original, example.valid() & ~equivalent);
                if (runner >= 0 && original[best] - original[runner] <= model.parameters().minimumMargin()) {
                    text = new String(example.bytes(), Charset.forName("IBM437"));
                }
                if (!text.equals(decision.text())) quantizationTextChanges++;
            }
            String[] predictions = new String[methods.length];
            predictions[0] = ZipAutoMetadataDecoder.DEFAULT.decode(ByteBuffer.wrap(example.bytes()));
            predictions[1] = decision.text();
            var universal = new org.mozilla.universalchardet.UniversalDetector();
            universal.handleData(example.bytes());
            universal.dataEnd();
            predictions[2] = decodeReference(example.bytes(), universal.getDetectedCharset());
            var icu = new com.ibm.icu.text.CharsetDetector();
            icu.setText(example.bytes());
            @Nullable var match = icu.detect();
            predictions[3] = decodeReference(example.bytes(), match == null ? null : match.getName());
            for (int method = 0; method < methods.length; method++) {
                long[] count = totals[method][example.label() * 5 + bucket];
                count[1]++;
                if (example.text().equals(predictions[method])) count[0]++;
                if (method == 1 && decision.fallback()) count[2]++;
            }
        }
        try (var writer = Files.newBufferedWriter(directory.resolve("holdout.tsv"), StandardCharsets.UTF_8)) {
            writer.write("method\tencoding\tnonAsciiCharacters\tcorrect\ttotal\tfallback\n");
            for (int method = 0; method < methods.length; method++) {
                long[][] byEncoding = new long[CLASSES][2];
                for (int row = 0; row < totals[method].length; row++) {
                    long[] count = totals[method][row];
                    writer.write(methods[method] + "\t" + ZipMetadataModel.ENCODINGS.get(row / 5) + "\t" + (row % 5 + 1)
                            + "\t" + count[0] + "\t" + count[1] + "\t" + count[2] + "\n");
                    byEncoding[row / 5][0] += count[0];
                    byEncoding[row / 5][1] += count[1];
                }
                System.out.printf(java.util.Locale.ROOT, "%s holdout macro accuracy: %.6f%n", methods[method], macro(byEncoding));
            }
        }
        if (weights != null) Files.writeString(directory.resolve("quantization.properties"),
                "changedTopLabels=" + quantizationChanges + "\nchangedDecodedTexts=" + quantizationTextChanges + "\nexamples=" + test.size() + "\n");
    }

    /// Strictly decodes a reference detector's supported result, using CP437 when the detector abstains.
    private static String decodeReference(byte[] bytes, @Nullable String name) {
        Charset fallback = Charset.forName("IBM437");
        if (name == null || !Charset.isSupported(name)) return new String(bytes, fallback);
        try { return Charset.forName(name).newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException exception) { return new String(bytes, fallback); }
    }
}
