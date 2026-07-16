// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.xz.XZCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;

/// Describes the discoverable XZ compression format.
@NotNullByDefault
public final class XZCompressionFormat implements CompressionFormat {
    /// The canonical XZ format instance.
    private static final XZCompressionFormat INSTANCE = new XZCompressionFormat();

    /// The XZ stream header magic bytes.
    private static final byte @Unmodifiable [] HEADER_MAGIC = {
            (byte) 0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00
    };

    /// Creates a stateless format descriptor for service discovery.
    public XZCompressionFormat() {
    }

    /// Returns the canonical XZ format instance.
    public static XZCompressionFormat instance() {
        return INSTANCE;
    }

    /// Supplies the canonical XZ format to service discovery.
    public static CompressionFormat provider() {
        return INSTANCE;
    }

    /// Returns the stable XZ format name.
    @Override
    public String name() {
        return XZCodec.NAME;
    }

    /// Returns the number of leading bytes used to identify XZ streams.
    @Override
    public int probeSize() {
        return HEADER_MAGIC.length;
    }

    /// Returns whether the given prefix starts with the XZ stream signature.
    @Override
    public boolean matches(ByteBuffer prefix) {
        int position = prefix.position();
        if (prefix.remaining() < HEADER_MAGIC.length) {
            return false;
        }

        for (int index = 0; index < HEADER_MAGIC.length; index++) {
            if (prefix.get(position + index) != HEADER_MAGIC[index]) {
                return false;
            }
        }
        return true;
    }

    /// Returns the default immutable XZ codec.
    @Override
    public CompressionCodec defaultCodec() {
        return XZCodec.DEFAULT;
    }
}
