// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Contains low-level byte-access primitives shared by selected Arkivo implementation modules.
///
/// This package is exported only through qualified module directives. It is not intended for application use, and its
/// types operate on caller-owned storage without defining archive- or compression-level policy.
@NotNullByDefault
package org.glavo.arkivo.internal;

import org.jetbrains.annotations.NotNullByDefault;
