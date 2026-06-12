// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.glavo.arkivo.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;
import java.util.Objects;

/// Exposes RAR archive volumes as one forward-only input stream.
@NotNullByDefault
final class RarVolumeInputStream extends InputStream {
    /// The RAR5 archive signature prefix present at the start of each volume.
    private static final byte[] RAR5_SIGNATURE = new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x01, 0x00};

    /// The volume source opened by this stream.
    private final ArkivoVolumeSource volumes;

    /// The currently open volume channel, or `null` before the first volume and after a volume EOF.
    private @Nullable SeekableByteChannel currentChannel;

    /// The next zero-based volume index to open.
    private long nextVolumeIndex;

    /// Whether the volume source has reported that no more volumes are available.
    private boolean endOfVolumes;

    /// Whether this stream is open.
    private boolean open = true;

    /// Creates a sequential input stream over the given RAR volumes.
    RarVolumeInputStream(ArkivoVolumeSource volumes) {
        this.volumes = Objects.requireNonNull(volumes, "volumes");
    }

    /// Reads one byte from the current or next volume.
    @Override
    public int read() throws IOException {
        byte[] buffer = new byte[1];
        int read = read(buffer, 0, 1);
        return read < 0 ? -1 : Byte.toUnsignedInt(buffer[0]);
    }

    /// Reads bytes from the current or next volume.
    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        ensureOpen();
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) {
            return 0;
        }

        while (true) {
            SeekableByteChannel channel = currentChannel;
            if (channel == null) {
                if (!openNextVolume()) {
                    return -1;
                }
                channel = Objects.requireNonNull(currentChannel, "currentChannel");
            }

            int read = channel.read(ByteBuffer.wrap(buffer, offset, length));
            if (read > 0) {
                return read;
            }
            if (read == 0) {
                return 0;
            }
            closeCurrentVolume();
        }
    }

    /// Closes the current volume channel.
    @Override
    public void close() throws IOException {
        if (!open && currentChannel == null) {
            return;
        }
        open = false;
        closeCurrentVolume();
    }

    /// Opens the next available volume.
    private boolean openNextVolume() throws IOException {
        if (endOfVolumes) {
            return false;
        }
        long volumeIndex = nextVolumeIndex++;
        SeekableByteChannel channel = volumes.openVolume(volumeIndex);
        if (channel == null) {
            endOfVolumes = true;
            return false;
        }
        if (volumeIndex > 0) {
            try {
                skipContinuationVolumeSignature(channel);
            } catch (IOException | RuntimeException | Error exception) {
                try {
                    channel.close();
                } catch (IOException | RuntimeException | Error closeException) {
                    exception.addSuppressed(closeException);
                }
                throw exception;
            }
        }
        currentChannel = channel;
        return true;
    }

    /// Skips and validates the RAR5 signature at the start of a continuation volume.
    private static void skipContinuationVolumeSignature(SeekableByteChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(RAR5_SIGNATURE.length);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                throw new IOException("RAR continuation volume is missing a signature");
            }
            if (read == 0) {
                throw new IOException("RAR continuation volume signature could not be read");
            }
        }
        if (!Arrays.equals(buffer.array(), RAR5_SIGNATURE)) {
            throw new IOException("Invalid RAR continuation volume signature");
        }
    }

    /// Closes and clears the current volume channel.
    private void closeCurrentVolume() throws IOException {
        SeekableByteChannel channel = currentChannel;
        if (channel != null) {
            currentChannel = null;
            channel.close();
        }
    }

    /// Requires this stream to be open.
    private void ensureOpen() throws IOException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
