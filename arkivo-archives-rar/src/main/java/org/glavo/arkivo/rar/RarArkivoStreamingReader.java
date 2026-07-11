// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar;

import org.glavo.arkivo.ArkivoStreamingReader;
import org.glavo.arkivo.rar.internal.RarArkivoStreamingReaderImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Map;
import java.util.Objects;

/// Reads RAR entries from a forward-only stream.
///
/// RAR5 AES-256 encrypted headers and single-volume stored entries use the archive-level password from
/// `RarArkivoFileSystem.PASSWORD_PROVIDER`. RAR4 encryption and encrypted entry bodies split across volumes are not
/// readable.
@NotNullByDefault
public abstract sealed class RarArkivoStreamingReader extends ArkivoStreamingReader
        permits RarArkivoStreamingReaderImpl {
    /// Creates a streaming RAR reader base instance.
    protected RarArkivoStreamingReader() {
    }

    /// Opens a streaming RAR reader from an input stream.
    public static RarArkivoStreamingReader open(InputStream source) {
        return open(source, Map.of());
    }

    /// Opens a streaming RAR reader from an input stream with environment options.
    public static RarArkivoStreamingReader open(InputStream source, Map<String, ?> environment) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        return new RarArkivoStreamingReaderImpl(
                source,
                RarArkivoFileSystem.PASSWORD_PROVIDER.read(environment)
        );
    }

    /// Opens a streaming RAR reader from a readable channel.
    public static RarArkivoStreamingReader open(ReadableByteChannel source) {
        return open(source, Map.of());
    }

    /// Opens a streaming RAR reader from a readable channel with environment options.
    public static RarArkivoStreamingReader open(
            ReadableByteChannel source,
            Map<String, ?> environment
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        return open(Channels.newInputStream(source), environment);
    }
}
