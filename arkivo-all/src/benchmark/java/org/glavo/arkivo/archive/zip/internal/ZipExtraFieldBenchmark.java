// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;

/// Measures timestamp and owner resolution independently of archive I/O and name decoding.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class ZipExtraFieldBenchmark {
    /// The timestamp layouts to parse, including an empty-data control.
    @Param({"empty", "unix", "ntfs", "mixed"})
    public String layout = "empty";

    /// The number of unrelated fields preceding the recognized metadata in each header.
    @Param({"0"})
    public int unrelatedFields;

    /// Uses different NTFS access and creation times instead of repeating the modification time.
    @Param({"false"})
    public boolean distinctNtfsTimes;

    /// Local-header records generated before measurement.
    private byte @Unmodifiable [] local = new byte[0];

    /// Central-directory records generated before measurement.
    private byte @Unmodifiable [] central = new byte[0];

    /// The fallback used when no timestamp field supplies a modification time.
    private final FileTime fallback = FileTime.fromMillis(1_000);

    /// Builds valid records and verifies all resolved timestamps and owner identifiers before measurement.
    @Setup
    public void setup() throws IOException {
        if (unrelatedFields < 0 || unrelatedFields > 4096) {
            throw new IllegalArgumentException("Invalid unrelated field count");
        }
        local = fields(true);
        central = fields(false);
        var result = ZipExtraFieldMetadata.resolve(local, central, fallback);
        long expectedSeconds = switch (layout) {
            case "empty" -> 1;
            case "unix" -> 123;
            case "ntfs", "mixed" -> 456;
            default -> throw new IllegalArgumentException(layout);
        };
        boolean distinct = distinctNtfsTimes && (layout.equals("ntfs") || layout.equals("mixed"));
        if (!result.lastModifiedTime().toInstant().equals(java.time.Instant.ofEpochSecond(expectedSeconds))
                || !result.lastAccessTime().toInstant().equals(java.time.Instant.ofEpochSecond(expectedSeconds, distinct ? 1_000 : 0))
                || !result.creationTime().toInstant().equals(java.time.Instant.ofEpochSecond(expectedSeconds, distinct ? 2_000 : 0))
                || result.userId() != (layout.equals("mixed") ? 42 : -1)
                || result.groupId() != (layout.equals("mixed") ? 43 : -1)) {
            throw new AssertionError("Incorrect resolved metadata");
        }
    }

    /// Encodes independent local and central metadata, with local fields taking precedence.
    private byte @Unmodifiable [] fields(boolean localHeader) {
        ByteBuffer data = ByteBuffer.allocate(unrelatedFields * 12 + 128).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < unrelatedFields; i++) {
            data.putShort((short) 0xcafe).putShort((short) 8).putLong(i);
        }
        if (layout.equals("unix") || layout.equals("mixed")) {
            data.putShort((short) 0x5455).putShort((short) 5).put((byte) 1)
                    .putInt(localHeader ? 123 : 789);
        }
        if (layout.equals("ntfs") || layout.equals("mixed")) {
            long ticks = 116_444_736_000_000_000L + (localHeader ? 456 : 789) * 10_000_000L;
            data.putShort((short) 0x000a).putShort((short) 32).putInt(0)
                    .putShort((short) 1).putShort((short) 24).putLong(ticks)
                    .putLong(distinctNtfsTimes ? ticks + 10 : ticks)
                    .putLong(distinctNtfsTimes ? ticks + 20 : ticks);
        }
        if (layout.equals("mixed")) {
            data.putShort((short) 0x5855).putShort((short) 8).putInt(10).putInt(20);
            data.putShort((short) 0x000d).putShort((short) 8).putInt(30).putInt(40);
            if (localHeader) {
                data.putShort((short) 0x7875).putShort((short) 5)
                        .put((byte) 1).put((byte) 1).put((byte) 42).put((byte) 1).put((byte) 43);
            }
        }
        return java.util.Arrays.copyOf(data.array(), data.position());
    }

    /// Resolves both headers, retaining the result so its allocations remain observable.
    @Benchmark
    public Object resolve() throws IOException {
        return ZipExtraFieldMetadata.resolve(local, central, fallback);
    }
}
