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

    /// Writes the current public types and signatures to the requested baseline paths.
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Expected public type and signature baseline output paths");
        }
        ClassLoader loader = Objects.requireNonNull(
                Thread.currentThread().getContextClassLoader(),
                "context class loader"
        );
        @Unmodifiable SortedSet<String> apiTypeNames = PublicApiBoundaryTest.collectApiTypeNames(loader);
        @Unmodifiable SortedSet<String> signatures = PublicApiSignatures.collect(loader, apiTypeNames);
        Path typeOutput = Path.of(arguments[0]);
        Path signatureOutput = Path.of(arguments[1]);
        PublicApiSignatures.write(typeOutput, apiTypeNames);
        PublicApiSignatures.write(signatureOutput, signatures);
        System.out.println("Wrote " + apiTypeNames.size() + " API types to " + typeOutput);
        System.out.println("Wrote " + signatures.size() + " API signatures to " + signatureOutput);
    }
}
