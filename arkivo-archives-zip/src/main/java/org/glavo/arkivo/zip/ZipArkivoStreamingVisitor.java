// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Visits entries while a ZIP stream is read in storage order.
@NotNullByDefault
@FunctionalInterface
public interface ZipArkivoStreamingVisitor {
    /// Visits one ZIP entry in storage order.
    ZipArkivoStreamingVisitResult visitEntry(String path, ZipArkivoEntryAttributes attributes) throws IOException;
}
