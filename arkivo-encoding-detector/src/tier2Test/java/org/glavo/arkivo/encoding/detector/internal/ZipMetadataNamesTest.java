// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies joint decoding of short, independently specified names in generated ZIP archives.
@NotNullByDefault
class ZipMetadataNamesTest {
    /// Covers 208 short-name variants without deriving expected text from another detector.
    @ParameterizedTest(name = "{0}")
    @MethodSource("shortNameGroups")
    void shortNamesWithArchiveEvidence(String encoding, String first, String second, String third, String fourth,
                                       @TempDir Path directory) throws Exception {
        var charset = Charset.forName(encoding);
        var stems = List.of(first, second, third, fourth);
        Path file = directory.resolve("names.zip");
        for (int index = 0; index < stems.size(); index++) {
            String stem = stems.get(index);
            for (String name : List.of(stem, stem + ".txt", "dir/" + stem + "_001.txt", "dir/" + stem + "_999.bin")) {
                var names = new ArrayList<String>();
                for (int other = 0; other < stems.size(); other++) if (other != index) names.add(stems.get(other));
                names.add(name);
                var output = new ByteArrayOutputStream();
                try (var zip = new ZipOutputStream(output, charset)) {
                    for (String entry : names) {
                        var encoded = charset.newEncoder().encode(CharBuffer.wrap(entry));
                        assertEquals(entry, charset.newDecoder().decode(encoded).toString());
                        zip.putNextEntry(new ZipEntry(entry));
                        zip.closeEntry();
                    }
                }
                byte[] bytes = output.toByteArray();
                // The generated archives contain no ZIP64 records or comments.
                // Clear both UTF-8 flags so the UTF-8 group also exercises inference.
                var records = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                int central = records.getInt(bytes.length - 6);
                while (central < bytes.length - 22) {
                    assertEquals(0x02014b50, records.getInt(central));
                    int local = records.getInt(central + 42);
                    records.putShort(local + 6, (short) (records.getShort(local + 6) & ~0x800));
                    records.putShort(central + 8, (short) (records.getShort(central + 8) & ~0x800));
                    central += 46 + Short.toUnsignedInt(records.getShort(central + 28))
                            + Short.toUnsignedInt(records.getShort(central + 30))
                            + Short.toUnsignedInt(records.getShort(central + 32));
                }
                Files.write(file, bytes);
                try (var archive = ZipArkivoFileSystem.open(file)) {
                    for (String expected : names) {
                        assertArrayEquals(new byte[0], Files.readAllBytes(archive.getPath(expected)),
                                encoding + ": " + expected);
                    }
                }
            }
        }
    }

    /// Supplies fixed names whose bytes round-trip through each selected charset.
    static String[][] shortNameGroups() {
        return new String[][]{
                {"IBM437", "é", "ö", "résumé", "München"},
                {"IBM850", "ø", "å", "smør", "données"},
                {"IBM852", "ł", "ž", "Łódź", "žluťoučký"},
                {"windows-1252", "é", "ö", "résumé", "München"},
                {"windows-1250", "ł", "ž", "Łódź", "žluťoučký"},
                {"windows-1251", "я", "ж", "файл", "Привет"},
                {"IBM866", "я", "ж", "файл", "Привет"},
                {"GB18030", "中", "档", "文件", "软件安装说明"},
                {"Big5-HKSCS", "中", "檔", "檔案", "軟體安裝說明"},
                {"windows-31j", "あ", "字", "日本語", "こんにちは世界"},
                {"EUC-JP", "あ", "字", "日本語", "こんにちは世界"},
                {"x-windows-949", "가", "한", "자료", "사용설명파일"},
                {"UTF-8", "é", "中", "日本語", "자료"}
        };
    }
}
