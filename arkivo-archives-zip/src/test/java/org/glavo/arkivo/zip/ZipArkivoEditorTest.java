// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoCommitTarget;
import org.glavo.arkivo.ArkivoEditStorage;
import org.glavo.arkivo.ArkivoEditor;
import org.glavo.arkivo.ArkivoSourceMutationPolicy;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Tests ZIP archive editor configuration.
@NotNullByDefault
public final class ZipArkivoEditorTest {
    /// Verifies that a ZIP editor opens with default strategies.
    @Test
    public void openWithDefaults() throws IOException {
        Path archivePath = Path.of("sample.zip");

        try (ZipArkivoEditor editor = ZipArkivoEditor.open(archivePath)) {
            assertEquals(archivePath.toAbsolutePath().normalize(), editor.sourcePath());
            assertNotNull(editor.storage());
            assertNotNull(editor.commitTarget());
            assertNotNull(editor.sourceMutationPolicy());
        }
    }

    /// Verifies that a ZIP editor accepts common editor strategy options.
    @Test
    public void openWithCustomStrategies() throws IOException {
        Path archivePath = Path.of("sample.zip");
        Path targetPath = Path.of("build", "tmp", "zip-editor-tests", "target.zip");
        ArkivoEditStorage storage = ArkivoEditStorage.memory();
        ArkivoCommitTarget commitTarget = ArkivoCommitTarget.writeTo(targetPath);
        ArkivoSourceMutationPolicy sourceMutationPolicy = ArkivoSourceMutationPolicy.patchWhenSafe();

        try (ZipArkivoEditor editor = ZipArkivoEditor.open(
                archivePath,
                Map.of(
                        ArkivoEditor.STORAGE.key(), storage,
                        ArkivoEditor.COMMIT_TARGET.key(), commitTarget,
                        ArkivoEditor.SOURCE_MUTATION_POLICY.key(), sourceMutationPolicy
                )
        )) {
            assertSame(storage, editor.storage());
            assertSame(commitTarget, editor.commitTarget());
            assertSame(sourceMutationPolicy, editor.sourceMutationPolicy());
        }
    }
}
