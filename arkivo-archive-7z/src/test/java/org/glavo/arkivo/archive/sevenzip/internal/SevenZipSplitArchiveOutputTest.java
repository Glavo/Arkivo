// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.ArkivoVolumeOutput;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.glavo.arkivo.archive.sevenzip.SevenZipCompression;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilterChain;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies split 7z publication boundaries, terminal failures, and retryable transaction cleanup.
@NotNullByDefault
final class SevenZipSplitArchiveOutputTest {
    /// The fixed 7z signature at the start of every assembled archive.
    private static final byte @Unmodifiable [] SIGNATURE = {
            '7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c
    };

    /// Verifies a completed archive is published in bounded volumes exactly once.
    @Test
    void publishesBoundedVolumesOnce() throws IOException {
        RecordingVolumeOutput physicalOutput = new RecordingVolumeOutput();
        RecordingVolumeTarget target = new RecordingVolumeTarget(physicalOutput);
        SevenZipSplitArchiveOutput output = completedOutput(target, 8L);

        output.commit();
        List<byte[]> volumes = physicalOutput.volumeBytes();
        assertTrue(volumes.size() > 1);
        for (byte[] volume : volumes) {
            assertTrue(volume.length > 0);
            assertTrue(volume.length <= 8);
        }
        byte[] archive = concatenate(volumes);
        byte[] actualSignature = new byte[SIGNATURE.length];
        System.arraycopy(archive, 0, actualSignature, 0, actualSignature.length);
        assertArrayEquals(SIGNATURE, actualSignature);
        assertTrue(physicalOutput.committed);
        assertEquals(volumes.size() - 1L, physicalOutput.finalVolumeIndex);
        assertTrue(physicalOutput.allChannelsClosed());
        assertEquals(1, target.openAttempts);
        assertEquals(1, physicalOutput.commitAttempts);
        assertEquals(1, physicalOutput.closeAttempts);

        output.commit();
        output.rollback();
        assertEquals(1, target.openAttempts);
        assertEquals(1, physicalOutput.commitAttempts);
        assertEquals(1, physicalOutput.closeAttempts);
    }

    /// Verifies failure to open the destination makes publication terminal after temporary cleanup.
    @Test
    void targetOpenFailureDoesNotRestartPublication() throws IOException {
        RecordingVolumeOutput physicalOutput = new RecordingVolumeOutput();
        RecordingVolumeTarget target = new RecordingVolumeTarget(physicalOutput);
        target.openFailures = 1;
        SevenZipSplitArchiveOutput output = completedOutput(target, 64L);

        IOException failure = assertThrows(IOException.class, output::commit);
        assertEquals("open output failure", failure.getMessage());
        assertEquals(1, target.openAttempts);
        assertEquals(0, physicalOutput.commitAttempts);

        output.commit();
        output.rollback();
        assertEquals(1, target.openAttempts);
    }

    /// Verifies failed rollback and close operations are retried without publishing the archive again.
    @Test
    void retriesCleanupWithoutRestartingPublication() throws IOException {
        RecordingVolumeOutput physicalOutput = new RecordingVolumeOutput();
        physicalOutput.commitFailures = 1;
        physicalOutput.rollbackFailures = 1;
        physicalOutput.closeFailures = 1;
        RecordingVolumeTarget target = new RecordingVolumeTarget(physicalOutput);
        SevenZipSplitArchiveOutput output = completedOutput(target, 64L);

        IOException failure = assertThrows(IOException.class, output::commit);
        assertEquals("commit failure", failure.getMessage());
        assertEquals(2, failure.getSuppressed().length);
        assertEquals("rollback failure", failure.getSuppressed()[0].getMessage());
        assertEquals("output close failure", failure.getSuppressed()[1].getMessage());
        assertEquals(1, target.openAttempts);
        assertEquals(1, physicalOutput.commitAttempts);
        assertEquals(1, physicalOutput.rollbackAttempts);
        assertEquals(1, physicalOutput.closeAttempts);

        output.commit();
        assertTrue(physicalOutput.rolledBack);
        assertEquals(1, target.openAttempts);
        assertEquals(1, physicalOutput.commitAttempts);
        assertEquals(2, physicalOutput.rollbackAttempts);
        assertEquals(2, physicalOutput.closeAttempts);

        output.commit();
        output.rollback();
        assertEquals(2, physicalOutput.rollbackAttempts);
        assertEquals(2, physicalOutput.closeAttempts);
    }

