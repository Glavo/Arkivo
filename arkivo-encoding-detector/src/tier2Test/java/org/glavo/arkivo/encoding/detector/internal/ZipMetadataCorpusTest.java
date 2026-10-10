// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.apache.commons.compress.archivers.zip.ZipFile;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/// Evaluates original raw ZIP names against explicit assertions from pinned upstream test sources.
/// Corpus failures are reported without changing weights or weakening authoritative Unicode handling.
@NotNullByDefault
class ZipMetadataCorpusTest {
    /// Uses names and CP866 bytes asserted by JSZip 3.10.1 test/asserts/unicode.js.
    @Test
    void jszipRussianNames() throws Exception {
        assertEquals(2, compare(fixture("jszip", "test/ref/local_encoding_in_name.zip"), "IBM866",
                List.of("Новая папка/", "Новая папка/Новый текстовый документ.txt")));
    }

    /// Uses the name asserted by Zip4j 2.11.5 ExtractZipFileIT.testExtractZipFileWithChineseCharsetGBK.
    @Test
    void sevenZipChineseName() throws Exception {
        compare(fixture("zip4j", "src/test/resources/test-archives/testfile_with_chinese_filename_by_7zip.zip"),
                "GBK", List.of("fff - 副本.txt"));
    }

    /// Keeps WinRAR's Unicode extra field authoritative even when raw legacy bytes lost the euro sign.
    /// JSZip 3.10.1 test/asserts/load.js specifies the resulting name independently of any detector.
    @Test
    void unicodeExtraFieldBypassesDetection() throws Exception {
        var options = ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(input -> {
            fail("Authoritative Unicode name must not invoke the legacy detector");
            return "";
        });
        try (var reader = ZipArkivoStreamingReader.open(fixture("jszip", "test/ref/winrar_utf8_in_name.zip"), options)) {
            assertTrue(reader.next());
            assertEquals("€15.txt", reader.readAttributes().path());
            assertFalse(reader.next());
        }
    }

    /// Resolves a fixture directory prepared by the existing verified Gradle download tasks.
    private static Path fixture(String corpus, String relative) {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo." + corpus + ".testDataDirectory")))
                .resolve(relative);
    }

    /// Reads raw central-directory names and returns the number correctly selected without context.
    /// Expected names come from upstream assertions, not the reference parser or a detector.
    private static int compare(Path path, String encoding, List<String> expectedNames) throws Exception {
        var charset = Charset.forName(encoding);
        var names = new ArrayList<String>();
        var rawNames = new ArrayList<ByteBuffer>();
        try (var archive = ZipFile.builder().setPath(path).setCharset(charset).get()) {
            var entries = archive.getEntries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var bytes = ByteBuffer.wrap(entry.getRawName());
                String name = charset.newDecoder().decode(bytes.duplicate()).toString();
                if (expectedNames.contains(name)) {
                    assertFalse(entry.getGeneralPurposeBit().usesUTF8ForNames());
                    names.add(name);
                    rawNames.add(bytes);
                }
            }
        }
        assertEquals(new java.util.HashSet<>(expectedNames), new java.util.HashSet<>(names));
        assertEquals(expectedNames.size(), names.size());
        int previous = 0;
        int isolated = 0;
        int joint = 0;
        for (int index = 0; index < names.size(); index++) {
            String expected = names.get(index);
            var input = rawNames.get(index);
            var others = new ArrayList<>(rawNames);
            others.remove(index);
            String old = ZipArchiveOptions.DEFAULT_LEGACY_METADATA_DECODER.decode(input);
            String single = ZipMetadataScoringTest.selectForEvaluation(input, DetectionEngine.scoreField(input));
            String combined = ZipMetadataScoringTest.selectWithEvidence(input, DetectionEngine.scoreFields(others));
            if (expected.equals(old)) previous++;
            if (expected.equals(single)) isolated++;
            if (expected.equals(combined)) joint++;
            System.out.println(path.getFileName() + " expected=" + expected + " isolated=" + single + " joint=" + combined);
        }
        System.out.printf(java.util.Locale.ROOT, "archive=%s,total=%d,current_zip=%d,field_scores=%d,joint_scores=%d%n",
                path.getFileName(), names.size(), previous, isolated, joint);
        return isolated;
    }
}
