// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/// Creates small ZIP archives shared by focused contract tests.
@NotNullByDefault
final class ZipTestArchiveFixtures {
    /// Creates no instances.
    private ZipTestArchiveFixtures() {
    }

    /// Writes a deflated archive containing `dir/hello.txt` and returns its path.
    static Path writeDeflatedArchive(Path archivePath) throws IOException {
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
            var directoryEntry = writer.beginDirectory("dir");
            directoryEntry.close();
            var helloEntry = writer.beginFile("dir/hello.txt");
            try (var output = helloEntry.openOutputStream()) {
                output.write("hello".getBytes(StandardCharsets.UTF_8));
            }
        }
        return archivePath;
    }
}
