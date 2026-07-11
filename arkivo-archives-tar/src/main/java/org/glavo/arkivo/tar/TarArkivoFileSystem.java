// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.tar;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemOption;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.ArkivoSeekableChannelSource;
import org.glavo.arkivo.compress.CompressionCodec;
import org.glavo.arkivo.compress.CompressionCodecs;
import org.glavo.arkivo.tar.internal.TarArkivoFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/// Opens TAR archives as NIO file systems.
///
/// Supplying `READ` and `WRITE` through `ArkivoFileSystem.OPEN_OPTIONS` opens a complete rewrite update session.
/// Closing a changed update session atomically replaces the source by default; `ArkivoFileSystem.COMMIT_TARGET` can
/// select another publication policy.
/// Indexed read and update sessions stage entry bodies through `ArkivoFileSystem.EDIT_STORAGE`, using temporary files
/// under the system temporary directory by default. The file system owns and closes the selected edit storage.
/// GNU sparse entries are staged as expanded logical files; an update commit normalizes old GNU `S` entries to regular
/// TAR entries while preserving their expanded content and metadata.
@NotNullByDefault
public abstract sealed class TarArkivoFileSystem extends ArkivoFileSystem permits TarArkivoFileSystemImpl {
    /// The environment option for a compression codec wrapping the TAR byte stream.
    ///
    /// Values may be a CompressionCodec or stable codec name. Existing seekable archives auto-detect installed codecs
    /// when this option is absent, while forward-only streaming readers treat an absent option as uncompressed because
    /// they cannot reliably undo a false compression match. New archives remain uncompressed when it is absent.
    public static final ArkivoFileSystemOption<CompressionCodec> COMPRESSION =
            ArkivoFileSystemOption.of(
                    "arkivo.tar",
                    "compression",
                    CompressionCodec.class,
                    TarArkivoFileSystem::compressionOptionValue
            );

    /// Creates a TAR archive file system base instance.
    protected TarArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }

    /// Opens a TAR archive file system.
    public static TarArkivoFileSystem open(Path path) throws IOException {
        return open(path, Map.of());
    }

    /// Opens a TAR archive file system with environment options.
    ///
    /// `READ` and `WRITE` select update mode. `CREATE` additionally allows a missing source archive.
    /// `ArkivoFileSystem.EDIT_STORAGE` selects storage for indexed entry bodies in read and update modes.
    public static TarArkivoFileSystem open(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        return TarArkivoFileSystemProvider.instance().newFileSystem(path, environment);
    }

    /// Opens a read-only TAR archive file system from a repeatable seekable channel source.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    public static TarArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, Map.of());
    }

    /// Opens a read-only TAR archive file system from a repeatable seekable channel source with environment options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    /// `ArkivoFileSystem.EDIT_STORAGE` selects storage for indexed entry bodies.
    public static TarArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        return TarArkivoFileSystemImpl.open(TarArkivoFileSystemProvider.instance(), source, environment);
    }

    /// Converts a raw compression option value.
    private static CompressionCodec compressionOptionValue(Object value) {
        if (value instanceof CompressionCodec codec) {
            return codec;
        }
        if (value instanceof String name) {
            @Nullable CompressionCodec codec = CompressionCodecs.find(name);
            if (codec != null) {
                return codec;
            }
            throw new IllegalArgumentException("Compression codec is not installed: " + name);
        }
        throw new IllegalArgumentException(
                "Expected CompressionCodec or String for key: " + COMPRESSION.key()
        );
    }
}
