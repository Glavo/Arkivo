// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides adapter and safety utilities used by compression-format implementations.
///
/// The package is qualified-exported to Arkivo codec modules. Factories adapt one fresh engine or stream coder to the
/// public blocking channel contracts and apply explicit ownership to the compressed-data endpoint. Format
/// implementations must not reuse a stateful engine between returned contexts.
@NotNullByDefault
package org.glavo.arkivo.codec.internal;

import org.jetbrains.annotations.NotNullByDefault;
