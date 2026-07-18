// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Implements reusable BCJ and Delta byte transforms used by XZ and 7z coders.
///
/// This package is exported only to the 7z implementation module. Applications configure XZ filter chains through
/// [org.glavo.arkivo.codec.xz.XZFilter] and its public implementations.
@NotNullByDefault
package org.glavo.arkivo.codec.xz.internal.filter;

import org.jetbrains.annotations.NotNullByDefault;
