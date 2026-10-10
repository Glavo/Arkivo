// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Compares isolated-field policies on authored filename probes, not an accuracy acceptance corpus.
/// Expected text is fixed before encoding. No external detector supplies expected results.
@NotNullByDefault
class ZipMetadataScoringTest {
    /// Reports exact-text results and abstentions without assigning a charset to uncertain fields.
    @Test
    void compareShortNames() throws Exception {
        String[][] groups = {
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
        System.out.println("charset,non_ascii_length,total,current_zip,reference_detector,field_correct,joint_correct,field_unknown,joint_unknown");
        for (String[] group : groups) {
            var charset = Charset.forName(group[0]);
            var detector = new EncodingDetector();
            int[][] counts = new int[2][7];
            for (int i = 1; i < group.length; i++) {
                String stem = group[i];
                var context = new java.util.ArrayList<ByteBuffer>();
                for (int other = 1; other < group.length; other++) {
                    if (other != i) {
                        context.add(charset.newEncoder().encode(CharBuffer.wrap(group[other])));
                    }
                }
                // Exclude the evaluated stem and every filename variant of it from its context.
                var evidence = detector.prepare(context, EncodingDetector.Kind.NAME);
                for (String name : List.of(stem, stem + ".txt", "dir/" + stem + "_001.txt", "dir/" + stem + "_999.bin")) {
                    ByteBuffer bytes = charset.newEncoder().encode(CharBuffer.wrap(name));
                    assertEquals(name, charset.newDecoder().decode(bytes.duplicate()).toString());
                    int bucket = stem.codePoints().filter(value -> value >= 128).count() <= 2 ? 0 : 1;
                    int[] row = counts[bucket];
                    row[0]++;
                    String previous = ZipArchiveOptions.DEFAULT_LEGACY_METADATA_DECODER.decode(bytes);
                    String continuous = ReferenceEncodingDetector.detect(bytes).decode(bytes.duplicate()).toString();
                    @Nullable String field = decodeForEvaluation(detector, bytes, null);
                    @Nullable String joint = decodeForEvaluation(detector, bytes, evidence);
                    if (previous.equals(name)) row[1]++;
                    if (continuous.equals(name)) row[2]++;
                    if (name.equals(field)) row[3]++;
                    if (name.equals(joint)) row[4]++;
                    if (field == null) row[5]++;
                    if (joint == null) row[6]++;
                    assertEquals(0, bytes.position());
                    if (previous.equals(name) && !name.equals(field)) {
                        System.out.println("REGRESSION " + group[0] + " " + name + " -> " + field);
                    }
                }
            }
            for (int bucket = 0; bucket < counts.length; bucket++) {
                int[] row = counts[bucket];
                System.out.printf(java.util.Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%d,%d%n", group[0],
                        bucket == 0 ? "1-2" : "3+", row[0], row[1], row[2], row[3], row[4], row[5], row[6]);
            }
        }
    }

    /// Decodes only an actual selection; uncertainty is measured separately and never silently replaced.
    static @Nullable String decodeForEvaluation(EncodingDetector detector, ByteBuffer input,
                                                @Nullable EncodingEvidence context) throws Exception {
        @Nullable var charset = detector.detect(input, EncodingDetector.Kind.NAME, context);
        return charset == null ? null : charset.newDecoder().decode(input.duplicate()).toString();
    }
}
