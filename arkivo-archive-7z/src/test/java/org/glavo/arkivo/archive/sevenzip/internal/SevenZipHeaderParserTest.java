// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.internal.ArkivoReadLimitTracker;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests 7z metadata header parsing behavior.
@NotNullByDefault
public final class SevenZipHeaderParserTest {
    /// Parses a standalone test header without archive stream access or resource limits.
    private static @Unmodifiable List<SevenZipEntryMetadata> parseEntries(byte[] header) throws IOException {
        return parseEntries(header, null);
    }

    /// Parses a test header with optional packed streams and a fresh unrestricted budget.
    private static @Unmodifiable List<SevenZipEntryMetadata> parseEntries(
            byte[] header,
            SevenZipHeaderParser.@Nullable PackedStreamOpener packedStreamOpener
    ) throws IOException {
        return SevenZipHeaderParser.parseEntries(
                header, packedStreamOpener, null,
                ArkivoReadLimitTracker.fromLimits(ArchiveReadLimits.UNLIMITED),
                ArchiveReadLimits.UNLIMITED);
    }

    /// Rejects metadata counts whose required payload is absent before allocating count-sized arrays.
    @ParameterizedTest
    @MethodSource("impossibleCounts")
    public void rejectsImpossibleCounts(byte[] header) {
        assertThrows(IOException.class, () -> parseEntries(header));
    }

    /// Retains an immutable entry snapshot rather than the caller's mutable list.
    @Test
    void archiveMetadataSnapshotsParsedEntries() throws IOException {
        List<SevenZipEntryMetadata> entries = new ArrayList<>(parseEntries(new byte[]{
                0x01, 0x05, 0x01,
                0x0e, 0x01, (byte) 0x80,
                0x11, 0x05, 0x00, 'a', 0x00, 0x00, 0x00,
                0x00, 0x00
        }));
        assertEquals(1, entries.size());
        SevenZipSignatureHeader signature = new SevenZipSignatureHeader(0, 4, 0, 0, 0);
        SevenZipArchiveMetadata metadata = new SevenZipArchiveMetadata(signature, entries);

        entries.clear();
        assertEquals(signature, metadata.signatureHeader());
        assertEquals(1, metadata.entries().size());
        assertEquals("a", metadata.entries().get(0).path());
        assertThrows(UnsupportedOperationException.class, () -> metadata.entries().clear());
        assertThrows(NullPointerException.class, () -> new SevenZipArchiveMetadata(null, List.of()));
        assertThrows(NullPointerException.class, () -> new SevenZipArchiveMetadata(signature, null));
    }

    /// Supplies compact headers with impossible file, stream, folder, coder, and substream counts.
    private static Stream<byte[]> impossibleCounts() {
        return Stream.of(
                oversizedCount(new int[]{1, 5}, new int[]{0, 0}),
                oversizedCount(new int[]{1, 5}, new int[]{0x11, 1, 0, 0, 0}),
                oversizedCount(new int[]{1, 4, 6, 0}, new int[]{0, 0, 0}),
                oversizedCount(new int[]{1, 4, 7, 0x0b}, new int[]{0, 0, 0}),
                oversizedCount(new int[]{1, 4, 7, 0x0b, 1, 0}, new int[]{0, 0, 0}),
                oversizedCount(new int[]{1, 4, 7, 0x0b, 1, 0, 1, 0x11, 0}, new int[]{1, 0}),
                oversizedCount(new int[]{1, 4, 7, 0x0b, 1, 0, 1, 1, 0, 0x0c, 0, 0, 8, 0x0d},
                        new int[]{9, 0, 0, 0}));
    }

