// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies bundled model values directly against the pinned upstream resources.
@NotNullByDefault
class BigramModelsTest {
    /// Preserves every IDF coordinate, model weight, and original normalization constant.
    @Test
    void losslessProjection() throws Exception {
        var target = BigramModels.INSTANCE;
        assertEquals(29, target.candidates.size());
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/kala/encdet/internal/idf.bin"))) {
            for (int pair = 0; pair < 65536; pair++) assertEquals(input.read(), target.idf(pair));
            assertEquals(-1, input.read());
        }
        var unmatched = new TreeMap<String, BigramModels.Model>();
        target.candidates.values().forEach(variants -> variants.forEach(model ->
                assertNull(unmatched.put(model.name(), model))));
        assertEquals(204, unmatched.size());
        try (var input = new DataInputStream(Objects.requireNonNull(getClass()
                .getResourceAsStream("/kala/encdet/internal/models.bin")))) {
            assertEquals(0x434D4432, input.readInt());
            int count = input.readInt();
            var names = new ArrayList<String>();
            for (int i = 0; i < count; i++) {
                names.add(new String(input.readNBytes(input.readInt()), StandardCharsets.UTF_8));
                double norm = input.readDouble();
                @Nullable var model = unmatched.get(names.get(i));
                if (model != null) assertEquals(norm, model.norm());
            }
            try (var rows = new DataInputStream(new InflaterInputStream(input))) {
                byte[] row = new byte[65536];
                for (var name : names) {
                    rows.readFully(row);
                    @Nullable var model = unmatched.remove(name);
                    if (model == null) continue;
                    for (int pair = 0; pair < row.length; pair++) assertEquals(row[pair] & 255, model.weight(pair));
                }
                assertEquals(-1, rows.read());
            }
        }
        assertTrue(unmatched.isEmpty());
    }

    /// Reproduces the uncompressed resource byte for byte without overwriting the checked-in file.
    @Test
    void reproducibleResource(@TempDir Path directory) throws Exception {
        MetadataModelGenerator.main(new String[]{directory.toString()});
        var checkedIn = Path.of("src/main/resources/org/glavo/arkivo/encoding/detector/internal/bigram-models.bin");
        assertEquals(-1L, Files.mismatch(checkedIn, directory.resolve("bigram-models.bin")));
        try (var input = new DataInputStream(Files.newInputStream(checkedIn))) {
            assertEquals(0x414D4431, input.readInt());
        }
    }
}
