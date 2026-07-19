// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.codec.CompressionCodec;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Defines explicit outer-compression policies for TAR read, create, and update operations.
///
/// The role subinterfaces prevent a read-only detection policy from being used for creation and prevent a
/// source-preservation policy from being used where no source exists. A configured codec is immutable operation policy;
/// encoder and decoder state is created separately by the TAR factory.
@NotNullByDefault
public sealed interface TarCompression permits TarCompression.Read, TarCompression.Create, TarCompression.Update {
    /// Automatically detects a signed outer compression format while opening an existing TAR archive.
    Read DETECT = Detection.INSTANCE;

    /// Reads or writes a plain TAR byte stream without an outer compression wrapper.
    Uncompressed UNCOMPRESSED = Uncompressed.INSTANCE;

    /// Preserves the detected source compression when publishing a complete-rewrite update.
    Update PRESERVE = Preservation.INSTANCE;

    /// Returns a policy using the given compression codec.
    ///
    /// @param codec the immutable codec configuration used for the outer wrapper
    /// @return a policy valid for reading, creation, and update
    static Codec using(CompressionCodec<?> codec) {
        return new Codec(codec);
    }

    /// Selects a policy valid while reading an existing TAR archive.
    @NotNullByDefault
    sealed interface Read extends TarCompression permits Detection, Uncompressed, Codec {
    }

    /// Selects a policy valid while creating a new TAR archive.
    @NotNullByDefault
    sealed interface Create extends TarCompression permits Uncompressed, Codec {
    }

    /// Selects a policy valid while rewriting an existing TAR archive.
    @NotNullByDefault
    sealed interface Update extends TarCompression permits Preservation, Uncompressed, Codec {
    }

    /// Implements automatic read-time compression detection.
    @NotNullByDefault
    enum Detection implements Read {
        /// The sole automatic-detection policy instance.
        INSTANCE
    }

    /// Implements an explicitly uncompressed TAR stream.
    @NotNullByDefault
    enum Uncompressed implements Read, Create, Update {
        /// The sole uncompressed policy instance.
        INSTANCE
    }

    /// Implements source-compression preservation for complete-rewrite updates.
    @NotNullByDefault
    enum Preservation implements Update {
        /// The sole preservation policy instance.
        INSTANCE
    }

    /// Uses one immutable codec configuration as the TAR outer wrapper.
    ///
    /// @param codec the codec used to decode an existing wrapper or encode a new one
    @NotNullByDefault
    record Codec(CompressionCodec<?> codec) implements Read, Create, Update {
        /// Validates the configured codec.
        public Codec {
            Objects.requireNonNull(codec, "codec");
        }
    }
}
