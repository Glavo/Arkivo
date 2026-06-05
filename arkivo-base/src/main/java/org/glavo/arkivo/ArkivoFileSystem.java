// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.FileSystem;
import java.nio.file.OpenOption;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

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

    /// The common environment option that controls how the backing archive path is opened.
    ///
    /// The typed option value is a `Set<OpenOption>`. Raw environment maps may also provide an `OpenOption[]`,
    /// a single `OpenOption`, or a `Collection` of `OpenOption` values.
    public static final ArkivoFileSystemOption<Set<OpenOption>> OPEN_OPTIONS =
            ArkivoFileSystemOption.of(
                    "arkivo",
                    "openOptions",
                    openOptionsType(),
                    ArkivoFileSystem::openOptionsValue
            );

    /// The common environment option for staging new and replacement archive entry content.
    public static final ArkivoFileSystemOption<ArkivoEditStorage> EDIT_STORAGE =
            ArkivoFileSystemOption.of("arkivo", "editStorage", ArkivoEditStorage.class);

    /// The common environment option for choosing where assembled archive bytes are committed.
    public static final ArkivoFileSystemOption<ArkivoCommitTarget> COMMIT_TARGET =
            ArkivoFileSystemOption.of("arkivo", "commitTarget", ArkivoCommitTarget.class);

    /// The common environment option for deciding whether direct source-file mutation may be used.
    public static final ArkivoFileSystemOption<ArkivoSourceMutationPolicy> SOURCE_MUTATION_POLICY =
            ArkivoFileSystemOption.of(
                    "arkivo",
                    "sourceMutationPolicy",
                    ArkivoSourceMutationPolicy.class
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

    /// Converts a raw open options value.
    private static @Unmodifiable Set<OpenOption> openOptionsValue(Object value) {
        LinkedHashSet<OpenOption> options = new LinkedHashSet<>();
        if (value instanceof OpenOption[] array) {
            for (OpenOption option : array) {
                options.add(Objects.requireNonNull(option, "option"));
            }
            return Set.copyOf(options);
        }
        if (value instanceof OpenOption option) {
            return Set.of(option);
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (!(item instanceof OpenOption option)) {
                    throw new IllegalArgumentException(
                            "Expected OpenOption values for key: " + OPEN_OPTIONS.key()
                    );
                }
                options.add(option);
            }
            return Set.copyOf(options);
        }
        throw new IllegalArgumentException(
                "Expected Set<OpenOption>, OpenOption[], OpenOption, or Collection<? extends OpenOption> for key: "
                        + OPEN_OPTIONS.key()
        );
    }

    /// Returns the erased runtime class used by the typed open options environment option.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Class<Set<OpenOption>> openOptionsType() {
        return (Class) Set.class;
    }
}
