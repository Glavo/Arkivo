// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Represents a stable read of 7z entry metadata.
///
/// Attribute objects returned by indexed file systems and pending streaming-writer views do not change after they are
/// read. A snapshot taken for a pending entry uses the documented sentinels for coder, packed-stream, offset, size,
/// and checksum properties because the entry has not yet been encoded; that snapshot is not updated on commit.
@NotNullByDefault
public interface SevenZipArkivoEntryAttributes extends ArchiveEntryAttributes {
    /// The Windows attributes value used when the archive entry has no Windows attributes property.
    int UNKNOWN_WINDOWS_ATTRIBUTES = -1;

    /// The Unix mode value used when the archive entry has no Unix mode metadata.
    int UNKNOWN_UNIX_MODE = -1;

    /// The data-offset value used when an entry has no packed stream.
    long NO_DATA_OFFSET = -1L;

    /// The CRC-32 value used when an entry or packed stream has no declared digest.
    long UNKNOWN_CRC32 = SevenZipPackedStream.UNKNOWN_CRC32;

    /// The substream-index value used when an entry has no packed stream.
    int NO_SUBSTREAM_INDEX = -1;

    /// Returns the decoded entry path stored in the 7z header.
    String path();

    /// Returns the complete coder graph, or null when the entry has no packed stream.
    ///
    /// Coders are reported in 7z declaration order from packed inputs toward the final decoded output.
    ///
    /// @return the immutable coder graph, or `null` when the entry has no packed stream
    @Nullable SevenZipCoderGraph coderGraph();

    /// Returns whether this entry is one of multiple file-addressable substreams in its folder.
    ///
    /// @return `true` when the entry shares a folder coder stream with another file entry
    boolean solid();

    /// Returns this entry's zero-based index among file-addressable substreams in its folder.
    ///
    /// Entries without a packed stream return `NO_SUBSTREAM_INDEX`.
    ///
    /// @return the zero-based file substream index, or [#NO_SUBSTREAM_INDEX]
    int substreamIndex();

    /// Returns the number of file-addressable substreams in this entry's folder.
    ///
    /// Entries without a packed stream return zero.
    ///
    /// @return the number of file-addressable substreams in the folder
    int substreamCount();

    /// Returns the absolute logical archive offset of the first packed stream, or `NO_DATA_OFFSET` when absent.
    ///
    /// @return the first packed-stream offset, or [#NO_DATA_OFFSET]
    long dataOffset();

    /// Returns the decoded byte offset of this entry within the folder output.
    ///
    /// Entries without a packed stream return zero.
    ///
    /// @return the non-negative decoded offset within the folder output
    long decodedOffset();

    /// Returns the total size of the physical packed byte ranges used to read this entry.
    ///
    /// Compressed solid entries normally report their whole folder input. Directly addressable Copy substreams report
    /// only their own byte range.
    ///
    /// @return the total physical packed size in bytes
    long packedSize();

    /// Returns the CRC-32 for the complete packed range when exactly one range has a declared digest, or
    /// `UNKNOWN_CRC32`.
    ///
    /// @return the unsigned packed CRC-32 value, or [#UNKNOWN_CRC32]
    long packedCrc32();

    /// Returns the immutable physical packed byte ranges used to read this entry.
    ///
    /// Compressed solid entries normally share folder ranges. Copy substreams may expose directly addressable slices.
    /// Entries without data return an empty list.
    ///
    /// @return the immutable ordered physical packed ranges
    @Unmodifiable
    List<SevenZipPackedStream> packedStreams();

    /// Returns the decoded entry CRC-32, or `UNKNOWN_CRC32` when absent.
    ///
    /// @return the unsigned decoded CRC-32 value, or [#UNKNOWN_CRC32]
    long crc32();

    /// Returns the Windows file attributes, or `UNKNOWN_WINDOWS_ATTRIBUTES` when not present.
    ///
    /// @return the raw Windows attributes, or [#UNKNOWN_WINDOWS_ATTRIBUTES]
    int windowsAttributes();

    /// Returns the Unix mode bits stored in the high 16 bits of the 7z attributes, or `UNKNOWN_UNIX_MODE`.
    ///
    /// @return the Unix mode, or [#UNKNOWN_UNIX_MODE]
    int unixMode();
}
