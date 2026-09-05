// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoFormat;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Describes Arkivo's read-only flattened Apple UDIF disk-image support.
///
/// DMG identification requires a seekable source because the authoritative `koly` signature is stored in the final
/// 512-byte trailer. Prefix-only detection therefore always returns `false`.
@NotNullByDefault
public final class DMGArkivoFormat implements ArkivoFormat.FileSystem {
    /// The stable format name.
    public static final String NAME = "dmg";

    /// The shared immutable descriptor.
    private static final DMGArkivoFormat INSTANCE = new DMGArkivoFormat();

    /// The big-endian `koly` signature.
    private static final int KOLY_SIGNATURE = 0x6b6f6c79;

    /// Creates the canonical descriptor.
    private DMGArkivoFormat() {
    }

    /// Returns the shared descriptor.
    ///
    /// @return the process-wide immutable descriptor
    public static DMGArkivoFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable DMG format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns the conventional DMG extension.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of("dmg");
    }

    /// Returns the trailer size used by seekable detection.
    @Override
    public int probeSize() {
        return 512;
    }

    /// Returns `false` because a prefix cannot identify a flattened UDIF image.
    @Override
    public boolean matches(ByteBuffer prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return false;
    }

    /// Checks the terminal `koly` trailer signature while restoring the borrowed channel position.
    @Override
    public boolean matches(SeekableByteChannel source) throws IOException {
        Objects.requireNonNull(source, "source");
        long originalPosition = source.position();
        @Nullable Throwable failure = null;
        try {
            long sourceSize = source.size();
            if (sourceSize - originalPosition < probeSize()) {
                return false;
            }
            byte[] signature = new byte[Integer.BYTES];
            source.position(sourceSize - probeSize());
            ByteBuffer buffer = ByteBuffer.wrap(signature);
            while (buffer.hasRemaining()) {
                int read = source.read(buffer);
                if (read < 0) {
                    return false;
                }
                if (read == 0) {
                    throw new IOException("DMG format probe made no progress");
                }
            }
            return ByteArrayAccess.readIntBigEndian(signature, 0) == KOLY_SIGNATURE;
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                source.position(originalPosition);
            } catch (IOException | RuntimeException | Error exception) {
                if (failure != null) {
                    if (failure != exception) {
                        failure.addSuppressed(exception);
                    }
                } else {
                    throw exception;
                }
            }
        }
    }

    /// Opens a path-backed DMG file system with common read options.
    @Override
    public DMGArkivoFileSystem open(Path path, ArchiveReadOptions options) throws IOException {
        return DMGArkivoFileSystem.open(
                path,
                new DMGArchiveOptions(options, DMGArchiveOptions.AUTOMATIC_PARTITION_INDEX)
        );
    }

    /// Opens a DMG file system from an owned repeatable source with common read options.
    @Override
    public DMGArkivoFileSystem open(
            ArkivoSeekableChannelSource source,
            ArchiveReadOptions options
    ) throws IOException {
        return DMGArkivoFileSystem.open(
                source,
                new DMGArchiveOptions(options, DMGArchiveOptions.AUTOMATIC_PARTITION_INDEX)
        );
    }

    /// Opens a DMG file system from one owned channel with common read options.
    @Override
    public DMGArkivoFileSystem open(
            SeekableByteChannel source,
            ArchiveReadOptions options
    ) throws IOException {
        return DMGArkivoFileSystem.open(
                source,
                new DMGArchiveOptions(options, DMGArchiveOptions.AUTOMATIC_PARTITION_INDEX)
        );
    }
}