    /// Inserts an encoded Integer.MAX_VALUE between the supplied header fragments.
    private static byte[] oversizedCount(int[] prefix, int[] suffix) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int value : prefix) {
            bytes.write(value);
        }
        bytes.writeBytes(new byte[]{(byte) 0xf0, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f});
        for (int value : suffix) {
            bytes.write(value);
        }
        return bytes.toByteArray();
    }

    /// Rejects an encoded header that decodes to another encoded header without opening it recursively.
    @Test
    public void encodedHeaderMustDecodeToPlainHeader() {
        byte[] header = {0x17, 6, 0, 1, 9, 18, 0, 7, 0x0b, 1, 0, 1, 1, 0, 0x0c, 18, 0, 0};
        AtomicInteger opens = new AtomicInteger();
        IOException failure = assertThrows(IOException.class, () -> parseEntries(
                header, (offset, size) -> {
                    if (opens.incrementAndGet() > 1) {
                        throw new IOException("Unexpected recursive stream open");
                    }
                    return new ByteArrayInputStream(header);
                }));
        assertEquals("Decoded 7z header must begin with kHeader", failure.getMessage());
        assertEquals(1, opens.get());
    }

    /// Verifies that empty streams may omit unpack metadata before empty substream metadata.
    @Test
    public void emptyStreamsAllowSubStreamsInfoWithoutUnpackInfo() throws IOException {
        assertEquals(
                List.of(),
                parseEntries(new byte[]{0x01, 0x04, 0x08, 0x00, 0x00, 0x00})
        );
    }

    /// Verifies that empty archive headers may contain redundant top-level end markers.
    @Test
    public void emptyArchiveAllowsTrailingEndMarkerPadding() throws IOException {
        assertEquals(
                List.of(),
                parseEntries(new byte[]{0x01, 0x04, 0x08, 0x00, 0x00, 0x00, 0x00})
        );
    }

    /// Verifies that top-level end-marker padding does not make arbitrary trailing metadata acceptable.
    @Test
    public void emptyArchiveRejectsNonEndTrailingData() {
        IOException exception = assertThrows(
                IOException.class,
                () -> parseEntries(
                        new byte[]{0x01, 0x04, 0x08, 0x00, 0x00, 0x00, 0x01}
                )
        );

        assertEquals("Trailing bytes in 7z header: [1]", exception.getMessage());
    }

    /// Verifies that packed streams require unpack metadata before substream metadata.
    @Test
    public void subStreamsInfoBeforeUnpackInfoIsRejected() {
        IOException exception = assertThrows(
                IOException.class,
                () -> parseEntries(new byte[]{
                        0x01,
                        0x04,
                        0x06, 0x00, 0x01, 0x09, 0x01, 0x00,
                        0x08, 0x00,
                        0x00,
                        0x00
                })
        );

        assertEquals("7z substreams appeared before folders", exception.getMessage());
    }

    /// Verifies that unpack metadata cannot be appended after an empty substream block.
    @Test
    public void unpackInfoAfterSubStreamsInfoIsRejected() {
        IOException exception = assertThrows(
                IOException.class,
                () -> parseEntries(new byte[]{
                        0x01,
                        0x04,
                        0x08, 0x00,
                        0x07, 0x00,
                        0x00,
                        0x00
                })
        );

        assertEquals("7z folders appeared after substreams", exception.getMessage());
    }

    /// Verifies unknown top-level properties are rejected as checked input failures.
    @Test
    public void unknownHeaderPropertyIsCheckedFailure() {
        IOException exception = assertThrows(
                IOException.class,
                () -> parseEntries(new byte[]{0x01, 0x7f})
        );

        assertEquals("Unsupported 7z header property: 0x7f", exception.getMessage());
    }

    /// Verifies encoded headers without archive stream access fail through the checked parser contract.
    @Test
    public void encodedHeaderWithoutArchiveAccessIsCheckedFailure() {
        IOException exception = assertThrows(
                IOException.class,
                () -> parseEntries(new byte[]{0x17})
        );

        assertEquals("7z encoded headers require archive stream access", exception.getMessage());
    }

    /// Verifies coder flags and methods controlled by archive bytes cannot leak runtime failures.
    @Test
    public void unsupportedCoderMetadataIsCheckedFailure() {
        IOException flagsFailure = assertThrows(
                IOException.class,
                () -> parseEntries(
                        encodedHeaderWithCoder(0xc1, 0x00),
                        (offset, size) -> new ByteArrayInputStream(new byte[0])
                )
        );
        assertEquals("Unsupported 7z coder flags: 0xc1", flagsFailure.getMessage());

        IOException methodFailure = assertThrows(
                IOException.class,
                () -> parseEntries(
                        encodedHeaderWithCoder(0x01, 0x7e),
                        (offset, size) -> new ByteArrayInputStream(new byte[0])
                )
        );
        assertEquals("Unsupported 7z coder method: [126]", methodFailure.getMessage());
    }

    /// Verifies malformed folder graphs are rejected before stream arrays can be indexed inconsistently.
    ///
    /// @param description the malformed graph case name
    /// @param folder the serialized folder definition
    /// @param expectedMessage the exact checked-failure diagnostic
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedFolderGraphs")
    public void malformedFolderGraphsAreCheckedFailures(
            String description,
            byte @Unmodifiable [] folder,
            String expectedMessage
    ) {
        IOException exception = assertThrows(
                IOException.class,
                () -> parseEntries(headerWithFolder(folder))
        );
        assertEquals(expectedMessage, exception.getMessage(), description);
    }

    /// Returns malformed coder graphs and the validation error required for each graph.
    private static Stream<Arguments> malformedFolderGraphs() {
        return Stream.of(
                Arguments.of(
                        "no coders",
                        new byte[]{0x00},
                        "7z folder has no coders"
                ),
                Arguments.of(
                        "zero input streams",
                        new byte[]{0x01, 0x11, 0x00, 0x00, 0x01},
                        "7z coder stream counts must be positive"
                ),
                Arguments.of(
                        "zero output streams",
                        new byte[]{0x01, 0x11, 0x00, 0x01, 0x00},
                        "7z coder stream counts must be positive"
                ),
                Arguments.of(
                        "missing bind input",
                        new byte[]{0x01, 0x11, 0x00, 0x02, 0x02, 0x02, 0x00},
                        "7z folder bind pair references a missing input stream"
                ),
                Arguments.of(
                        "missing bind output",
                        new byte[]{0x01, 0x11, 0x00, 0x02, 0x02, 0x00, 0x02},
                        "7z folder bind pair references a missing output stream"
                ),
                Arguments.of(
                        "duplicate bound input",
                        new byte[]{0x01, 0x11, 0x00, 0x03, 0x03, 0x00, 0x00, 0x00, 0x01},
                        "7z folder has duplicate bound input stream"
                ),
                Arguments.of(
                        "duplicate bound output",
                        new byte[]{0x01, 0x11, 0x00, 0x03, 0x03, 0x00, 0x00, 0x01, 0x00},
                        "7z folder has duplicate bound output stream"
                ),
                Arguments.of(
                        "no packed input",
                        new byte[]{0x01, 0x11, 0x00, 0x01, 0x02, 0x00, 0x00},
                        "7z folder has no packed input stream"
                ),
                Arguments.of(
                        "missing packed input",
                        new byte[]{0x01, 0x11, 0x00, 0x02, 0x01, 0x02, 0x00},
                        "7z folder references a missing packed input stream"
                ),
                Arguments.of(
                        "bound packed input",
                        new byte[]{0x01, 0x11, 0x00, 0x03, 0x02, 0x00, 0x00, 0x00, 0x01},
                        "7z folder packed input stream is already bound"
                ),
                Arguments.of(
                        "duplicate packed input",
                        new byte[]{0x01, 0x11, 0x00, 0x03, 0x02, 0x00, 0x00, 0x01, 0x01},
                        "7z folder has a duplicate packed input stream"
                ),
                Arguments.of(
                        "input stream count overflow",
                        new byte[]{
                                0x02,
                                0x11, 0x00, (byte) 0xf0, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f, 0x01,
                                0x11, 0x00, (byte) 0xf0, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f, 0x01
                        },
                        "7z folder stream count is too large"
                ),
                Arguments.of(
                        "output stream count overflow",
                        new byte[]{
                                0x02,
                                0x11, 0x00, 0x01, (byte) 0xf0, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f,
                                0x11, 0x00, 0x01, (byte) 0xf0, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f
                        },
                        "7z folder stream count is too large"
                )
        );
    }

    /// Wraps one inline folder definition in the shortest plain 7z streams header.
    private static byte @Unmodifiable [] headerWithFolder(byte @Unmodifiable [] folder) {
        byte[] prefix = {0x01, 0x04, 0x07, 0x0b, 0x01, 0x00};
        byte[] header = new byte[prefix.length + folder.length];
        System.arraycopy(prefix, 0, header, 0, prefix.length);
        System.arraycopy(folder, 0, header, prefix.length, folder.length);
        return header;
    }

    /// Verifies complete but cyclic coder graphs remain checked malformed-input failures.
    @Test
    public void cyclicFolderGraphIsCheckedFailure() {
        IOException exception = assertThrows(
                IOException.class,
                () -> parseEntries(new byte[]{
                        0x01,
                        0x04,
                        0x06, 0x00, 0x01, 0x09, 0x00, 0x00,
                        0x07, 0x0b, 0x01, 0x00,
                        0x01, 0x11, 0x00, 0x02, 0x02, 0x00, 0x00,
                        0x0c, 0x00, 0x00, 0x00,
                        0x00,
                        0x00
                })
        );

        assertEquals("Invalid 7z folder coder graph", exception.getMessage());
        assertEquals(IllegalArgumentException.class, exception.getCause().getClass());
        assertEquals("the 7z folder coder bindings contain a cycle", exception.getCause().getMessage());
    }

    /// Returns an encoded-header descriptor with caller-selected coder bytes.
    private static byte[] encodedHeaderWithCoder(int flags, int method) {
        return new byte[]{
                0x17,
                0x06, 0x00, 0x01, 0x09, 0x01, 0x00,
                0x07, 0x0b, 0x01, 0x00, 0x01, (byte) flags, (byte) method, 0x0c, 0x01, 0x00,
                0x00
        };
    }

    /// Verifies that encoded-header read failures are not replaced by runtime cleanup failures.
    @Test
    public void encodedHeaderReadFailureSuppressesRuntimeCloseFailure() {
        IOException exception = assertThrows(
                IOException.class,
                () -> parseEntries(
                        truncatedEncodedCopyHeader(),
                        (offset, size) -> new RuntimeCloseFailingInputStream()
                )
        );

        assertEquals("Unexpected end of 7z encoded header", exception.getMessage());
        assertEquals(1, exception.getSuppressed().length);
        assertEquals(IllegalStateException.class, exception.getSuppressed()[0].getClass());
        assertEquals("close failed", exception.getSuppressed()[0].getMessage());
    }

    /// Verifies a packed-stream open failure closes all previously opened streams and preserves cleanup failures.
    @Test
    public void encodedHeaderOpenFailureClosesPriorPackedStreams() {
        CloseFailingInputStream first = new CloseFailingInputStream("first close failed");
        CloseFailingInputStream second = new CloseFailingInputStream("second close failed");
        AtomicInteger openCount = new AtomicInteger();

        IOException exception = assertThrows(
                IOException.class,
                () -> parseEntries(
                        encodedHeaderWithFourPackedStreams(),
                        (offset, size) -> switch (openCount.getAndIncrement()) {
                            case 0 -> first;
                            case 1 -> second;
                            default -> throw new IOException("open failed");
                        }
                )
        );

        assertEquals("open failed", exception.getMessage());
        assertEquals(3, openCount.get());
        assertEquals(1, first.closeCount());
        assertEquals(1, second.closeCount());
        assertEquals(1, exception.getSuppressed().length);
        Throwable cleanupFailure = exception.getSuppressed()[0];
        assertEquals("first close failed", cleanupFailure.getMessage());
        assertEquals(1, cleanupFailure.getSuppressed().length);
        assertEquals("second close failed", cleanupFailure.getSuppressed()[0].getMessage());
    }

    /// Returns an encoded-header descriptor for a one-byte Copy stream.
    private static byte[] truncatedEncodedCopyHeader() {
        return new byte[]{
                0x17,
                0x06, 0x00, 0x01, 0x09, 0x01, 0x00,
                0x07, 0x0b, 0x01, 0x00, 0x01, 0x01, 0x00, 0x0c, 0x01, 0x00,
                0x00
        };
    }

    /// Returns an encoded-header descriptor whose BCJ2 folder consumes four packed streams.
    private static byte[] encodedHeaderWithFourPackedStreams() {
        return new byte[]{
                0x17,
                0x06, 0x00, 0x04, 0x09, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x07, 0x0b, 0x01, 0x00,
                0x01, 0x14, 0x03, 0x03, 0x01, 0x1b, 0x04, 0x01, 0x00, 0x01, 0x02, 0x03,
                0x0c, 0x00, 0x00,
                0x00
        };
    }

    /// Input stream that reaches EOF immediately and fails at runtime when closed.
    @NotNullByDefault
    private static final class RuntimeCloseFailingInputStream extends ByteArrayInputStream {
        /// Creates an empty runtime close-failing input stream.
        private RuntimeCloseFailingInputStream() {
            super(new byte[0]);
        }

        /// Always fails at runtime when closed.
        @Override
        public void close() {
            throw new IllegalStateException("close failed");
        }
    }

    /// Empty input stream that records and fails every close attempt.
    @NotNullByDefault
    private static final class CloseFailingInputStream extends ByteArrayInputStream {
        /// Failure message emitted by [#close()].
        private final String failureMessage;

        /// Number of close attempts.
        private int closeCount;

        /// Creates an empty stream with the given close-failure message.
        private CloseFailingInputStream(String failureMessage) {
            super(new byte[0]);
            this.failureMessage = failureMessage;
        }

        /// Records the attempt and throws the configured failure.
        @Override
        public void close() throws IOException {
            closeCount++;
            throw new IOException(failureMessage);
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }
}
