// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.glavo.arkivo.archive.internal.ArkivoReadLimitTracker;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/// Stores a validated immutable UDIF logical-to-physical block layout.
///
/// @param size the decoded logical disk size
/// @param runs the non-overlapping data runs in ascending logical order
@NotNullByDefault
record UDIFLayout(long size, @Unmodifiable List<UDIFRun> runs) {
    /// The fixed portion of a `mish` block table.
    private static final int BLOCK_TABLE_HEADER_SIZE = 204;

    /// The encoded size of one `mish` run descriptor.
    private static final int RUN_SIZE = 40;

    /// Copies the run sequence into this immutable layout.
    UDIFLayout {
        runs = List.copyOf(runs);
    }

    /// Parses the plist resource fork and every embedded block table.
    ///
    /// @param source the exclusively owned parser channel
    /// @param trailer the validated UDIF trailer
    /// @param limits the operation-wide archive read limits
    /// @param tracker the operation-wide metadata tracker
    /// @return the validated decoded layout
    /// @throws IOException if the plist or a block table is malformed or exceeds a configured limit
    static UDIFLayout read(
            SeekableByteChannel source,
            UDIFTrailer trailer,
            ArchiveReadLimits limits,
            ArkivoReadLimitTracker tracker
    ) throws IOException {
        long decodedSize = ChannelIO.multiply(
                trailer.sectorCount(),
                UDIFConstants.SECTOR_SIZE,
                "UDIF decoded image size"
        );
        long maximumDecodedSize = limits.maximumDecodedArchiveSize();
        if (maximumDecodedSize != ArchiveReadLimits.UNLIMITED_SIZE && decodedSize > maximumDecodedSize) {
            throw new ArkivoReadLimitException(
                    ArkivoReadLimitKind.DECODED_ARCHIVE_SIZE,
                    maximumDecodedSize,
                    decodedSize,
                    null
            );
        }
        if (trailer.xmlLength() == 0L) {
            throw new IOException("UDIF image has no XML resource fork");
        }
        if (trailer.xmlLength() > Integer.MAX_VALUE) {
            throw new IOException("UDIF XML property list is too large to buffer");
        }
        tracker.acceptMetadata(trailer.xmlLength(), null);
        byte[] xml = ChannelIO.readBytes(source, trailer.xmlOffset(), Math.toIntExact(trailer.xmlLength()));

        ArrayList<UDIFRun> parsedRuns = new ArrayList<>();
        for (byte[] blockTable : blockTables(xml)) {
            tracker.acceptMetadata(blockTable.length, null);
            parseBlockTable(blockTable, trailer, decodedSize, parsedRuns);
        }
        if (parsedRuns.isEmpty() && decodedSize != 0L) {
            throw new IOException("UDIF resource fork contains no data runs");
        }
        parsedRuns.sort(Comparator.comparingLong(UDIFRun::logicalOffset));
        long precedingEnd = 0L;
        for (UDIFRun run : parsedRuns) {
            if (run.logicalOffset() < precedingEnd) {
                throw new IOException("Overlapping UDIF data runs at decoded offset " + run.logicalOffset());
            }
            precedingEnd = run.logicalEnd();
        }
        return new UDIFLayout(decodedSize, List.copyOf(parsedRuns));
    }

    /// Extracts decoded `blkx` values from the plist resource fork.
    private static List<byte[]> blockTables(byte[] xml) throws IOException {
        final Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(XMLParsingErrorHandler.INSTANCE);
            InputSource input = new InputSource(new ByteArrayInputStream(xml));
            input.setSystemId("urn:arkivo:udif-resource-fork");
            document = builder.parse(input);
        } catch (ParserConfigurationException | SAXException | IllegalArgumentException exception) {
            throw new IOException("Invalid UDIF XML property list", exception);
        }

