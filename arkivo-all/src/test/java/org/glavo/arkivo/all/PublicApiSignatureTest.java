// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.SortedSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the reviewed public JVM and generic signatures remain unchanged.
@NotNullByDefault
final class PublicApiSignatureTest {
    /// Compares the current exported API with its reviewed text baseline.
    @Test
    void publicSignaturesMatchBaseline() throws Exception {
        ClassLoader loader = Objects.requireNonNull(
                Thread.currentThread().getContextClassLoader(),
                "context class loader"
        );
        assertEquals(
                PublicApiSignatures.loadLines(loader, PublicApiSignatures.SIGNATURE_RESOURCE),
                PublicApiSignatures.collect(loader),
                "Exported public signatures differ from the reviewed 1.0 API baseline"
        );
    }
    /// Verifies the baseline captures inlinable values and canonical method modifiers.
    @Test
    void signaturesCaptureAbiSensitiveDetails() throws Exception {
        ClassLoader loader = Objects.requireNonNull(
                Thread.currentThread().getContextClassLoader(),
                "context class loader"
        );
        @Unmodifiable SortedSet<String> signatures = PublicApiSignatures.collect(loader);
        assertTrue(
                signatures.contains(
                        "FIELD\torg.glavo.arkivo.archive.zip.ZipArkivoFileSystem\tMINIMUM_SPLIT_SIZE"
                                + "\tdescriptor=J\tgeneric=long\tmodifiers=public+static+final"
                                + "\tflags=-\tconstant=65536"
                ),
                "The API baseline must capture inlinable field values"
        );
        assertTrue(
                signatures.stream().noneMatch(signature ->
                        signature.startsWith("METHOD\t")
                                && (signature.contains("\tmodifiers=public+volatile")
                                || signature.contains("\tmodifiers=public+transient"))
                ),
                "Bridge and varargs flags must not leak field modifier aliases"
        );
    }
}
