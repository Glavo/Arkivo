// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests public immutable 7z coder metadata.
@NotNullByDefault
public final class SevenZipCoderGraphTest {
    /// Verifies method identifiers and coder properties cannot mutate their metadata owners.
    @Test
    public void methodAndCoderBytesAreDefensivelyCopied() {
        byte[] properties = {1};
        SevenZipCoder coder = new SevenZipCoder(SevenZipCoderMethod.LZMA2, properties, 1, 1, 0, 0);
        properties[0] = 2;
        byte[] returnedProperties = coder.properties();
        returnedProperties[0] = 3;

        assertArrayEquals(new byte[]{1}, coder.properties());        SevenZipCoder equalCoder = new SevenZipCoder(
                SevenZipCoderMethod.LZMA2,
                new byte[]{1},
                1,
                1,
                0,
                0
        );
        assertEquals(coder, equalCoder);
        assertEquals(coder.hashCode(), equalCoder.hashCode());
        assertEquals(true, coder.toString().contains("properties=[1]"));
        byte[] methodId = SevenZipCoderMethod.BCJ_ARM64.methodId();
        methodId[0] = 0;
        assertArrayEquals(new byte[]{0x0a}, SevenZipCoderMethod.BCJ_ARM64.methodId());
        assertEquals(SevenZipCoderMethod.BCJ_RISCV, SevenZipCoderMethod.fromMethodId(new byte[]{0x0b}));
        for (SevenZipCoderMethod method : SevenZipCoderMethod.values()) {
            assertEquals(method, SevenZipCoderMethod.fromMethodId(method.methodId()));
        }
        assertThrows(IllegalArgumentException.class, () -> SevenZipCoderMethod.fromMethodId(new byte[]{0x7f}));
    }

    /// Verifies a valid linear graph exposes immutable stream topology and sizes.
    @Test
    public void linearGraphExposesTopology() {
        List<SevenZipCoder> coders = List.of(
                new SevenZipCoder(SevenZipCoderMethod.LZMA2, new byte[]{0x10}, 1, 1, 0, 0),
                new SevenZipCoder(SevenZipCoderMethod.BCJ_X86, new byte[0], 1, 1, 1, 1)
        );
        SevenZipCoderGraph graph = new SevenZipCoderGraph(
                coders,
                new int[]{-1, 0},
                new int[]{0, -1},
                new long[]{12, 12},
                1
        );

        assertEquals(coders, graph.coders());
        assertThrows(UnsupportedOperationException.class, () -> graph.coders().clear());
        assertEquals(2, graph.inputStreamCount());
        assertEquals(2, graph.outputStreamCount());
        assertEquals(1, graph.packedStreamCount());
        assertEquals(-1, graph.boundOutputStreamIndex(0));
        assertEquals(0, graph.boundOutputStreamIndex(1));
        assertEquals(0, graph.packedStreamOrdinal(0));
        assertEquals(-1, graph.packedStreamOrdinal(1));
        assertEquals(12L, graph.unpackSize(0));
        assertEquals(1, graph.finalOutputStreamIndex());
        assertEquals(12L, graph.finalUnpackSize());        SevenZipCoderGraph equalGraph = new SevenZipCoderGraph(
                coders,
                new int[]{-1, 0},
                new int[]{0, -1},
                new long[]{12, 12},
                1
        );
        assertEquals(graph, equalGraph);
        assertEquals(graph.hashCode(), equalGraph.hashCode());
        assertEquals(true, graph.toString().contains("packedStreamOrdinalByInput=[0, -1]"));
    }

    /// Verifies malformed input sources, bindings, sizes, and final outputs are rejected.
    @Test
    public void invalidGraphsAreRejected() {
        List<SevenZipCoder> coders = List.of(
                new SevenZipCoder(SevenZipCoderMethod.COPY, new byte[0], 1, 1, 0, 0),
                new SevenZipCoder(SevenZipCoderMethod.COPY, new byte[0], 1, 1, 1, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipCoderGraph(coders, new int[]{-1, -1}, new int[]{0, 0}, new long[]{1, 1}, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipCoderGraph(coders, new int[]{-1, 0}, new int[]{0, -1}, new long[]{1, -1}, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipCoderGraph(coders, new int[]{-1, 0}, new int[]{0, -1}, new long[]{1, 1}, 0)
        );        List<SevenZipCoder> disconnectedCoders = List.of(
                new SevenZipCoder(SevenZipCoderMethod.COPY, new byte[0], 1, 1, 0, 0),
                new SevenZipCoder(SevenZipCoderMethod.COPY, new byte[0], 1, 1, 1, 1),
                new SevenZipCoder(SevenZipCoderMethod.COPY, new byte[0], 1, 1, 2, 2)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipCoderGraph(
                        disconnectedCoders,
                        new int[]{-1, 2, 1},
                        new int[]{0, -1, -1},
                        new long[]{1, 1, 1},
                        0
                )
        );
    }
}
