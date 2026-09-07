// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar.internal;

import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.archive.ar.ArArkivoEntryAttributeView;
import org.glavo.arkivo.archive.ar.ArArkivoStreamingReader;
import org.glavo.arkivo.archive.ar.ArArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies complete AR header preparation before any member bytes are emitted.
@NotNullByDefault
final class ArHeaderEncodingTest {
    /// Verifies invalid metadata cannot leave a partial header after either a fixed or staged member.
    @ParameterizedTest
    @MethodSource("completionFailures")
    void discardsUnencodableMembers(InvalidField field, boolean longName, boolean staged, boolean priorEntry)
            throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(target, ArkivoEditStorage.memory())) {
            if (priorEntry) {
                writer.beginFile("before").close();
            }
            byte @Unmodifiable [] before = target.toByteArray();
            ArkivoStreamingWriter.Entry entry = writer.beginFile(longName ? "long-member-name.bin" : "bad");
            ArArkivoEntryAttributeView view = Objects.requireNonNull(entry.attributeView(ArArkivoEntryAttributeView.class));
            field.configure(view);
            if (staged) {
                OutputStream body = entry.openOutputStream();
                body.write(1);
                assertThrows(IOException.class, body::close);
                assertArrayEquals(before, target.toByteArray());
                body.close();
            } else {
                assertThrows(IOException.class, entry::close);
                assertArrayEquals(before, target.toByteArray());
                entry.close();
            }
            assertThrows(IllegalStateException.class, () -> view.setUserId(0L));
            writer.beginFile("after").close();
        }
        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(new ByteArrayInputStream(target.toByteArray()))) {
            if (priorEntry) {
                assertTrue(reader.next());
                assertEquals("before", reader.readAttributes().path());
            }
            assertTrue(reader.next());
            assertEquals("after", reader.readAttributes().path());
            assertFalse(reader.next());
        }
    }

    /// Verifies a failed direct-body open leaves metadata configurable because no body ownership was transferred.
    @ParameterizedTest
    @MethodSource("openingFailures")
    void retriesDirectBodyOpenAfterMetadataCorrection(InvalidField field, boolean longName) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        String path = longName ? "long-member-name.bin" : "entry";
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(target, ArkivoEditStorage.memory())) {
            ArkivoStreamingWriter.Entry entry = writer.beginFile(path);
            ArArkivoEntryAttributeView view = Objects.requireNonNull(entry.attributeView(ArArkivoEntryAttributeView.class));
            view.setSize(1L);
            field.configure(view);
            assertThrows(IOException.class, entry::openOutputStream);
            assertEquals(0, target.size());
            view.setTimes(FileTime.fromMillis(0L), null, null);
            view.setUserId(0L);
            view.setGroupId(0L);
            view.setMode(0100644);
            try (OutputStream body = entry.openOutputStream()) {
                body.write(7);
            }
        }
        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(new ByteArrayInputStream(target.toByteArray()))) {
            assertTrue(reader.next());
            assertEquals(path, reader.readAttributes().path());
            try (var body = reader.openInputStream()) {
                assertArrayEquals(new byte[]{7}, body.readAllBytes());
            }
            assertFalse(reader.next());
        }
    }

    /// Verifies exact-width decimal fields, octal mode bits, padding, and the fixed header trailer.
    @Test
    void encodesFixedWidthFieldsWithoutTruncation() throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        int mode = (0xFFFFFF & ~0170000) | 0100000;
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(target, ArkivoEditStorage.memory())) {
            ArkivoStreamingWriter.Entry entry = writer.beginFile("entry");
            ArArkivoEntryAttributeView view = Objects.requireNonNull(entry.attributeView(ArArkivoEntryAttributeView.class));
            view.setTimes(FileTime.from(Instant.ofEpochSecond(999_999_999_999L)), null, null);
            view.setUserId(999_999L);
            view.setGroupId(999_999L);
            view.setMode(mode);
            entry.close();
        }
        String expected = "!<arch>\n" + "entry/          " + "999999999999" + "999999" + "999999"
                + Integer.toOctalString(mode) + "0         " + "`\n";
        assertArrayEquals(expected.getBytes(StandardCharsets.US_ASCII), target.toByteArray());
        assertEquals(68, target.size());
    }

    /// Supplies metadata failures with short and BSD long names for both body-completion paths.
    private static Stream<Arguments> completionFailures() {
        return Stream.of(InvalidField.values()).flatMap(field -> Stream.of(false, true)
                .flatMap(longName -> Stream.of(false, true).flatMap(staged -> Stream.of(false, true)
                        .map(priorEntry -> Arguments.of(field, longName, staged, priorEntry)))));
    }

    /// Supplies metadata failures for direct-body opens that can be retried before output.
    private static Stream<Arguments> openingFailures() {
        return Stream.of(InvalidField.values()).flatMap(field -> Stream.of(false, true)
                .map(longName -> Arguments.of(field, longName)));
    }

    /// Numeric fields accepted by their mutable view but not representable in a fixed AR header.
    @NotNullByDefault
    private enum InvalidField {
        /// A timestamp before the AR epoch.
        NEGATIVE_TIME,
        /// A timestamp needing thirteen decimal digits.
        WIDE_TIME,
        /// A user identifier needing seven decimal digits.
        USER,
        /// A group identifier needing seven decimal digits.
        GROUP,
        /// A regular-file mode needing nine octal digits.
        MODE;

        /// Configures the selected unrepresentable field without changing the member type.
        private void configure(ArArkivoEntryAttributeView view) throws IOException {
            switch (this) {
                case NEGATIVE_TIME -> view.setTimes(FileTime.fromMillis(-1000L), null, null);
                case WIDE_TIME -> view.setTimes(FileTime.from(Instant.ofEpochSecond(1_000_000_000_000L)), null, null);
                case USER -> view.setUserId(1_000_000L);
                case GROUP -> view.setGroupId(1_000_000L);
                case MODE -> view.setMode((1 << 24) | 0100000);
            }
        }
    }
}
