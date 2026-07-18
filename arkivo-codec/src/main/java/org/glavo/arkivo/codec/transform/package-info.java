// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Provides stateful in-place byte transforms and blocking stream or channel wrappers that apply them.
///
/// A transform may retain an incomplete suffix until more lookahead arrives. On end of input or explicit finish, wrappers
/// forward that suffix unchanged. Transform instances and wrappers are mutable, not safe for concurrent use, and must
/// not be shared across independent byte sequences without format-specific reinitialization.
@NotNullByDefault
package org.glavo.arkivo.codec.transform;

import org.jetbrains.annotations.NotNullByDefault;
