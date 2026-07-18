// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzip;

import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.lzip.internal.LzipSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.util.List;

/// Describes the discoverable lzip compression format.
@NotNullByDefault
public final class LzipFormat implements CompressionFormat {
    /// The stable lzip format name.
    public static final String NAME = "lzip";

    /// The canonical lzip format instance.
    private static final LzipFormat INSTANCE = new LzipFormat();

    /// Creates a service-discoverable lzip format descriptor.
    public LzipFormat() {
    }

    /// Returns the canonical lzip format instance.
    public static LzipFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable lzip format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns common lzip and tar-lzip file extensions.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of("lz", "tlz");
    }

    /// Returns the number of leading bytes used to identify lzip members.
    @Override
    public int probeSize() {
        return LzipSupport.MAGIC.length;
    }

    /// Returns whether the supplied prefix starts with the lzip member signature.
    @Override
    public boolean matches(ByteBuffer prefix) {
        int position = prefix.position();
        if (prefix.remaining() < LzipSupport.MAGIC.length) {
            return false;
        }
        for (int index = 0; index < LzipSupport.MAGIC.length; index++) {
            if (prefix.get(position + index) != LzipSupport.MAGIC[index]) {
                return false;
            }
        }
        return true;
    }

    /// Returns the default immutable lzip codec.
    @Override
    public LzipCodec defaultCodec() {
        return LzipCodec.DEFAULT;
    }
}
