// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar;

import org.glavo.arkivo.ArkivoStreamingReader;
import org.glavo.arkivo.rar.internal.RarArkivoStreamingReaderImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Reads RAR entries from a forward-only stream.
@NotNullByDefault
public abstract sealed class RarArkivoStreamingReader extends ArkivoStreamingReader
        permits RarArkivoStreamingReaderImpl {
    /// Creates a streaming RAR reader base instance.
    protected RarArkivoStreamingReader() {
    }

    /// Opens a streaming RAR reader from an input stream.
    public static RarArkivoStreamingReader open(InputStream source) {
        return new RarArkivoStreamingReaderImpl(Objects.requireNonNull(source, "source"));
    }

    /// Opens a streaming RAR reader from a readable channel.
    public static RarArkivoStreamingReader open(ReadableByteChannel source) {
        Objects.requireNonNull(source, "source");
        return open(Channels.newInputStream(source));
    }
}
