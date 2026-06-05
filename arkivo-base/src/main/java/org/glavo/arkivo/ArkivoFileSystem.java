// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.FileSystem;
import java.util.Objects;

/// Provides shared behavior for archive-backed file systems.
@NotNullByDefault
public abstract class ArkivoFileSystem extends FileSystem {
    /// The common environment option that controls the requested file system thread-safety strategy.
    public static final ArkivoFileSystemOption<ArkivoFileSystemThreadSafety> THREAD_SAFETY =
            ArkivoFileSystemOption.of(
                    "arkivo",
                    "threadSafety",
                    ArkivoFileSystemThreadSafety.class,
                    ArkivoFileSystem::threadSafetyOptionValue
            );

    /// The requested file system thread-safety strategy.
    private final ArkivoFileSystemThreadSafety threadSafety;

    /// Creates an archive-backed file system.
    protected ArkivoFileSystem() {
        this(ArkivoFileSystemThreadSafety.CONCURRENT_READ);
    }

    /// Creates an archive-backed file system with a thread-safety strategy.
    protected ArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        this.threadSafety = Objects.requireNonNull(threadSafety, "threadSafety");
    }

    /// Returns the requested file system thread-safety strategy.
    public final ArkivoFileSystemThreadSafety threadSafety() {
        return threadSafety;
    }

    /// Converts a raw thread-safety option value.
    private static ArkivoFileSystemThreadSafety threadSafetyOptionValue(Object value) {
        if (value instanceof ArkivoFileSystemThreadSafety threadSafety) {
            return threadSafety;
        }
        if (value instanceof String stringValue) {
            return ArkivoFileSystemThreadSafety.parse(stringValue);
        }
        throw new IllegalArgumentException(
                "Expected ArkivoFileSystemThreadSafety or String for key: " + THREAD_SAFETY.key()
        );
    }
}
