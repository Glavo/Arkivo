// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;

/// Incrementally encodes a sequence of independently terminated compression frames.
///
/// Completing a frame preserves immutable encoder configuration and leaves the encoder ready to accept source bytes
/// for a following frame. The following frame may be initialized lazily.
@NotNullByDefault
public interface FramedCompressionEncoder extends CompressionEncoder {
    /// Finishes the current frame without finishing the complete encoding session.
    ///
    /// Callers repeat this operation with fresh target space after `CodecOutcome.NEEDS_OUTPUT`.
    ///
    /// @return `CodecOutcome.BOUNDARY_REACHED` when the frame boundary completed, or
    /// `CodecOutcome.NEEDS_OUTPUT` otherwise
    default CodecOutcome finishFrame(ByteBuffer target) throws IOException {
        CodecOutcome outcome = finish(target);
        if (outcome == CodecOutcome.FINISHED) {
            reset();
            return CodecOutcome.BOUNDARY_REACHED;
        }
        return outcome;
    }
}
