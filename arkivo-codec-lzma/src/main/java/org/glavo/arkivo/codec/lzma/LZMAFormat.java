// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.CompressionFormat;
import org.jetbrains.annotations.NotNullByDefault;

/// Describes the registered LZMA-alone compression format.
///
/// LZMA-alone headers have no sufficiently reliable fixed magic, so this immutable descriptor does not claim prefix
/// matches. Callers normally select the format from a file extension or other out-of-band metadata.
@NotNullByDefault
public final class LZMAFormat implements CompressionFormat {
    /// The stable LZMA-alone format name.
    public static final String NAME = "lzma";

    /// The canonical LZMA-alone format instance.
    private static final LZMAFormat INSTANCE = new LZMAFormat();

    /// Creates a service-discoverable LZMA-alone format descriptor.
    public LZMAFormat() {
    }

    /// Returns the canonical LZMA-alone format instance.
    ///
    /// @return the canonical format instance
    public static LZMAFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable LZMA-alone format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns the default immutable LZMA-alone codec.
    @Override
    public LZMACodec defaultCodec() {
        return LZMACodec.DEFAULT;
    }
}
