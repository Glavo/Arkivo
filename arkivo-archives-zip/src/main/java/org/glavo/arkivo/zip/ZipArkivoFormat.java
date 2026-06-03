// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoFormat;
import org.glavo.arkivo.ArkivoFormatCapabilities;
import org.jetbrains.annotations.NotNullByDefault;

/// Describes the ZIP archive format support provided by Arkivo.
@NotNullByDefault
public final class ZipArkivoFormat implements ArkivoFormat {
    /// The stable ZIP format name.
    public static final String NAME = "zip";

    /// The shared ZIP format instance.
    private static final ZipArkivoFormat INSTANCE = new ZipArkivoFormat();

    /// The ZIP capability set.
    private static final ArkivoFormatCapabilities CAPABILITIES = ArkivoFormatCapabilities.of(
            true,
            true,
            true,
            false,
            true,
            true,
            true,
            true,
            true,
            true
    );

    /// Creates a ZIP format descriptor.
    public ZipArkivoFormat() {
    }

    /// Returns the shared ZIP format descriptor.
    public static ZipArkivoFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable ZIP format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns the ZIP capability set.
    @Override
    public ArkivoFormatCapabilities capabilities() {
        return CAPABILITIES;
    }
}