        Element rootDictionary = firstChildElement(document.getDocumentElement(), "dict");
        Element resourceFork = dictionaryValue(rootDictionary, "resource-fork");
        if (resourceFork == null || !"dict".equals(resourceFork.getTagName())) {
            throw new IOException("UDIF property list has no resource-fork dictionary");
        }
        Element blockArray = dictionaryValue(resourceFork, "blkx");
        if (blockArray == null || !"array".equals(blockArray.getTagName())) {
            throw new IOException("UDIF resource fork has no blkx array");
        }

        ArrayList<byte[]> tables = new ArrayList<>();
        for (Element entry : childElements(blockArray)) {
            if (!"dict".equals(entry.getTagName())) {
                continue;
            }
            Element data = dictionaryValue(entry, "Data");
            if (data == null || !"data".equals(data.getTagName())) {
                continue;
            }
            try {
                tables.add(Base64.getMimeDecoder().decode(data.getTextContent()));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid base64 data in a UDIF blkx entry", exception);
            }
        }
        if (tables.isEmpty()) {
            throw new IOException("UDIF resource fork has no usable blkx data");
        }
        return List.copyOf(tables);
    }

    /// Converts XML parser diagnostics into controlled parse failures without writing to standard error.
    @NotNullByDefault
    private enum XMLParsingErrorHandler implements ErrorHandler {
        /// The stateless shared handler.
        INSTANCE;

        /// Ignores parser warnings that do not invalidate the property list.
        @Override
        public void warning(SAXParseException exception) {
        }

        /// Rejects a recoverable XML parse error.
        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        /// Rejects a fatal XML parse error.
        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    }

    /// Parses one binary `mish` block table and appends its data runs.
    private static void parseBlockTable(
            byte[] bytes,
            UDIFTrailer trailer,
            long decodedSize,
            List<UDIFRun> runs
    ) throws IOException {
        if (bytes.length < BLOCK_TABLE_HEADER_SIZE
                || ByteArrayAccess.readIntBigEndian(bytes, 0) != UDIFConstants.MISH_SIGNATURE) {
            throw new IOException("Invalid UDIF mish block table");
        }
        if (Integer.toUnsignedLong(ByteArrayAccess.readIntBigEndian(bytes, 4)) != 1L) {
            throw new IOException("Unsupported UDIF mish block-table version");
        }
        long firstSector = uint64(bytes, 8, "UDIF block-table first sector");
        long tableSectors = uint64(bytes, 16, "UDIF block-table sector count");
        long dataStart = uint64(bytes, 24, "UDIF block-table data start");
        long tableEndSector = ChannelIO.add(firstSector, tableSectors, "UDIF block-table sector range");
        long decodedSectorCount = decodedSize / UDIFConstants.SECTOR_SIZE;
        if (tableEndSector > decodedSectorCount) {
            throw new IOException("UDIF block table exceeds the decoded disk");
        }

        long runCount = Integer.toUnsignedLong(ByteArrayAccess.readIntBigEndian(bytes, 200));
        long expectedSize = ChannelIO.add(
                BLOCK_TABLE_HEADER_SIZE,
                ChannelIO.multiply(runCount, RUN_SIZE, "UDIF run table size"),
                "UDIF block table size"
        );
        if (expectedSize != bytes.length) {
            throw new IOException("UDIF block table has an inconsistent run count");
        }

        for (int index = 0; index < runCount; index++) {
            int offset = BLOCK_TABLE_HEADER_SIZE + index * RUN_SIZE;
            int type = ByteArrayAccess.readIntBigEndian(bytes, offset);
            long sectorStart = uint64(bytes, offset + 8, "UDIF run sector start");
            long sectorCount = uint64(bytes, offset + 16, "UDIF run sector count");
            long compressedOffset = uint64(bytes, offset + 24, "UDIF run compressed offset");
            long compressedLength = uint64(bytes, offset + 32, "UDIF run compressed length");
            if (type == UDIFConstants.BLOCK_COMMENT || type == UDIFConstants.BLOCK_TERMINATOR || sectorCount == 0L) {
                continue;
            }

            long runEndSector = ChannelIO.add(sectorStart, sectorCount, "UDIF run sector range");
            if (runEndSector > tableSectors) {
                throw new IOException("UDIF run exceeds its block-table sector range");
            }
            long logicalOffset = ChannelIO.multiply(
                    ChannelIO.add(firstSector, sectorStart, "UDIF run absolute sector"),
                    UDIFConstants.SECTOR_SIZE,
                    "UDIF run logical offset"
            );
            long logicalLength = ChannelIO.multiply(
                    sectorCount,
                    UDIFConstants.SECTOR_SIZE,
                    "UDIF run logical length"
            );
            ChannelIO.requireRange(logicalOffset, logicalLength, decodedSize, "UDIF logical run");

            if (type == UDIFConstants.BLOCK_ZEROES || type == UDIFConstants.BLOCK_IGNORE) {
                runs.add(new UDIFRun(type, logicalOffset, logicalLength, 0L, 0L));
                continue;
            }
            if (!knownEncodedType(type)) {
                throw new IOException("Unsupported UDIF block type 0x" + Integer.toHexString(type));
            }
            if (compressedLength == 0L) {
                throw new IOException("Encoded UDIF run has no physical bytes");
            }
            long physicalOffset = ChannelIO.add(dataStart, compressedOffset, "UDIF run physical offset");
            ChannelIO.requireRange(
                    physicalOffset,
                    compressedLength,
                    ChannelIO.add(trailer.dataForkOffset(), trailer.dataForkLength(), "UDIF data-fork end"),
                    "UDIF physical run"
            );
            if (physicalOffset < trailer.dataForkOffset()) {
                throw new IOException("UDIF physical run precedes the data fork");
            }
            if (type == UDIFConstants.BLOCK_RAW && compressedLength != logicalLength) {
                throw new IOException("Raw UDIF run length differs from its decoded length");
            }
            runs.add(new UDIFRun(type, logicalOffset, logicalLength, physicalOffset, compressedLength));
        }
    }

    /// Returns whether a run type describes encoded bytes supported or diagnosed by the block channel.
    private static boolean knownEncodedType(int type) {
        return type == UDIFConstants.BLOCK_RAW
                || type == UDIFConstants.BLOCK_ADC
                || type == UDIFConstants.BLOCK_ZLIB
                || type == UDIFConstants.BLOCK_BZIP2
                || type == UDIFConstants.BLOCK_LZFSE
                || type == UDIFConstants.BLOCK_LZMA;
    }

    /// Returns a dictionary value following the requested key.
    private static Element dictionaryValue(Element dictionary, String key) {
        List<Element> elements = childElements(dictionary);
        for (int index = 0; index + 1 < elements.size(); index++) {
            Element element = elements.get(index);
            if ("key".equals(element.getTagName()) && key.equals(element.getTextContent())) {
                return elements.get(index + 1);
            }
        }
        return null;
    }

    /// Returns the first direct child element with the requested tag.
    private static Element firstChildElement(Element parent, String tagName) throws IOException {
        for (Element element : childElements(parent)) {
            if (tagName.equals(element.getTagName())) {
                return element;
            }
        }
        throw new IOException("UDIF property list has no " + tagName + " root");
    }

    /// Returns direct child elements without exposing the live DOM node list.
    private static List<Element> childElements(Element parent) {
        NodeList children = parent.getChildNodes();
        ArrayList<Element> elements = new ArrayList<>();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    /// Reads an unsigned 64-bit field and rejects values above Java's signed range.
    private static long uint64(byte[] bytes, int offset, String description) throws IOException {
        long value = ByteArrayAccess.readLongBigEndian(bytes, offset);
        if (value < 0L) {
            throw new IOException(description + " exceeds the supported signed 64-bit range");
        }
        return value;
    }
}
