// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.internal.ArchiveOptions;
import org.glavo.arkivo.archive.internal.SeekableChannelSources;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.internal.ArchiveOption;
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
/// Opening a file system indexes entry metadata without retaining decoded bodies. On its first content access, a readable
/// body is decoded and cached through storage created by
/// [org.glavo.arkivo.archive.ArchiveReadOptions#editStorageFactory()]; later accesses share that cache. The file system
/// owns and closes the selected edit storage, deferring cached-body cleanup while a channel still uses it. An uncached
/// body requires the owned archive source to remain reopenable; reaching a solid entry may decode preceding solid
/// members again to reconstruct its dictionary.
///
/// RAR4 compression methods 0 through 5 with extraction versions 15, 20, 26, 29, and 36 are readable, including legacy
/// LZ modes, RAR 2.x adaptive audio, RAR3 PPMd blocks, and RAR3 virtual-machine filters. RAR5 compression methods 0 through
/// 5 with algorithm versions 0 and 1 and dictionaries up to 768 MiB are also readable. Both formats support solid and
/// split compressed entries.
/// RAR 3.x AES-128 and RAR5 AES-256 encrypted headers and supported entries, including split entries, use
/// [RarArchiveOptions.Read#passwordProvider()]. Legacy RAR 1.3, 1.5, and 2.x file-data encryption is also readable for
/// otherwise supported entries.
/// Materialized bodies validate available CRC32 values and RAR5 BLAKE2sp hashes over unpacked plaintext, including
/// password-dependent checksums on encrypted entries. RAR5 hard-link and file-copy records targeting an indexed regular
/// file retain their own metadata while sharing the target's lazily materialized content.
///
/// Paths and attributes are snapshots of the indexed headers and never observe later external archive changes. Because
/// uncached bodies reopen the indexed byte ranges, the path, channel source, or volume source must continue to expose
/// the same archive bytes until the file system closes. A successfully returned channel- or volume-backed file system
/// owns and closes that source; path factories own their internally opened channels. The common read option selects
/// whether reads may run concurrently and whether close force-closes active entry resources.
@NotNullByDefault
public abstract sealed class RarArkivoFileSystem extends ArkivoFileSystem permits RarArkivoFileSystemImpl {
    /// The option for an `ArkivoPasswordProvider` whose archive-level password decrypts RAR data.
    ///
    /// Legacy RAR 1.3 through 2.x treats provider bytes as a raw single-byte password terminated by the first zero byte.
    /// RAR 3.x AES treats provider bytes as UTF-16LE, and RAR5 treats provider bytes as UTF-8.
    private static final ArchiveOption<ArkivoPasswordProvider> PASSWORD_PROVIDER =
            ArchiveOption.of("arkivo.rar", "passwordProvider", ArkivoPasswordProvider.class);

    /// The option for the detector used to select charsets for legacy non-Unicode RAR4 entry names.
    private static final ArchiveOption<ArchiveMetadataCharsetDetector> LEGACY_CHARSET_DETECTOR =
            ArchiveOption.of(
                    "arkivo.rar",
                    "legacyCharsetDetector",
                    ArchiveMetadataCharsetDetector.class,
                    RarArkivoFileSystem::legacyCharsetDetectorOptionValue
            );

    /// Creates a RAR archive file system base instance.
    ///
    /// @param threadSafety the operation-coordination strategy for this file system
    protected RarArkivoFileSystem(ArkivoFileSystemThreadSafety threadSafety) {
        super(threadSafety);
    }

    /// Opens a RAR archive file system.
    ///
    /// @param path the path of the first or only RAR volume
    /// @return a read-only file system for the archive
    /// @throws IOException if the archive or any discovered volume cannot be opened or parsed
    public static RarArkivoFileSystem open(Path path) throws IOException {
        return open(path, RarArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a RAR archive file system with read options.
    ///
    /// @param path the path of the first or only RAR volume
    /// @param options the read configuration
    /// @return a read-only file system for the archive
    /// @throws IOException if the archive or any discovered volume cannot be opened or parsed
    public static RarArkivoFileSystem open(Path path, RarArchiveOptions.Read options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return RarArkivoFileSystemProvider.instance().openPath(path, toLegacyOptions(options));
    }

    /// Opens a read-only RAR archive file system directly from one owned seekable channel.
    ///
    /// The channel's current position is the logical archive start. The returned file system owns and closes the
    /// channel.
    ///
    /// @param source the channel whose current position is the archive start
    /// @return a read-only file system that owns `source`
    /// @throws IOException if the archive cannot be read; setup failure closes `source`
    public static RarArkivoFileSystem open(SeekableByteChannel source) throws IOException {
        return open(source, RarArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a read-only RAR archive file system directly from one owned seekable channel with options.
    ///
    /// @param source the channel whose current position is the archive start
    /// @param options the read configuration
    /// @return a read-only file system that owns `source`
    /// @throws IOException if the archive cannot be read; setup failure closes `source`
    public static RarArkivoFileSystem open(
            SeekableByteChannel source,
            RarArchiveOptions.Read options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return SeekableChannelSources.open(source, channelSource -> open(channelSource, options));
    }

    /// Opens a read-only RAR archive file system from a repeatable seekable channel source.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    ///
    /// @param source the repeatable logical archive source
    /// @return a read-only file system that owns `source`
    /// @throws IOException if the archive cannot be opened; setup failure closes `source`
    public static RarArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return open(source, RarArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a read-only RAR archive file system from a repeatable seekable channel source with options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    ///
    /// @param source the repeatable logical archive source
    /// @param options the read configuration
    /// @return a read-only file system that owns `source`
    /// @throws IOException if the archive cannot be opened; setup failure closes `source`
    public static RarArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            RarArchiveOptions.Read options
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        return open((ArkivoVolumeSource) source, options);
    }

    /// Opens a multi-volume RAR archive file system.
    ///
    /// @param volumes the ordered, repeatable archive volume source
    /// @return a read-only file system that owns `volumes`
    /// @throws IOException if a volume cannot be opened or the archive cannot be parsed; setup failure closes `volumes`
    public static RarArkivoFileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return open(volumes, RarArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a multi-volume RAR archive file system with options.
    ///
    /// The returned file system owns the volume source and selected edit storage after this method returns successfully.
    ///
    /// @param volumes the ordered, repeatable archive volume source
    /// @param options the read configuration
    /// @return a read-only file system that owns `volumes`
    /// @throws IOException if a volume cannot be opened or the archive cannot be parsed; setup failure closes `volumes`
    public static RarArkivoFileSystem open(ArkivoVolumeSource volumes, RarArchiveOptions.Read options) throws IOException {
        Objects.requireNonNull(volumes, "volumes");
        Objects.requireNonNull(options, "options");
        return RarArkivoFileSystemImpl.open(
                RarArkivoFileSystemProvider.instance(),
                volumes,
                toLegacyOptions(options)
        );
    }

    /// Converts strongly typed RAR read settings for the internal parser.
    static ArchiveOptions toLegacyOptions(RarArchiveOptions.Read options) {
        ArchiveOptions result = ArchiveOptions.fromReadOptions(options.common())
                .with(LEGACY_CHARSET_DETECTOR, options.legacyCharsetDetector());
        return options.passwordProvider() == null
                ? result
                : result.with(PASSWORD_PROVIDER, options.passwordProvider());
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
