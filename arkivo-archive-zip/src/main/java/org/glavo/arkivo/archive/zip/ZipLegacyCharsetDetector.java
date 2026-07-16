// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/// Selects the charset used to decode legacy ZIP entry metadata without authoritative Unicode information.
///
/// ZIP readers invoke this detector only after a valid Info-ZIP Unicode extra field and the UTF-8 general-purpose
/// flag have been considered. The supplied buffer is an independent read-only view containing one raw entry name or
/// comment. It is valid only for the duration of the call and must not be retained.
///
/// Returning `null` reports that the charset could not be determined; the reader then falls back to CP437 as required
/// by the original ZIP format rules. Implementations may be reused by multiple archive readers and therefore should
/// be thread-safe.
@FunctionalInterface
@NotNullByDefault
public interface ZipLegacyCharsetDetector {
    /// Detects the charset of one raw legacy ZIP entry name or comment, or returns `null` when it is unknown.
    @Nullable Charset detect(@UnmodifiableView ByteBuffer bytes) throws IOException;

    /// Returns the ZIP-standard detector that always selects CP437.
    static ZipLegacyCharsetDetector standard() {
        return FixedZipLegacyCharsetDetector.STANDARD;
    }

    /// Returns a detector that always selects the given charset.
    static ZipLegacyCharsetDetector fixed(Charset charset) {
        return FixedZipLegacyCharsetDetector.of(charset);
    }
}
