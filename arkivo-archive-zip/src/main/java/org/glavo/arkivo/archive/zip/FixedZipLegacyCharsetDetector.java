// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Objects;

/// Selects one fixed charset for legacy ZIP entry metadata.
///
/// @param charset the selected charset
@NotNullByDefault
record FixedZipLegacyCharsetDetector(Charset charset) implements ZipLegacyCharsetDetector {
    /// The shared detector for the ZIP-standard CP437 charset.
    static final FixedZipLegacyCharsetDetector STANDARD =
            new FixedZipLegacyCharsetDetector(Charset.forName("IBM437"));

    /// Creates a fixed legacy charset detector.
    FixedZipLegacyCharsetDetector {
        Objects.requireNonNull(charset, "charset");
    }

    /// Returns the shared standard detector or a detector for another charset.
    static FixedZipLegacyCharsetDetector of(Charset charset) {
        Charset checkedCharset = Objects.requireNonNull(charset, "charset");
        return STANDARD.charset.equals(checkedCharset)
                ? STANDARD
                : new FixedZipLegacyCharsetDetector(checkedCharset);
    }

    /// Returns the fixed charset without inspecting the supplied bytes.
    @Override
    public Charset detect(@UnmodifiableView ByteBuffer bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return charset;
    }
}
