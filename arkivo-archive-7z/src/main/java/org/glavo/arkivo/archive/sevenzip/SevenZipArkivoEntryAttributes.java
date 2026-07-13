// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/// Exposes parsed 7z entry attributes.
@NotNullByDefault
public interface SevenZipArkivoEntryAttributes extends BasicFileAttributes {
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
    @Nullable SevenZipCoderGraph coderGraph();

    /// Returns whether this entry is one of multiple file-addressable substreams in its folder.
    boolean solid();

    /// Returns this entry's zero-based index among file-addressable substreams in its folder.
    ///
    /// Entries without a packed stream return `NO_SUBSTREAM_INDEX`.
    int substreamIndex();

    /// Returns the number of file-addressable substreams in this entry's folder.
    ///
    /// Entries without a packed stream return zero.
    int substreamCount();

    /// Returns the absolute logical archive offset of the first packed stream, or `NO_DATA_OFFSET` when absent.
    long dataOffset();

    /// Returns the decoded byte offset of this entry within the folder output.
    ///
    /// Entries without a packed stream return zero.
    long decodedOffset();

    /// Returns the total size of the physical packed byte ranges used to read this entry.
    ///
    /// Compressed solid entries normally report their whole folder input. Directly addressable Copy substreams report
    /// only their own byte range.
    long packedSize();

    /// Returns the CRC-32 for the complete packed range when exactly one range has a declared digest, or
    /// `UNKNOWN_CRC32`.
    long packedCrc32();

    /// Returns the immutable physical packed byte ranges used to read this entry.
    ///
    /// Compressed solid entries normally share folder ranges. Copy substreams may expose directly addressable slices.
    /// Entries without data return an empty list.
    @Unmodifiable
    List<SevenZipPackedStream> packedStreams();

    /// Returns the decoded entry CRC-32, or `UNKNOWN_CRC32` when absent.
    long crc32();

    /// Returns the Windows file attributes, or `UNKNOWN_WINDOWS_ATTRIBUTES` when not present.
    int windowsAttributes();

    /// Returns the Unix mode bits stored in the high 16 bits of the 7z attributes, or `UNKNOWN_UNIX_MODE`.
    int unixMode();
}
