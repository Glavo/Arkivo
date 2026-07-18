// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.FileStore;
import java.util.Objects;

/// Resolves common archive file store attributes.
@NotNullByDefault
public final class ArkivoFileStoreAttributes {
    /// Prevents instantiation.
    private ArkivoFileStoreAttributes() {
    }

    /// Reads a common file store attribute from the given file store.
    ///
    /// @param fileStore the file store queried for the resolved attribute
    /// @param attribute the {@code name} or {@code basic:name} attribute identifier
    /// @return the attribute value
    /// @throws IOException if the underlying space attribute cannot be read
    /// @throws UnsupportedOperationException if the view or attribute name is unsupported
    public static Object get(FileStore fileStore, String attribute) throws IOException {
        Objects.requireNonNull(fileStore, "fileStore");
        Objects.requireNonNull(attribute, "attribute");

        String view = "basic";
        String name = attribute;
        int separator = attribute.indexOf(':');
        if (separator >= 0) {
            view = attribute.substring(0, separator);
            name = attribute.substring(separator + 1);
        }
        if (!"basic".equals(view)) {
            throw unsupported(attribute);
        }

        return switch (name) {
            case "name" -> fileStore.name();
            case "type" -> fileStore.type();
            case "readOnly" -> fileStore.isReadOnly();
            case "totalSpace" -> fileStore.getTotalSpace();
            case "usableSpace" -> fileStore.getUsableSpace();
            case "unallocatedSpace" -> fileStore.getUnallocatedSpace();
            default -> throw unsupported(attribute);
        };
    }

    /// Creates an exception for an unsupported file store attribute.
    private static UnsupportedOperationException unsupported(String attribute) {
        return new UnsupportedOperationException("Unsupported file store attribute: " + attribute);
    }
}
