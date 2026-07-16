// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.ArchiveOptions;
import org.glavo.arkivo.archive.internal.SeekableChannelSources;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArchiveOption;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.rar.internal.RarArkivoFileSystemProvider;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.rar.internal.RarArkivoFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.Objects;

/// Opens RAR archives as read-only NIO file systems.
/// Opening a file system indexes entry metadata without retaining decoded bodies. A readable body is decoded and cached
/// through `ArkivoFileSystem.EDIT_STORAGE` on its first content access, and later accesses share that cache. The file
/// system owns and closes the selected edit storage, deferring cached-body cleanup while a channel still uses it. An
/// uncached body requires the owned archive source to remain reopenable; reaching a solid entry may decode preceding
/// solid members again to reconstruct its dictionary.
///
/// RAR4 compression methods 0 through 5 with extraction versions 15, 20, 26, 29, and 36 are readable, including legacy
/// LZ modes, RAR 2.x adaptive audio, RAR3 PPMd blocks, and RAR3 virtual-machine filters. RAR5 compression methods 0 through
/// 5 with algorithm versions 0 and 1 and dictionaries up to 768 MiB are also readable. Both formats support solid and
/// split compressed entries.
/// RAR 3.x AES-128 and RAR5 AES-256 encrypted headers and supported entries, including split entries, use
/// `PASSWORD_PROVIDER`. Legacy RAR 1.3, 1.5, and 2.x file-data encryption is also readable for otherwise supported entries.
/// Materialized bodies validate available CRC32 values and RAR5 BLAKE2sp hashes over unpacked plaintext, including
/// password-dependent checksums on encrypted entries. RAR5 hard-link and file-copy records targeting an indexed regular
/// file retain their own metadata while sharing the target's lazily materialized content.
@NotNullByDefault
public abstract sealed class RarArkivoFileSystem extends ArkivoFileSystem permits RarArkivoFileSystemImpl {
    /// The option for an `ArkivoPasswordProvider` whose archive-level password decrypts RAR data.
    ///
    /// Legacy RAR 1.3 through 2.x treats provider bytes as a raw single-byte password terminated by the first zero byte.
    /// RAR 3.x AES treats provider bytes as UTF-16LE, and RAR5 treats provider bytes as UTF-8.
    public static final ArchiveOption<ArkivoPasswordProvider> PASSWORD_PROVIDER =
            ArchiveOption.of("arkivo.rar", "passwordProvider", ArkivoPasswordProvider.class);

    /// The option for the detector used to select charsets for legacy non-Unicode RAR4 entry names.
    public static final ArchiveOption<ArchiveMetadataCharsetDetector> LEGACY_CHARSET_DETECTOR =
            ArchiveOption.of(
                    "arkivo.rar",
                    "legacyCharsetDetector",
                    ArchiveMetadataCharsetDetector.class,
                    RarArkivoFileSystem::legacyCharsetDetectorOptionValue
            );

    /// Creates a RAR archive file system base instance.
    protected RarArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }

    /// Opens a RAR archive file system.
    public static RarArkivoFileSystem open(Path path) throws IOException {
        return open(path, ArchiveOptions.EMPTY);
    }

    /// Opens a RAR archive file system with options.
    ///
    /// `ArkivoFileSystem.EDIT_STORAGE` selects storage for readable entry bodies materialized on first access.
    /// `PASSWORD_PROVIDER` supplies passwords for encrypted RAR headers and readable entries.
    public static RarArkivoFileSystem open(Path path, ArchiveOptions options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return RarArkivoFileSystemProvider.instance().openPath(path, options);
    }

    /// Opens a read-only RAR archive file system directly from one owned seekable channel.
    ///
    /// The channel's current position is the logical archive start. The returned file system owns and closes the
    /// channel.
    public static RarArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return open(source, ArchiveOptions.EMPTY);
    }

    /// Opens a read-only RAR archive file system directly from one owned seekable channel with options.
    public static RarArkivoFileSystem open(
            SeekableByteChannel source,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return SeekableChannelSources.open(source, channelSource -> open(channelSource, options));
    }

    /// Opens a read-only RAR archive file system from a repeatable seekable channel source.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    public static RarArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, ArchiveOptions.EMPTY);
    }

    /// Opens a read-only RAR archive file system from a repeatable seekable channel source with options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    /// `ArkivoFileSystem.EDIT_STORAGE` selects storage for readable entry bodies materialized on first access.
    /// `PASSWORD_PROVIDER` supplies passwords for encrypted RAR headers and readable entries.
    public static RarArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            ArchiveOptions options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        return open((ArkivoVolumeSource) source, options);
    }

    /// Opens a multi-volume RAR archive file system.
    public static RarArkivoFileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return open(volumes, ArchiveOptions.EMPTY);
    }

    /// Opens a multi-volume RAR archive file system with options.
    ///
    /// The returned file system owns the volume source and selected edit storage after this method returns successfully.
    /// `ArkivoFileSystem.EDIT_STORAGE` selects storage for readable entry bodies materialized on first access.
    /// `PASSWORD_PROVIDER` supplies the archive-level password for encrypted RAR headers and readable entries.
    public static RarArkivoFileSystem open(ArkivoVolumeSource volumes, ArchiveOptions options) throws IOException {
        Objects.requireNonNull(volumes, "volumes");
        Objects.requireNonNull(options, "options");
        return RarArkivoFileSystemImpl.open(RarArkivoFileSystemProvider.instance(), volumes, options);
    }

    /// Converts a raw legacy charset detector option value.
    private static ArchiveMetadataCharsetDetector legacyCharsetDetectorOptionValue(Object value) {
        if (value instanceof ArchiveMetadataCharsetDetector detector) {
            return detector;
        }
        if (value instanceof Charset charset) {
            return ArchiveMetadataCharsetDetector.fixed(charset);
        }
        if (value instanceof String stringValue) {
            return ArchiveMetadataCharsetDetector.fixed(Charset.forName(stringValue));
        }
        throw new IllegalArgumentException(
                "Expected ArchiveMetadataCharsetDetector, Charset, or String for key: "
                        + LEGACY_CHARSET_DETECTOR.key()
        );
    }
}
