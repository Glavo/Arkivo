// Copyright Mozilla Foundation and (c) 2026 Glavo
// SPDX-License-Identifier: CC0-1.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;

import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HexFormat;
import java.util.Properties;

/// Loads the shared character-class and probability tables.
@NotNullByDefault
final class Models {
    /// Single-byte candidates in upstream table order.
    static final SingleByteData @Unmodifiable [] SINGLE_BYTE_DATA;
    /// Most frequent simplified Chinese characters, in frequency order.
    static final char @Unmodifiable [] FREQUENT_SIMPLIFIED;
    /// Most frequent Japanese ideographs, in frequency order.
    static final char @Unmodifiable [] FREQUENT_KANJI;
    /// Most frequent Korean syllables, in frequency order.
    static final char @Unmodifiable [] FREQUENT_HANGUL;

    static {
        var tables = new Properties();
        try (@Nullable var input = Models.class.getResourceAsStream("models.properties")) {
            if (input == null) {
                throw new IOException("Missing character encoding model");
            }
            tables.load(input);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
        var decoded = new java.util.HashMap<String, byte[]>();
        tables.forEach((name, value) -> decoded.put((String) name, HexFormat.of().parseHex((String) value)));
        FREQUENT_SIMPLIFIED = characters(decoded.get("frequent_simplified"));
        FREQUENT_KANJI = characters(decoded.get("frequent_kanji"));
        FREQUENT_HANGUL = characters(decoded.get("frequent_hangul"));
        SINGLE_BYTE_DATA = new SingleByteData[]{
                new SingleByteData("WINDOWS-1258", decoded.get("latin_ascii"), decoded.get("windows_1258"),
                        decoded.get("vietnamese"), 27, 25),
                new SingleByteData("WINDOWS-1250", decoded.get("latin_ascii"), decoded.get("windows_1250"),
                        decoded.get("central"), 27, 41),
                new SingleByteData("ISO-8859-2", decoded.get("latin_ascii"), decoded.get("iso_8859_2"),
                        decoded.get("central"), 27, 41),
                new SingleByteData("WINDOWS-1251", decoded.get("non_latin_ascii"), decoded.get("windows_1251"),
                        decoded.get("cyrillic"), 2, 44),
                new SingleByteData("KOI8-U", decoded.get("non_latin_ascii"), decoded.get("koi8_u"),
                        decoded.get("cyrillic"), 2, 44),
                new SingleByteData("ISO-8859-5", decoded.get("non_latin_ascii"), decoded.get("iso_8859_5"),
                        decoded.get("cyrillic"), 2, 44),
                new SingleByteData("IBM866", decoded.get("non_latin_ascii"), decoded.get("ibm866"),
                        decoded.get("cyrillic"), 2, 44),
                new SingleByteData("WINDOWS-1252", decoded.get("latin_ascii"), decoded.get("windows_1252"),
                        decoded.get("western"), 27, 32),
                new SingleByteData("WINDOWS-1252", decoded.get("latin_ascii"), decoded.get("windows_1252_icelandic"),
                        decoded.get("icelandic"), 27, 13),
                new SingleByteData("WINDOWS-1253", decoded.get("non_latin_ascii"), decoded.get("windows_1253"),
                        decoded.get("greek"), 2, 35),
                new SingleByteData("ISO-8859-7", decoded.get("non_latin_ascii"), decoded.get("iso_8859_7"),
                        decoded.get("greek"), 2, 35),
                new SingleByteData("WINDOWS-1254", decoded.get("turkish_ascii"), decoded.get("windows_1254"),
                        decoded.get("turkish"), 26, 13),
                new SingleByteData("WINDOWS-1255", decoded.get("non_latin_ascii"), decoded.get("windows_1255"),
                        decoded.get("hebrew"), 2, 34),
                new SingleByteData("ISO-8859-8", decoded.get("non_latin_ascii"), decoded.get("iso_8859_8"),
                        decoded.get("hebrew"), 2, 34),
                new SingleByteData("WINDOWS-1256", decoded.get("non_latin_ascii"), decoded.get("windows_1256"),
                        decoded.get("arabic"), 2, 51),
                new SingleByteData("ISO-8859-6", decoded.get("non_latin_ascii"), decoded.get("iso_8859_6"),
                        decoded.get("arabic"), 2, 51),
                new SingleByteData("WINDOWS-1257", decoded.get("latin_ascii"), decoded.get("windows_1257"),
                        decoded.get("baltic"), 27, 19),
                new SingleByteData("ISO-8859-13", decoded.get("latin_ascii"), decoded.get("iso_8859_13"),
                        decoded.get("baltic"), 27, 19),
                new SingleByteData("ISO-8859-4", decoded.get("latin_ascii"), decoded.get("iso_8859_4"),
                        decoded.get("baltic"), 27, 19),
                new SingleByteData("x-windows-874", decoded.get("non_latin_ascii"), decoded.get("windows_874"),
                        decoded.get("thai"), 2, 70)
        };
    }

    /// Prevents instantiation.
    private Models() {
    }

    /// Converts the big-endian frequency table to UTF-16 code units.
    private static char[] characters(byte[] bytes) {
        if (bytes.length != 256) {
            throw new ExceptionInInitializerError("Invalid frequency table length");
        }
        var result = new char[128];
        for (int i = 0; i < result.length; i++) {
            result[i] = (char) ((bytes[2 * i] & 0xFF) << 8 | (bytes[2 * i + 1] & 0xFF));
        }
        return result;
    }
}
