// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies 7z folder coder graphs enforce structural invariants without rejecting valid directed acyclic graphs.
@NotNullByDefault
final class SevenZipFolderMethodTest {
    /// Verifies linear construction derives bindings and retains defensive copies for multiple coders.
    @Test
    void constructsLinearPipelineDefensively() {
        byte[][] methodIds = {{0}, {0}, {0}};
        byte[][] properties = {{1}, {2}, {3}};
        SevenZipFolderMethod method = new SevenZipFolderMethod(
                methodIds,
                properties,
                new long[]{7L, 8L, 9L}
        );

        methodIds[0][0] = 1;
        properties[0][0] = 9;
        byte[] returnedMethodId = method.methodId(0);
        byte[] returnedProperties = method.properties(0);
        returnedMethodId[0] = 2;
        returnedProperties[0] = 8;

        assertArrayEquals(new byte[]{0}, method.methodId(0));
        assertArrayEquals(new byte[]{1}, method.properties(0));
        assertEquals(-1, method.boundOutputStreamIndex(0));
        assertEquals(0, method.boundOutputStreamIndex(1));
        assertEquals(1, method.boundOutputStreamIndex(2));
        assertEquals(0, method.packedStreamOrdinal(0));
        assertEquals(-1, method.packedStreamOrdinal(1));
        assertEquals(2, method.finalOutputStreamIndex());
        assertEquals(9L, method.finalUnpackSize());
        assertTrue(method.isCopyOnly());
    }

    /// Verifies a coder with multiple outputs can feed multiple inputs of one downstream coder.
    @Test
    void acceptsSharedUpstreamCoderOutputs() {
        SevenZipFolderMethod method = SevenZipFolderMethod.graph(
                new byte[][]{{0}, {0}},
                new byte[][]{new byte[0], new byte[0]},
                new int[]{1, 2},
                new int[]{2, 1},
                new int[]{1, 2},
                new int[]{0, 1},
                new int[]{0},
                new long[]{5L, 5L, 5L}
        );

        assertEquals(2, method.finalOutputStreamIndex());
        assertEquals(0, method.coderIndexForOutput(0));
        assertEquals(0, method.coderIndexForOutput(1));
        assertEquals(1, method.coderIndexForOutput(2));
        assertEquals(0, method.packedStreamOrdinal(0));
        assertEquals(-1, method.packedStreamOrdinal(1));
        assertEquals(2, method.coderGraph().coders().size());
    }

    /// Verifies malformed folder array dimensions and stream counts are rejected before graph traversal.
    @Test
    void rejectsInconsistentGraphShapes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipFolderMethod.graph(
                        new byte[0][],
                        new byte[0][],
                        new int[0],
                        new int[0],
                        new int[0],
                        new int[0],
                        new int[0],
                        new long[0]
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipFolderMethod.graph(
                        new byte[][]{{0}},
                        new byte[0][],
                        new int[]{1},
                        new int[]{1},
                        new int[0],
                        new int[0],
                        new int[]{0},
                        new long[]{0L}
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipFolderMethod.graph(
                        new byte[][]{{0}},
                        new byte[][]{new byte[0]},
                        new int[]{1},
                        new int[]{1},
                        new int[]{0},
                        new int[0],
                        new int[]{0},
                        new long[]{0L}
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipFolderMethod.graph(
                        new byte[][]{{0}},
                        new byte[][]{new byte[0]},
                        new int[]{0},
                        new int[]{1},
                        new int[0],
                        new int[0],
                        new int[0],
                        new long[]{0L}
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipFolderMethod.graph(
                        new byte[][]{{0}},
                        new byte[][]{new byte[0]},
                        new int[]{1},
                        new int[]{1},
                        new int[]{0},
                        new int[]{0},
                        new int[0],
                        new long[]{0L}
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipFolderMethod.graph(
                        new byte[][]{{0}},
                        new byte[][]{new byte[0]},
                        new int[]{1},
                        new int[]{1},
                        new int[0],
                        new int[0],
                        new int[0],
                        new long[]{0L}
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipFolderMethod.graph(
                        new byte[][]{{0}},
                        new byte[][]{new byte[0]},
                        new int[]{1},
                        new int[]{1},
                        new int[0],
                        new int[0],
                        new int[]{0},
                        new long[0]
                )
        );
    }

    /// Verifies malformed bind pairs and packed-input declarations are rejected deterministically.
    @Test
    void rejectsInvalidBindingsAndPackedInputs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> twoCoderGraph(new int[]{2}, new int[]{0}, new int[]{0})
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> twoCoderGraph(new int[]{1}, new int[]{2}, new int[]{0})
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipFolderMethod.graph(
                        new byte[][]{{0}, {0}},
                        new byte[][]{new byte[0], new byte[0]},
                        new int[]{2, 1},
                        new int[]{2, 1},
                        new int[]{1, 1},
                        new int[]{0, 1},
                        new int[]{0},
                        new long[]{0L, 0L, 0L}
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipFolderMethod.graph(
                        new byte[][]{{0}, {0}},
                        new byte[][]{new byte[0], new byte[0]},
                        new int[]{2, 1},
                        new int[]{2, 1},
                        new int[]{1, 2},
                        new int[]{0, 0},
                        new int[]{0},
                        new long[]{0L, 0L, 0L}
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> twoCoderGraph(new int[]{1}, new int[]{0}, new int[]{1})
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipFolderMethod.graph(
                        new byte[][]{{0}},
                        new byte[][]{new byte[0]},
                        new int[]{2},
                        new int[]{1},
                        new int[0],
                        new int[0],
                        new int[]{0, 0},
                        new long[]{0L}
                )
        );
    }

    /// Verifies disconnected and cyclic coder graphs are rejected after local indexes have been validated.
    @Test
    void rejectsDisconnectedAndCyclicGraphs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> twoCoderGraph(new int[]{0}, new int[]{0}, new int[]{1})
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SevenZipFolderMethod.graph(
                        new byte[][]{{0}},
                        new byte[][]{new byte[0]},
                        new int[]{1},
                        new int[]{2},
                        new int[]{0},
                        new int[]{0},
                        new int[0],
                        new long[]{0L, 0L}
                )
        );
    }

    /// Creates a two-coder graph with one binding and one packed input.
    private static SevenZipFolderMethod twoCoderGraph(
            int[] bindPairInputs,
            int[] bindPairOutputs,
            int[] packedInputStreamIndexes
    ) {
        return SevenZipFolderMethod.graph(
                new byte[][]{{0}, {0}},
                new byte[][]{new byte[0], new byte[0]},
                new int[]{1, 1},
                new int[]{1, 1},
                bindPairInputs,
                bindPairOutputs,
                packedInputStreamIndexes,
                new long[]{0L, 0L}
        );
    }
}