    /// Verifies rollback recovers a volume channel whose first close attempt failed during publication.
    @Test
    void rollbackRecoversVolumeCloseFailure() throws IOException {
        RecordingVolumeOutput physicalOutput = new RecordingVolumeOutput();
        physicalOutput.firstVolumeCloseFailures = 1;
        RecordingVolumeTarget target = new RecordingVolumeTarget(physicalOutput);
        SevenZipSplitArchiveOutput output = completedOutput(target, Long.MAX_VALUE);

        IOException failure = assertThrows(IOException.class, output::commit);
        assertEquals("volume close failure", failure.getMessage());
        assertEquals(0, physicalOutput.commitAttempts);
        assertEquals(1, physicalOutput.rollbackAttempts);
        assertEquals(1, physicalOutput.closeAttempts);
        assertTrue(physicalOutput.rolledBack);
        assertTrue(physicalOutput.allChannelsClosed());
        assertEquals(2, physicalOutput.channels.get(0).closeAttempts);

        output.commit();
        assertEquals(1, target.openAttempts);
        assertEquals(1, physicalOutput.rollbackAttempts);
    }

    /// Verifies rollback before publication removes assembly state without opening the target.
    @Test
    void rollbackBeforePublicationSkipsTarget() throws IOException {
        RecordingVolumeOutput physicalOutput = new RecordingVolumeOutput();
        RecordingVolumeTarget target = new RecordingVolumeTarget(physicalOutput);
        SevenZipSplitArchiveOutput output = completedOutput(target, 64L);

        output.rollback();
        output.rollback();
        output.commit();

        assertEquals(0, target.openAttempts);
        assertEquals(0, physicalOutput.commitAttempts);
        assertEquals(0, physicalOutput.rollbackAttempts);
        assertEquals(0, physicalOutput.closeAttempts);
    }

    /// Opens and finalizes an empty temporary 7z archive ready for publication.
    private static SevenZipSplitArchiveOutput completedOutput(
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        SevenZipSplitArchiveOutput output = SevenZipSplitArchiveOutput.open(
                target,
                splitSize,
                null,
                SevenZipCompression.copy(),
                SevenZipFilterChain.EMPTY,
                1
        );
        output.writer().close();
        return output;
    }

