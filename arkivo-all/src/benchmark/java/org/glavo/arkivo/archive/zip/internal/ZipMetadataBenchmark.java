// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/// Measures independent fields, archive evidence, and ASCII index construction using identical inputs.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class ZipMetadataBenchmark {
    /// Selects legacy or UTF-8 fields, directory repetition, mixed encodings, or an 8192-entry ASCII index.
    @Param({"single", "batch", "utf8-single", "utf8-batch", "prefix", "mixed", "ascii-index"})
    public String workload = "single";

    /// Independent original bytes, generated outside the measurement.
    private byte[][] fields = new byte[0][];

    /// Central records for the archive-level workloads.
    private ByteBuffer central = ByteBuffer.allocate(0);

    /// Shared stateless strategy; prepared instances are constructed inside the measured operation.
    private ArchiveMetadataDecoder decoder = ZipAutoMetadataDecoder.DEFAULT;

    /// Temporary ASCII archive, absent for field-only workloads.
    private @Nullable Path archive;

    /// Generates deterministic fields and checks the index entry count without judging language-model accuracy.
    @Setup
    public void setup() throws IOException {
        decoder = ZipAutoMetadataDecoder.DEFAULT;
        String[] names = {"一覧表", "漢字", "資料", "写真", "報告書", "予定表", "説明", "映像"};
        fields = new byte[workload.endsWith("single") ? 1 : 512][];
        int size = 0;
        for (int index = 0; index < fields.length; index++) {
            String name = names[index % names.length] + index + ".txt";
            Charset charset = Charset.forName(workload.startsWith("utf8-") ? "UTF-8" : "windows-31j");
            if (workload.equals("prefix")) name = "資料/" + name;
            if (workload.equals("mixed") && index % 2 == 1) {
                name = "документ" + index + ".txt";
                charset = Charset.forName("IBM866");
            }
            fields[index] = name.getBytes(charset);
            size += 46 + fields[index].length;
        }
        central = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        for (byte[] field : fields) {
            int start = central.position();
            central.putInt(0x02014b50);
            central.putShort(start + 4, (short) 20);
            central.putShort(start + 28, (short) field.length);
            central.position(start + 46).put(field);
        }
        central.flip();
        if (workload.equals("ascii-index")) {
            archive = Files.createTempFile("arkivo-metadata-", ".zip");
            try (var zip = new ZipOutputStream(Files.newOutputStream(archive), Charset.forName("IBM437"))) {
                for (int index = 0; index < 8192; index++) {
                    var entry = new ZipEntry("entry" + index + ".txt");
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(0);
                    entry.setCrc(0);
                    zip.putNextEntry(entry);
                    zip.closeEntry();
                }
            }
            if (index() != 8192) throw new AssertionError("Incomplete ASCII index");
        } else {
            // Exclude first model loading from steady-state measurements.
            decoder.decode(ByteBuffer.wrap(fields[0]));
        }
    }

    /// Opens and completely enumerates the ASCII archive; no timing-only open comparison is used.
    private long index() throws IOException {
        try (var fs = ZipArkivoFileSystem.open(Objects.requireNonNull(archive),
                ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(decoder));
             var paths = Files.list(fs.getPath("/"))) {
            return paths.count();
        }
    }

    /// Measures a complete workload, including sampling and freezing for archive-level cases.
    @Benchmark
    public void run(Blackhole sink) throws IOException {
        if (workload.equals("ascii-index")) {
            sink.consume(index());
        } else if (workload.endsWith("single")) {
            sink.consume(decoder.decode(ByteBuffer.wrap(fields[0])));
        } else {
            var prepared = ZipEntryNameDecoder.forCentralDirectory(decoder, central);
            int offset = 0;
            for (byte[] field : fields) {
                @Nullable String text = prepared.take(offset + 46);
                sink.consume(text != null ? text : prepared.decoder().decodePath(field, 0, new byte[0],
                        org.glavo.arkivo.archive.zip.ZipLegacyMetadataDecoder.HeaderSource.CENTRAL_DIRECTORY, 20, 20, 0));
                offset += 46 + field.length;
            }
        }
    }

    /// Deletes only the archive created by this benchmark instance.
    @TearDown
    public void cleanup() throws IOException {
        if (archive != null) Files.delete(archive);
    }
}
