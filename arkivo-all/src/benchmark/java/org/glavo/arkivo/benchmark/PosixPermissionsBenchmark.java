// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.archive.internal.PosixModes;
import org.glavo.arkivo.archive.internal.PosixPermissions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.openjdk.jmh.annotations.*;

import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/// Measures shared POSIX permission decoding, attribute validation, and first-use initialization.
@NotNullByDefault
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class PosixPermissionsBenchmark {
    /// Decodes common archive permission modes without file-system I/O.
    @Benchmark
    public @Unmodifiable Set<PosixFilePermission> decode(PermissionState state) {
        return PosixModes.permissions(state.mode);
    }

    /// Validates and snapshots a dynamically typed permission attribute.
    @Benchmark
    public @Unmodifiable Set<PosixFilePermission> validate(PermissionState state) {
        return PosixPermissions.copyOf(state.permissions, "posix:permissions");
    }

    /// Includes permission-table and related class initialization in a fresh process.
    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Warmup(iterations = 0)
    @Measurement(iterations = 1)
    @Fork(value = 5, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
    public @Unmodifiable Set<PosixFilePermission> firstLookup() {
        return PosixModes.permissions(0644);
    }

    /// Prepares common file and directory permissions outside steady-state measurements.
    @NotNullByDefault
    @State(Scope.Thread)
    public static class PermissionState {
        /// The octal permission mode.
        @Param({"0644", "0755"})
        public String octalMode = "0644";

        /// Mode bits used for decoding.
        private int mode;

        /// Immutable attribute value used for validation.
        private @Unmodifiable Set<PosixFilePermission> permissions = Set.of();

        /// Verifies the conversions against the JDK textual permission parser.
        @Setup
        public void setUp() {
            mode = Integer.parseInt(octalMode, 8);
            permissions = Set.copyOf(PosixFilePermissions.fromString(mode == 0644 ? "rw-r--r--" : "rwxr-xr-x"));
            if (!permissions.equals(PosixModes.permissions(mode))
                    || !permissions.equals(PosixPermissions.copyOf(permissions, "permissions"))) {
                throw new IllegalStateException("Permission conversion mismatch");
            }
        }
    }
}
