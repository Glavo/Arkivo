// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Detects character encodings from byte sequences.
///
/// Detection is heuristic and does not replace a format's explicit encoding declaration.
/// Each detector processes one continuous byte sequence; separate fields require separate detectors.
package org.glavo.arkivo.encoding.detector.internal;
