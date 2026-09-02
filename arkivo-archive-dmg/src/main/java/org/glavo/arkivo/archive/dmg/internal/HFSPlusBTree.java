// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.glavo.arkivo.archive.internal.ArkivoReadLimitTracker;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.HashSet;

/// Reads validated HFS Plus B-tree headers and linked leaf records.
@NotNullByDefault
final class HFSPlusBTree {
    /// The encoded node-descriptor size.
    private static final int NODE_DESCRIPTOR_SIZE = 14;

    /// The signed node-kind value for a leaf node.
    private static final byte LEAF_NODE_KIND = -1;

    /// The signed node-kind value for the header node.
    private static final byte HEADER_NODE_KIND = 1;

    /// The required height of every leaf node.
    private static final byte LEAF_NODE_HEIGHT = 1;

    /// Creates no instances.
    private HFSPlusBTree() {
    }

    /// Reads and validates the tree header from node zero.
    ///
    /// @param tree the borrowed B-tree fork channel
    /// @param tracker the operation-wide metadata tracker
    /// @return the validated tree geometry
    /// @throws IOException if the header node is malformed or outside the fork
    static Header readHeader(SeekableByteChannel tree, ArkivoReadLimitTracker tracker) throws IOException {
        byte[] prefix = ChannelIO.readBytes(tree, 0L, 36);
        if (prefix[8] != HEADER_NODE_KIND) {
            throw new IOException("HFS Plus B-tree node zero is not a header node");
        }
        int nodeSize = Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(prefix, 32));
        if (nodeSize < 512 || nodeSize > 32_768 || (nodeSize & (nodeSize - 1)) != 0) {
            throw new IOException("Invalid HFS Plus B-tree node size: " + nodeSize);
        }
        if (tree.size() < nodeSize) {
            throw new IOException("HFS Plus B-tree header node exceeds its fork");
        }
        byte[] headerNode = ChannelIO.readBytes(tree, 0L, nodeSize);
        tracker.acceptMetadata(nodeSize, null);
        int recordOffset = recordOffset(headerNode, 0);
        if (recordOffset < NODE_DESCRIPTOR_SIZE || recordOffset > nodeSize - 106) {
            throw new IOException("Invalid HFS Plus B-tree header-record offset");
        }
        int treeDepth = Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(headerNode, recordOffset));
        long rootNode = uint32(headerNode, recordOffset + 2);
        long leafRecords = uint32(headerNode, recordOffset + 6);
        long firstLeafNode = uint32(headerNode, recordOffset + 10);
        long lastLeafNode = uint32(headerNode, recordOffset + 14);
        int recordedNodeSize = Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(headerNode, recordOffset + 18));
        int maximumKeyLength = Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(headerNode, recordOffset + 20));
        long totalNodes = uint32(headerNode, recordOffset + 22);
        if (recordedNodeSize != nodeSize || totalNodes == 0L
                || ChannelIO.multiply(totalNodes, nodeSize, "HFS Plus B-tree size") > tree.size()) {
            throw new IOException("Invalid HFS Plus B-tree geometry");
        }
        if (leafRecords == 0L) {
            if (firstLeafNode != 0L || lastLeafNode != 0L) {
                throw new IOException("Empty HFS Plus B-tree has linked leaf nodes");
            }
        } else if (firstLeafNode == 0L || firstLeafNode >= totalNodes
                || lastLeafNode == 0L || lastLeafNode >= totalNodes) {
            throw new IOException("HFS Plus B-tree leaf chain is outside the node map");
        }
        if (treeDepth == 0
                ? rootNode != 0L || leafRecords != 0L
                : rootNode == 0L || rootNode >= totalNodes || leafRecords == 0L) {
            throw new IOException("Invalid HFS Plus B-tree root node");
        }
        return new Header(nodeSize, maximumKeyLength, totalNodes, leafRecords, firstLeafNode, lastLeafNode);
    }

    /// Visits every record in the validated linked leaf-node chain.
    ///
    /// Record byte arrays contain only the record region, beginning with the key-length field.
    ///
    /// @param tree the borrowed B-tree fork channel
    /// @param header the validated geometry read from the same fork
    /// @param tracker the operation-wide metadata tracker
    /// @param consumer the record consumer
    /// @throws IOException if a node or leaf link is malformed or the consumer rejects a record
    static void visitLeafRecords(
            SeekableByteChannel tree,
            Header header,
            ArkivoReadLimitTracker tracker,
            RecordConsumer consumer
    ) throws IOException {
        if (header.leafRecords() == 0L) {
            return;
        }
        HashSet<Long> visited = new HashSet<>();
        long nodeNumber = header.firstLeafNode();
        long previousNode = 0L;
        long seenRecords = 0L;
        while (nodeNumber != 0L) {
            if (nodeNumber >= header.totalNodes() || !visited.add(nodeNumber)) {
                throw new IOException("Invalid or cyclic HFS Plus B-tree leaf chain");
            }
            long nodeOffset = ChannelIO.multiply(nodeNumber, header.nodeSize(), "HFS Plus B-tree node offset");
            byte[] node = ChannelIO.readBytes(tree, nodeOffset, header.nodeSize());
            tracker.acceptMetadata(node.length, null);
            if (node[8] != LEAF_NODE_KIND) {
                throw new IOException("HFS Plus B-tree leaf chain references a non-leaf node");
            }
            if (node[9] != LEAF_NODE_HEIGHT) {
                throw new IOException("Invalid HFS Plus B-tree leaf-node height");
            }
            if (uint32(node, 4) != previousNode) {
                throw new IOException("Inconsistent HFS Plus B-tree backward leaf link");
            }
            int recordCount = Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(node, 10));
            validateRecordTable(node, recordCount);
            for (int index = 0; index < recordCount; index++) {
                int start = recordOffset(node, index);
                int end = recordOffset(node, index + 1);
                byte[] record = new byte[end - start];
                System.arraycopy(node, start, record, 0, record.length);
                consumer.accept(record);
                seenRecords++;
                if (seenRecords > header.leafRecords()) {
                    throw new IOException("HFS Plus B-tree contains more leaf records than declared");
                }
            }
            long next = uint32(node, 0);
            if (next == 0L && nodeNumber != header.lastLeafNode()) {
                throw new IOException("HFS Plus B-tree leaf chain ends before its declared last node");
            }
            previousNode = nodeNumber;
            nodeNumber = next;
        }
        if (seenRecords != header.leafRecords()) {
            throw new IOException("HFS Plus B-tree leaf-record count mismatch");
        }
    }

    /// Validates one node's record-offset table.
    private static void validateRecordTable(byte[] node, int recordCount) throws IOException {
        int tableStart = node.length - (recordCount + 1) * Short.BYTES;
        if (tableStart < NODE_DESCRIPTOR_SIZE) {
            throw new IOException("HFS Plus B-tree record table exceeds its node");
        }
        int previous = recordOffset(node, 0);
        if (previous < NODE_DESCRIPTOR_SIZE || previous > tableStart) {
            throw new IOException("Invalid HFS Plus B-tree record offset");
        }
        for (int index = 1; index <= recordCount; index++) {
            int current = recordOffset(node, index);
            if (current < previous || current > tableStart) {
                throw new IOException("Unordered HFS Plus B-tree record offsets");
            }
            previous = current;
        }
    }

    /// Reads one record or free-space offset from a node's reverse offset table.
    private static int recordOffset(byte[] node, int index) {
        return Short.toUnsignedInt(ByteArrayAccess.readShortBigEndian(
                node,
                node.length - (index + 1) * Short.BYTES
        ));
    }

    /// Reads an unsigned big-endian 32-bit field.
    private static long uint32(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(ByteArrayAccess.readIntBigEndian(bytes, offset));
    }

    /// Stores validated B-tree geometry.
    ///
    /// @param nodeSize the encoded node size
    /// @param maximumKeyLength the maximum encoded key length
    /// @param totalNodes the number of addressable nodes
    /// @param leafRecords the declared leaf-record count
    /// @param firstLeafNode the first leaf-node number, or zero for an empty tree
    /// @param lastLeafNode the last leaf-node number, or zero for an empty tree
    @NotNullByDefault
    record Header(
            int nodeSize,
            int maximumKeyLength,
            long totalNodes,
            long leafRecords,
            long firstLeafNode,
            long lastLeafNode
    ) {
    }

    /// Consumes one isolated B-tree leaf record.
    @FunctionalInterface
    @NotNullByDefault
    interface RecordConsumer {
        /// Consumes one complete leaf record.
        ///
        /// @param record the isolated encoded record
        /// @throws IOException if the record is malformed or cannot be accepted
        void accept(byte[] record) throws IOException;
    }
}
