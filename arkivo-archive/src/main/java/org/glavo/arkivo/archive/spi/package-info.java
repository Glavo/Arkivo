// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Defines service-provider contracts for preparing forward-only archive sources.
///
/// Providers receive ownership of an input channel after argument validation. A provider must either return an owning
/// result that preserves the complete logical input or close the input when setup fails. Provider implementations may
/// be discovered once and invoked by concurrent archive operations, so they should not keep per-probe state in the
/// service instance.
@NotNullByDefault
package org.glavo.arkivo.archive.spi;

import org.jetbrains.annotations.NotNullByDefault;
