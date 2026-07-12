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
/// Readable entry bodies are cached through `ArkivoFileSystem.EDIT_STORAGE`, using temporary files under the system
/// temporary directory by default. The file system owns and closes the selected edit storage. RAR4 compression methods
/// 0 through 5 with extraction versions 15, 20, 26, 29, and 36 are readable in LZ mode, including RAR 2.x adaptive
/// audio and RAR3 virtual-machine filters. RAR3 PPM blocks are rejected. RAR5 compression methods 0 through 5 with
/// algorithm versions 0 and 1 and dictionaries up to 768 MiB are also readable. Both formats support solid and split compressed
/// entries. RAR 3.x AES-128 and RAR5 AES-256 encrypted headers and supported entries, including split entries, use
/// `PASSWORD_PROVIDER`. Legacy RAR 1.3, 1.5, and 2.x file-data encryption is also readable for otherwise supported entries.
/// Cached readable bodies validate available CRC32 values and RAR5 BLAKE2sp hashes over unpacked plaintext, including
/// password-dependent checksums on encrypted entries.
@NotNullByDefault
/// RAR5 hard-link and file-copy records targeting an earlier regular file expose the target's cached content while
/// retaining their own entry metadata.
public abstract sealed class RarArkivoFileSystem extends ArkivoFileSystem permits RarArkivoFileSystemImpl {
    /// The environment option for an `ArkivoPasswordProvider` whose archive-level password decrypts RAR data.
    ///
    /// Legacy RAR 1.3 through 2.x treats provider bytes as a raw single-byte password terminated by the first zero byte.
    /// RAR 3.x AES treats provider bytes as UTF-16LE, and RAR5 treats provider bytes as UTF-8.
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
    /// passwords for encrypted RAR headers and readable entries.
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
    /// passwords for encrypted RAR headers and readable entries.
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
    /// the archive-level password for encrypted RAR headers and readable entries.
    public static RarArkivoFileSystem open(ArkivoVolumeSource volumes, Map<String, ?> environment) throws IOException {
        Objects.requireNonNull(volumes, "volumes");
        Objects.requireNonNull(environment, "environment");
        return RarArkivoFileSystemImpl.open(RarArkivoFileSystemProvider.instance(), volumes, environment);
    }
}
