// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.Objects;
import java.util.SortedSet;

/// Updates the reviewed text baseline after an intentional public API change.
@NotNullByDefault
public final class PublicApiBaselineGenerator {
    /// Prevents generator instantiation.
    private PublicApiBaselineGenerator() {
    }

    /// Writes the current public signatures to the single requested baseline path.
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected one public API baseline output path");
        }
        ClassLoader loader = Objects.requireNonNull(
                Thread.currentThread().getContextClassLoader(),
                "context class loader"
        );
        @Unmodifiable SortedSet<String> signatures = PublicApiSignatures.collect(loader);
        Path output = Path.of(arguments[0]);
        PublicApiSignatures.write(output, signatures);
        System.out.println("Wrote " + signatures.size() + " public API signatures to " + output);
    }
}
