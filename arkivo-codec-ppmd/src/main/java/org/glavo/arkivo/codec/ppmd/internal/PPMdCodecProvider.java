// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.glavo.arkivo.codec.spi.CompressionCodecProvider;
import org.glavo.arkivo.codec.ppmd.PPMdCodec;
import org.jetbrains.annotations.NotNullByDefault;

/// Supplies the default PPMd7 codec through compression codec service discovery.
@NotNullByDefault
public final class PPMdCodecProvider implements CompressionCodecProvider {
    /// Creates a stateless PPMd7 codec provider.
    public PPMdCodecProvider() {
    }

    /// Returns the default immutable PPMd7 codec.
    @Override
    public PPMdCodec defaultCodec() {
        return PPMdCodec.DEFAULT;
    }
}