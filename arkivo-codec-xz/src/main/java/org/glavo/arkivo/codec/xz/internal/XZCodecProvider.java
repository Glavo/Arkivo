// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.glavo.arkivo.codec.spi.CompressionCodecProvider;
import org.glavo.arkivo.codec.xz.XZCodec;
import org.jetbrains.annotations.NotNullByDefault;

/// Supplies the default XZ codec through compression codec service discovery.
@NotNullByDefault
public final class XZCodecProvider implements CompressionCodecProvider {
    /// Creates a stateless XZ codec provider.
    public XZCodecProvider() {
    }

    /// Returns the default immutable XZ codec.
    @Override
    public XZCodec defaultCodec() {
        return XZCodec.DEFAULT;
    }
}