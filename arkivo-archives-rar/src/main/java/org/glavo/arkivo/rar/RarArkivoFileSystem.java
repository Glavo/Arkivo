// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemOption;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.ArkivoSeekableChannelSource;
import org.glavo.arkivo.ArkivoVolumeSource;
import org.glavo.arkivo.rar.internal.RarArkivoFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/// Opens RAR archives as read-only NIO file systems.
///
/// Readable stored-entry bodies are cached through `ArkivoFileSystem.EDIT_STORAGE`, using temporary files under the
/// system temporary directory by default. The file system owns and closes the selected edit storage. RAR 3.x AES-128
/// and RAR5 AES-256 encrypted headers and single-volume stored entries are supported through `PASSWORD_PROVIDER`.
/// Legacy RAR encryption before AES and encrypted entry bodies split across volumes are not readable.
@NotNullByDefault
public abstract sealed class RarArkivoFileSystem extends ArkivoFileSystem permits RarArkivoFileSystemImpl {
    /// The environment option for an `ArkivoPasswordProvider` whose archive-level password decrypts RAR data.
    ///
    /// RAR3 and RAR4 interpret provider bytes as UTF-16LE. RAR5 interprets provider bytes as UTF-8.
    public static final ArkivoFileSystemOption<ArkivoPasswordProvider> PASSWORD_PROVIDER =
            ArkivoFileSystemOption.of("arkivo.rar", "passwordProvider", ArkivoPasswordProvider.class);

    /// Creates a RAR archive file system base instance.
    protected RarArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }

    /// Opens a RAR archive file system.
    public static RarArkivoFileSystem open(Path path) throws IOException {
        return open(path, Map.of());
    }

    /// Opens a RAR archive file system with environment options.
    ///
    /// `ArkivoFileSystem.EDIT_STORAGE` selects storage for cached readable entry bodies. `PASSWORD_PROVIDER` supplies
    /// passwords for encrypted RAR headers and stored entries.
    public static RarArkivoFileSystem open(Path path, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        return RarArkivoFileSystemProvider.instance().newFileSystem(path, environment);
    }

    /// Opens a read-only RAR archive file system from a repeatable seekable channel source.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    public static RarArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, Map.of());
    }

    /// Opens a read-only RAR archive file system from a repeatable seekable channel source with environment options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    /// `ArkivoFileSystem.EDIT_STORAGE` selects storage for cached readable entry bodies. `PASSWORD_PROVIDER` supplies
    /// passwords for encrypted RAR headers and stored entries.
    public static RarArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            Map<String, ?> environment
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        return open((ArkivoVolumeSource) source, environment);
    }

    /// Opens a multi-volume RAR archive file system.
    public static RarArkivoFileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return open(volumes, Map.of());
    }

    /// Opens a multi-volume RAR archive file system with environment options.
    ///
    /// The returned file system owns the volume source and selected edit storage after this method returns successfully.
    /// `ArkivoFileSystem.EDIT_STORAGE` selects storage for cached readable entry bodies. `PASSWORD_PROVIDER` supplies
    /// the archive-level password for encrypted RAR headers and stored entries.
    public static RarArkivoFileSystem open(ArkivoVolumeSource volumes, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(volumes, "volumes");
        Objects.requireNonNull(environment, "environment");
        return RarArkivoFileSystemImpl.open(RarArkivoFileSystemProvider.instance(), volumes, environment);
    }
}
