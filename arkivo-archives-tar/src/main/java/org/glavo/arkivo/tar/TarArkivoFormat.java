// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.tar;

import org.glavo.arkivo.ArkivoFormat;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;

/// Describes TAR archive streaming support provided by Arkivo.
@NotNullByDefault
public final class TarArkivoFormat implements ArkivoFormat {
    /// The stable TAR format name.
    public static final String NAME = "tar";

    /// The shared TAR format instance.
    private static final TarArkivoFormat INSTANCE = new TarArkivoFormat();

    /// Creates a TAR format descriptor.
    public TarArkivoFormat() {
    }

    /// Returns the shared TAR format descriptor.
    public static TarArkivoFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable TAR format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Opens a streaming TAR reader from an input stream.
    public TarArkivoStreamingReader openStreamingReader(InputStream source) {
        return TarArkivoStreamingReader.open(source);
    }

    /// Opens a streaming TAR reader from a readable channel.
    public TarArkivoStreamingReader openStreamingReader(ReadableByteChannel source) {
        return TarArkivoStreamingReader.open(source);
    }
}
