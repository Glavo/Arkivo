// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies compression signature detection treats every caller buffer as immutable input state.
@NotNullByDefault
final class CompressionDetectionBufferContractTest {
    /// Sentinel retained outside the visible prefix range.
    private static final byte BUFFER_GUARD = (byte) 0xc7;

    /// Content used to obtain a valid signature from every detectable codec.
    private static final byte @Unmodifiable [] CONTENT = (
            "compression signature buffer contract 0123456789abcdef;".repeat(32)
    ).getBytes(StandardCharsets.UTF_8);

    /// Verifies exact preferred prefixes across heap, direct, mutable, and read-only views.
    @Test
    void detectsEverySignedFormatFromProtectedBufferViews() throws IOException {
        for (CompressionFormat format : CompressionFormats.installed()) {
            if (format.probeSize() == 0) {
                continue;
            }

            ByteBuffer encoded = format.defaultCodec().compress(ByteBuffer.wrap(CONTENT));
            assertTrue(encoded.remaining() >= format.probeSize(), format.name());
            byte[] prefix = new byte[format.probeSize()];
            encoded.get(prefix);

            assertDetectedView(format, prefix, false, false);
            assertDetectedView(format, prefix, false, true);
            assertDetectedView(format, prefix, true, false);
            assertDetectedView(format, prefix, true, true);
        }
    }

    /// Verifies every shorter prefix is safe to inspect and leaves all caller-visible state unchanged.
    @Test
    void safelyRejectsOrRecognizesTruncatedPrefixesWithoutMutation() throws IOException {
        for (CompressionFormat format : CompressionFormats.installed()) {
            if (format.probeSize() == 0) {
                continue;
            }

            ByteBuffer encoded = format.defaultCodec().compress(ByteBuffer.wrap(CONTENT));
            byte[] completePrefix = new byte[format.probeSize()];
            encoded.get(completePrefix);
            for (int length = 0; length < completePrefix.length; length++) {
                byte[] truncated = Arrays.copyOf(completePrefix, length);
                assertStableDetection(
                        format,
                        truncated,
                        (length & 1) != 0,
                        (length & 2) != 0
                );
            }
        }
    }

    /// Verifies a signature view identifies the expected format and preserves storage and view state.
    private static void assertDetectedView(
            CompressionFormat expected,
            byte @Unmodifiable [] prefix,
            boolean direct,
            boolean readOnly
    ) {
        ByteBuffer storage = createStorage(prefix, direct);
        ByteBuffer view = createView(storage, prefix.length, readOnly);
        BufferState original = BufferState.capture(view, storage);
        String context = expected.name() + " direct=" + direct + " readOnly=" + readOnly;

        assertTrue(expected.matches(view), context);
        original.assertUnchanged(view, storage, context);
        assertSame(expected, CompressionFormats.detect(view), context);
        original.assertUnchanged(view, storage, context);
        assertGuardBytes(storage, prefix.length, context);
    }

    /// Runs generic detection for an arbitrary short prefix and verifies it is observationally read-only.
    private static void assertStableDetection(
            CompressionFormat format,
            byte @Unmodifiable [] prefix,
            boolean direct,
            boolean readOnly
    ) {
        ByteBuffer storage = createStorage(prefix, direct);
        ByteBuffer view = createView(storage, prefix.length, readOnly);
        BufferState original = BufferState.capture(view, storage);
        String context = format.name() + " length=" + prefix.length;

        format.matches(view);
        original.assertUnchanged(view, storage, context);
        CompressionFormats.detect(view);
        original.assertUnchanged(view, storage, context);
        assertGuardBytes(storage, prefix.length, context);
    }

    /// Creates guarded storage containing the prefix at a nonzero offset.
    private static ByteBuffer createStorage(byte @Unmodifiable [] prefix, boolean direct) {
        ByteBuffer storage = direct
                ? ByteBuffer.allocateDirect(prefix.length + 9)
                : ByteBuffer.allocate(prefix.length + 9);
        for (int index = 0; index < storage.capacity(); index++) {
            storage.put(index, BUFFER_GUARD);
        }
        storage.position(4);
        storage.put(prefix);
        storage.clear();
        return storage;
    }

    /// Creates the mutable or read-only visible prefix view with a non-default byte order and a current mark.
    private static ByteBuffer createView(ByteBuffer storage, int prefixLength, boolean readOnly) {
        ByteBuffer view = storage.duplicate();
        view.position(4);
        view.limit(4 + prefixLength);
        if (readOnly) {
            view = view.asReadOnlyBuffer();
        }
        view.order(ByteOrder.LITTLE_ENDIAN);
        view.mark();
        return view;
    }

    /// Verifies the guard bytes surrounding the visible prefix remain untouched.
    private static void assertGuardBytes(ByteBuffer storage, int prefixLength, String context) {
        for (int index = 0; index < 4; index++) {
            assertEquals(BUFFER_GUARD, storage.get(index), context + " prefix guard " + index);
        }
        for (int index = 4 + prefixLength; index < storage.capacity(); index++) {
            assertEquals(BUFFER_GUARD, storage.get(index), context + " suffix guard " + index);
        }
    }

    /// Captures caller-visible buffer state and the complete backing storage contents.
    ///
        /// @param position original view position and mark
    /// @param limit original view limit
    /// @param order original view byte order
    /// @param readOnly whether the original view was read-only
    /// @param storage complete backing-storage snapshot
    private record BufferState(
            int position,
            int limit,
            ByteOrder order,
            boolean readOnly,
            byte @Unmodifiable [] storage
    ) {
        /// Captures one view and its complete backing storage.
        private static BufferState capture(ByteBuffer view, ByteBuffer storage) {
            ByteBuffer snapshotView = storage.duplicate();
            snapshotView.clear();
            byte[] snapshot = new byte[snapshotView.remaining()];
            snapshotView.get(snapshot);
            return new BufferState(
                    view.position(),
                    view.limit(),
                    view.order(),
                    view.isReadOnly(),
                    snapshot
            );
        }

        /// Verifies the view state, mark, and complete backing storage equal this snapshot.
        private void assertUnchanged(ByteBuffer view, ByteBuffer currentStorage, String context) {
            assertEquals(position, view.position(), context);
            assertEquals(limit, view.limit(), context);
            assertEquals(order, view.order(), context);
            assertEquals(readOnly, view.isReadOnly(), context);
            view.reset();
            assertEquals(position, view.position(), context + " mark");

            ByteBuffer snapshotView = currentStorage.duplicate();
            snapshotView.clear();
            byte[] current = new byte[snapshotView.remaining()];
            snapshotView.get(current);
            assertArrayEquals(storage, current, context);
        }
    }
}
