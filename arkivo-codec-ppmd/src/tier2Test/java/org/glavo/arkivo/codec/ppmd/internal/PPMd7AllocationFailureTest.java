// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks allocator recovery after a real allocation failure in an isolated, bounded heap.
@NotNullByDefault
final class PPMd7AllocationFailureTest {
    /// Preserves the old arena when a replacement array cannot fit in a separate 32 MiB JVM.
    @Test
    void preservesArenaAfterFailedGrowth(@TempDir Path directory) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String classPath = Path.of(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator
                + Path.of(PPMd7Allocator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path log = directory.resolve("allocation-failure.log");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xms16m", "-Xmx32m", "-cp", classPath, Probe.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Allocator probe did not terminate");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    /// Exercises a failed growth without requiring JUnit in the child process.
    @NotNullByDefault
    public static final class Probe {
        /// Prevents instantiation of the subprocess entry point.
        private Probe() {
        }

        /// Forces an arena allocation failure, verifies rollback, and retries with a smaller arena.
        ///
        /// @param args unused command-line arguments
        /// @throws Exception if allocation does not fail as expected or allocator state is not preserved
        public static void main(String[] args) throws Exception {
            PPMd7Allocator allocator = new PPMd7Allocator();
            allocator.initialize(2_048);
            allocator.restart();
            allocator.setSymbol(1, 123);
            allocator.setFrequency(1, 17);
            allocator.setSuccessor(1, 456);
            String[] names = {"symbols", "frequencies", "successors", "arenaStateCount", "lowerHeapMaximumBytes"};
            Object[] previous = new Object[names.length];
            for (int index = 0; index < names.length; index++) {
                previous[index] = fieldValue(allocator, names[index]);
            }
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    allocator.initialize(48L << 20);
                    throw new AssertionError("Oversized arena unexpectedly fit in the bounded heap");
                } catch (IOException exception) {
                    if (!(exception.getCause() instanceof OutOfMemoryError)) throw exception;
                }
                for (int index = 0; index < names.length; index++) {
                    if (!Objects.equals(previous[index], fieldValue(allocator, names[index]))) {
                        throw new AssertionError("Failed growth changed " + names[index]);
                    }
                }
                if (allocator.symbol(1) != 123 || allocator.frequency(1) != 17 || allocator.successor(1) != 456) {
                    throw new AssertionError("Failed growth corrupted the previous arena");
                }
            }
            allocator.initialize(4_096);
            allocator.restart();
            allocator.setSymbol(1, 71);
            allocator.setFrequency(1, 31);
            allocator.setSuccessor(1, 789);
            if (allocator.symbol(1) != 71 || allocator.frequency(1) != 31 || allocator.successor(1) != 789) {
                throw new AssertionError("Allocator did not recover after failed growth");
            }
        }

        /// Reads an array identity or primitive layout value without copying the arena.
        private static Object fieldValue(PPMd7Allocator allocator, String name) throws ReflectiveOperationException {
            var field = PPMd7Allocator.class.getDeclaredField(name);
            field.setAccessible(true);
            return Objects.requireNonNull(field.get(allocator));
        }
    }
}
