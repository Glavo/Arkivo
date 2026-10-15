// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies named attribute selection in readable and updateable archive file systems.
@NotNullByDefault
final class ArchiveNamedAttributesTest {
    /// Accepts repeated names and wildcard combinations while rejecting unknown names in every supported view.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z"})
    void selectsNamedAttributes(String format, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("attributes." + format);
        try (var fs = ArkivoFormats.createFileSystem(format, archive)) {
            Files.writeString(fs.getPath("/entry"), "content");
        }
        for (boolean update : new boolean[]{false, true}) {
            try (var fs = update ? ArkivoFormats.updateFileSystem(format, archive)
                    : ArkivoFormats.openFileSystem(format, archive)) {
                Path file = fs.getPath("/entry");
                for (String view : List.of("basic", "owner", "posix", format)) {
                    @Unmodifiable Map<String, @Nullable Object> all = Files.readAttributes(file, view + ":*");
                    assertFalse(all.isEmpty());
                    String name = all.keySet().iterator().next();
                    assertEquals(Files.readAttributes(file, view + ":" + name).keySet(),
                            Files.readAttributes(file, view + ":" + name + "," + name).keySet());
                    for (String selection : List.of("*," + name, name + ",*", "*,*")) {
                        assertEquals(all.keySet(), Files.readAttributes(file, view + ":" + selection).keySet());
                    }
                    for (var attribute : all.entrySet()) {
                        if (attribute.getValue() == null) {
                            @Unmodifiable Map<String, @Nullable Object> selected = Files.readAttributes(
                                    file, view + ":" + attribute.getKey());
                            assertTrue(selected.containsKey(attribute.getKey()));
                            assertNull(selected.get(attribute.getKey()));
                        }
                    }
                    for (String selection : List.of("doesNotExist", "*,doesNotExist", "doesNotExist,*")) {
                        assertThrows(IllegalArgumentException.class,
                                () -> Files.readAttributes(file, view + ":" + selection));
                    }
                    for (String selection : List.of("", ",", ",,")) {
                        assertThrows(IllegalArgumentException.class,
                                () -> Files.readAttributes(file, view + ":" + selection));
                    }
                    assertThrows(IllegalArgumentException.class,
                            () -> Files.getAttribute(file, view + ":doesNotExist"));
                }
                assertEquals(Files.readAttributes(file, "size"), Files.readAttributes(file, "size,size"));
                assertEquals(7L, Files.getAttribute(file, "size"));
            }
        }
    }
}