    /// Concatenates physical volume snapshots in logical order.
    private static byte[] concatenate(List<byte[]> volumes) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] volume : volumes) {
            output.writeBytes(volume);
        }
        return output.toByteArray();
    }

    /// Opens one configurable output transaction and records whether publication was restarted.
    @NotNullByDefault
    private static final class RecordingVolumeTarget implements ArkivoVolumeTarget {
        /// The output transaction returned after successful opening.
        private final RecordingVolumeOutput output;

        /// Number of target-open failures still scheduled.
        private int openFailures;

        /// Number of output-open attempts.
        private int openAttempts;

        /// Creates a target returning the supplied output transaction.
        private RecordingVolumeTarget(RecordingVolumeOutput output) {
            this.output = output;
        }

        /// Opens the configured transaction or performs one scheduled failure.
        @Override
        public ArkivoVolumeOutput openOutput() throws IOException {
            openAttempts++;
            if (openFailures > 0) {
                openFailures--;
                throw new IOException("open output failure");
            }
            return output;
        }
    }

    /// Records a multi-volume transaction with configurable commit and cleanup failures.
    @NotNullByDefault
    private static final class RecordingVolumeOutput implements ArkivoVolumeOutput {
        /// Opened physical volume channels.
        private final ArrayList<RecordingVolumeChannel> channels = new ArrayList<>();

        /// Number of commit failures still scheduled.
        private int commitFailures;

        /// Number of rollback failures still scheduled.
        private int rollbackFailures;

        /// Number of close failures still scheduled.
        private int closeFailures;

        /// Number of close failures scheduled for the first volume channel.
        private int firstVolumeCloseFailures;

        /// Number of commit attempts.
        private int commitAttempts;

        /// Number of rollback attempts.
        private int rollbackAttempts;

        /// Number of close attempts.
        private int closeAttempts;

        /// Final committed volume index, or `-1` before commit.
        private long finalVolumeIndex = -1L;

        /// Whether the transaction committed.
        private boolean committed;

        /// Whether the transaction rolled back.
        private boolean rolledBack;

        /// Whether the transaction has completed.
        private boolean finished;

        /// Opens the next sequential in-memory volume.
        @Override
        public WritableByteChannel openVolume(long index) throws IOException {
            if (finished) {
                throw new IOException("output is finished");
            }
            if (index != channels.size()) {
                throw new IllegalArgumentException("volume index is not sequential");
            }
            if (!channels.isEmpty() && channels.get(channels.size() - 1).isOpen()) {
                throw new IOException("previous volume is still open");
            }
            RecordingVolumeChannel channel = new RecordingVolumeChannel(
                    channels.isEmpty() ? firstVolumeCloseFailures : 0
            );
            channels.add(channel);
            return channel;
        }

        /// Commits the closed volume sequence or performs one scheduled failure.
        @Override
        public void commit(long finalVolumeIndex) throws IOException {
            commitAttempts++;
            if (finished) {
                throw new IOException("output is finished");
            }
            if (finalVolumeIndex != channels.size() - 1L) {
                throw new IllegalArgumentException("final volume index is invalid");
            }
            if (!allChannelsClosed()) {
                throw new IOException("volume remains open");
            }
            if (commitFailures > 0) {
                commitFailures--;
                throw new IOException("commit failure");
            }
            this.finalVolumeIndex = finalVolumeIndex;
            committed = true;
            finished = true;
        }

        /// Rolls back the transaction or performs one scheduled failure.
        @Override
        public void rollback() throws IOException {
            rollbackAttempts++;
            if (finished) {
                return;
            }
            if (rollbackFailures > 0) {
                rollbackFailures--;
                throw new IOException("rollback failure");
            }
            @SuppressWarnings("resource")
            @Unmodifiable List<RecordingVolumeChannel> openedChannels = List.copyOf(channels);
            for (RecordingVolumeChannel channel : openedChannels) {
                if (channel.isOpen()) {
                    channel.close();
                }
            }
            rolledBack = true;
            finished = true;
        }

        /// Closes the transaction or performs one scheduled failure.
        @Override
        public void close() throws IOException {
            closeAttempts++;
            if (closeFailures > 0) {
                closeFailures--;
                throw new IOException("output close failure");
            }
            if (!finished) {
                rollback();
            }
        }

        /// Returns immutable snapshots of bytes written to all opened volumes.
        private @Unmodifiable List<byte[]> volumeBytes() {
            ArrayList<byte[]> result = new ArrayList<>(channels.size());
            for (RecordingVolumeChannel channel : channels) {
                result.add(channel.bytes.toByteArray());
            }
            return List.copyOf(result);
        }

        /// Returns whether every opened volume channel is closed.
        private boolean allChannelsClosed() {
            for (RecordingVolumeChannel channel : channels) {
                if (channel.isOpen()) {
                    return false;
                }
            }
            return true;
        }
    }

    /// Stores one physical volume and supports close-failure injection.
    @NotNullByDefault
    private static final class RecordingVolumeChannel implements WritableByteChannel {
        /// Bytes written to this volume.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /// Number of close failures still scheduled.
        private int closeFailures;

        /// Number of close attempts.
        private int closeAttempts;

        /// Whether the channel remains open.
        private boolean open = true;

        /// Creates a volume with the requested close failures.
        private RecordingVolumeChannel(int closeFailures) {
            this.closeFailures = closeFailures;
        }

        /// Writes every remaining source byte.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            int count = source.remaining();
            byte[] buffer = new byte[count];
            source.get(buffer);
            bytes.writeBytes(buffer);
            return count;
        }

        /// Returns whether the channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this volume or performs one scheduled failure while leaving it open.
        @Override
        public void close() throws IOException {
            closeAttempts++;
            if (!open) {
                return;
            }
            if (closeFailures > 0) {
                closeFailures--;
                throw new IOException("volume close failure");
            }
            open = false;
        }

        /// Requires this volume to remain open.
        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}
