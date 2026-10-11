// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

/// Detects character encodings from byte sequences.
///
/// Detection is heuristic and does not replace a format's explicit encoding declaration.
/// Complete names and comments have independent decoding state. Statistical profiles can be pooled
/// to infer one encoding for an archive; format declarations, whole-archive validation, and fallback
/// policies belong to archive readers.
package org.glavo.arkivo.encoding.detector.internal;
