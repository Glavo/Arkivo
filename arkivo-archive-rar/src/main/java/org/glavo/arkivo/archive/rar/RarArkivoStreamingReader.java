// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.rar.internal.RarArkivoStreamingReaderImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.Objects;

/// Reads RAR entries from a forward-only stream.
///
/// RAR4 compression methods 0 through 5 with extraction versions 15, 20, 26, 29, and 36 are readable, including legacy LZ
/// modes, RAR 2.x adaptive audio, RAR3 PPMd blocks, and RAR3 virtual-machine filters. RAR5 compression methods 0 through
/// 5 with algorithm versions 0 and 1 and dictionaries up to 768 MiB are also readable.
/// Both formats preserve solid decompression state and can span split entry data across physical volumes. Legacy RAR 1.3,
/// 1.5, and 2.x file-data encryption, RAR 3.x AES-128 encryption, and RAR5 AES-256 encryption are readable for otherwise
/// supported entries. Encrypted headers are supported for RAR 3.x and RAR5. Encryption uses the archive-level password
/// from [RarArchiveOptions.Read#passwordProvider()]. Legacy RAR 1.3 through 2.x treats provider bytes as a raw single-byte
/// password terminated by the first zero byte; RAR 3.x AES treats them as UTF-16LE, and RAR5 treats them as UTF-8.
/// Advancing past a compressed entry in a solid archive decodes and discards its body when possible to preserve the
/// dictionary required by later entries.
/// Readable bodies validate available CRC32 values and RAR5 BLAKE2sp hashes over unpacked plaintext before accepting
/// logical end of input. Password-dependent RAR5 checksums are verified with the key derived from the supplied archive
/// password.
/// RAR5 hard-link and file-copy records expose only their own physical data area, which is normally empty; use
/// `RarArkivoFileSystem` when their target content must be resolved.
@NotNullByDefault
public abstract sealed class RarArkivoStreamingReader extends ArkivoStreamingReader
        permits RarArkivoStreamingReaderImpl {
    /// Creates a streaming RAR reader base instance.
    protected RarArkivoStreamingReader() {
    }

    /// Opens a streaming RAR reader from its first path and discovers conventional split volumes.
    public static RarArkivoStreamingReader open(Path path) throws IOException {
        return open(path, RarArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a configured streaming RAR reader from its first path and discovers conventional split volumes.
    public static RarArkivoStreamingReader open(
            Path path,
            RarArchiveOptions.Read options
    ) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return open(RarArkivoFormat.instance().openVolumeSource(path), options);
    }

    /// Opens a streaming RAR reader from a multi-volume source.
    public static RarArkivoStreamingReader open(ArkivoVolumeSource source) {
        return open(source, RarArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a streaming RAR reader from a multi-volume source with options.
    ///
    /// The returned reader owns the source and every physical volume channel it opens.
    public static RarArkivoStreamingReader open(
            ArkivoVolumeSource source,
            RarArchiveOptions.Read options
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return new RarArkivoStreamingReaderImpl(
                source,
                options.passwordProvider(),
                RarArkivoFileSystem.toLegacyOptions(options)
        );
    }

    /// Opens a streaming RAR reader from an input stream.
    public static RarArkivoStreamingReader open(InputStream source) {
        return open(source, RarArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a streaming RAR reader from an input stream with options.
    public static RarArkivoStreamingReader open(InputStream source, RarArchiveOptions.Read options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return new RarArkivoStreamingReaderImpl(
                source,
                options.passwordProvider(),
                RarArkivoFileSystem.toLegacyOptions(options)
        );
    }

    /// Opens a streaming RAR reader from a readable channel.
    public static RarArkivoStreamingReader open(ReadableByteChannel source) {
        return open(source, RarArchiveOptions.READ_DEFAULTS);
    }

    /// Opens a streaming RAR reader from a readable channel with options.
    public static RarArkivoStreamingReader open(
            ReadableByteChannel source,
            RarArchiveOptions.Read options
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return new RarArkivoStreamingReaderImpl(
                StreamChannelAdapters.inputStream(source),
                options.passwordProvider(),
                RarArkivoFileSystem.toLegacyOptions(options)
        );
    }
}
