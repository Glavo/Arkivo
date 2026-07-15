// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.spi;

import org.glavo.arkivo.codec.CompressionCodec;
import org.jetbrains.annotations.NotNullByDefault;

/// Supplies the default immutable configuration of one compression codec to service discovery.
///
/// Providers must be stateless and safe for concurrent use. Configuration variants are derived from the returned
/// codec rather than registered as additional services.
@NotNullByDefault
@FunctionalInterface
public interface CompressionCodecProvider {
    /// Returns an immutable codec using its default configuration.
    ///
    /// An implementation may return the same codec instance from multiple invocations.
    CompressionCodec defaultCodec();
}
