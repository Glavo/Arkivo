// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CompressionFormat;
import org.jetbrains.annotations.NotNullByDefault;

/// Describes the registered raw Deflate compression format.
///
/// Raw Deflate has no reliable fixed signature, so this immutable descriptor does not claim prefix matches. Callers
/// must select the format from container metadata or other out-of-band information.
@NotNullByDefault
public final class DeflateFormat implements CompressionFormat {
    /// The stable raw Deflate format name.
    public static final String NAME = "deflate";

    /// The canonical raw Deflate format instance.
    private static final DeflateFormat INSTANCE = new DeflateFormat();

    /// Creates a service-discoverable raw Deflate format descriptor.
    public DeflateFormat() {
    }

    /// Returns the canonical raw Deflate format instance.
    ///
    /// @return the shared immutable format descriptor
    public static DeflateFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable raw Deflate format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns the default immutable raw Deflate codec.
    @Override
    public DeflateCodec defaultCodec() {
        return DeflateCodec.DEFAULT;
    }
}
