// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.internal.SeekableChannelSources;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ar.internal.ArArkivoFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/// Opens AR archives as NIO file systems.
///
/// Supplying `READ` and `WRITE` through `ArkivoFileSystem.OPEN_OPTIONS` opens a complete rewrite update session.
/// Closing a changed update session atomically replaces the source by default; `ArkivoFileSystem.COMMIT_TARGET` can
/// select another publication policy. AR symbol indexes are omitted from rewritten archives because member offsets
/// change; callers that require a linker index must rebuild it with a platform tool such as `ranlib`.
/// Indexed read and update sessions stage member bodies through `ArkivoFileSystem.EDIT_STORAGE`, using temporary files
/// under the system temporary directory by default. The file system owns and closes the selected edit storage.
/// Channel-source update sessions require an explicit `ArkivoFileSystem.COMMIT_TARGET` because they have no source
/// path to replace. A fixed `ArkivoCommitTarget.writeTo(Path)` publishes a derived archive without mutating the source.
@NotNullByDefault
public abstract sealed class ArArkivoFileSystem extends ArkivoFileSystem permits ArArkivoFileSystemImpl {
    /// Creates an AR archive file system base instance.
    protected ArArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }

    /// Opens an AR archive file system.
    public static ArArkivoFileSystem open(Path path) throws IOException {
        return open(path, Map.of());
    }

    /// Opens an AR archive file system with environment options.
    ///
    /// `READ` and `WRITE` select update mode. `CREATE` additionally allows a missing source archive.
    /// `ArkivoFileSystem.EDIT_STORAGE` selects storage for indexed member bodies in read and update modes.
    public static ArArkivoFileSystem open(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        return ArArkivoFileSystemProvider.instance().newFileSystem(path, environment);
    }

    /// Opens a read-only AR archive file system directly from one owned seekable channel.
    ///
    /// The channel's current position is the logical archive start. The returned file system owns and closes the
    /// channel.
    public static ArArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return open(source, Map.of());
    }

    /// Opens an AR archive file system directly from one owned seekable channel with environment options.
    ///
    /// `READ` and `WRITE` select complete-rewrite update mode and require an explicit
    /// `ArkivoFileSystem.COMMIT_TARGET`. The returned file system owns and closes the channel in all modes.
    public static ArArkivoFileSystem open(
            SeekableByteChannel source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        return SeekableChannelSources.open(source, channelSource -> open(channelSource, environment));
    }
    /// Opens a read-only AR archive file system from a repeatable seekable channel source.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    public static ArArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, Map.of());
    }

    /// Opens an AR archive file system from a repeatable seekable channel source with environment options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    /// `ArkivoFileSystem.EDIT_STORAGE` selects storage for indexed member bodies. `READ` and `WRITE` select
    /// complete-rewrite update mode and require an explicit `ArkivoFileSystem.COMMIT_TARGET`.
    public static ArArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(environment, "environment");
        return ArArkivoFileSystemImpl.open(ArArkivoFileSystemProvider.instance(), source, environment);
    }
}
