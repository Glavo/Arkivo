// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.encoding.detector.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.zip.InflaterInputStream;

/// Generates the lossless metadata projection from pinned, verified Kala model data.
/// Output stays under the supplied build directory and is never installed into sources automatically.
@NotNullByDefault
public final class MetadataModelGenerator {
    /// Selected ASCII-compatible model identities and their JDK validation encodings.
    /// EUC-JP shares the byte layout of the modeled EUC-JIS-2004 subset, but validation remains EUC-JP.
    private static final @Unmodifiable Map<String, String> CHARSETS = Map.ofEntries(
            Map.entry("utf-8", "UTF-8"), Map.entry("gb18030", "GB18030"),
            Map.entry("big5hkscs", "Big5-HKSCS"), Map.entry("cp932", "windows-31j"),
            Map.entry("euc_jis_2004", "EUC-JP"), Map.entry("cp949", "x-windows-949"),
            Map.entry("cp437", "IBM437"), Map.entry("cp850", "IBM850"), Map.entry("cp852", "IBM852"),
            Map.entry("cp866", "IBM866"), Map.entry("cp1250", "windows-1250"),
            Map.entry("cp1251", "windows-1251"), Map.entry("cp1252", "windows-1252"),
            Map.entry("cp1253", "windows-1253"), Map.entry("cp1254", "windows-1254"),
            Map.entry("cp1255", "windows-1255"), Map.entry("cp1256", "windows-1256"),
            Map.entry("cp1257", "windows-1257"), Map.entry("cp1258", "windows-1258"),
            Map.entry("cp874", "x-windows-874"), Map.entry("koi8-r", "KOI8-R"),
            Map.entry("koi8-u", "KOI8-U"), Map.entry("mac-roman", "x-MacRoman"),
            Map.entry("iso8859-1", "ISO-8859-1"), Map.entry("iso8859-2", "ISO-8859-2"),
            Map.entry("iso8859-5", "ISO-8859-5"), Map.entry("iso8859-7", "ISO-8859-7"),
            Map.entry("iso8859-9", "ISO-8859-9"), Map.entry("iso8859-15", "ISO-8859-15"));

    /// Prevents instantiation.
    private MetadataModelGenerator() {
    }

    /// Writes an uncompressed AMD1 resource preserving every weight and original model norm.
    /// @param args one output directory
    /// @throws Exception if the source models or output cannot be read or written
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected output directory");
        for (String charset : CHARSETS.values()) {
            if (!Charset.isSupported(charset)) throw new IllegalStateException("Missing source charset: " + charset);
        }
        byte[] idf;
        try (var input = Objects.requireNonNull(MetadataModelGenerator.class
                .getResourceAsStream("/kala/encdet/internal/idf.bin"))) {
            idf = input.readNBytes(65536);
            if (idf.length != 65536 || input.read() != -1) throw new IOException("Invalid upstream IDF length");
        }
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        Path output = directory.resolve("bigram-models.bin");
        try (var source = new DataInputStream(Objects.requireNonNull(MetadataModelGenerator.class
                .getResourceAsStream("/kala/encdet/internal/models.bin")))) {
            if (source.readInt() != 0x434D4432) throw new IOException("Invalid upstream model header");
            int count = source.readInt();
            if (count < 1 || count > 10000) throw new IOException("Invalid upstream model count");
            var headers = new ArrayList<Header>(count);
            int selected = 0;
            for (int i = 0; i < count; i++) {
                int length = source.readInt();
                if (length < 1 || length > 256) throw new IOException("Invalid upstream model name length");
                byte[] bytes = new byte[length];
                source.readFully(bytes);
                String name = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                int slash = name.indexOf('/');
                if (slash < 1 || slash == name.length() - 1) throw new IOException("Invalid upstream model name");
                @Nullable String charset = CHARSETS.get(name.substring(slash + 1));
                if (charset != null) {
                    charset = Charset.forName(charset).name();
                    selected++;
                }
                double norm = source.readDouble();
                if (!Double.isFinite(norm) || norm < 0) throw new IOException("Invalid upstream model norm");
                headers.add(new Header(name, charset, norm));
            }
            try (var data = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(output)));
                 var rows = new DataInputStream(new InflaterInputStream(source))) {
                data.writeInt(0x414D4431);
                data.write(idf);
                data.writeInt(selected);
                byte[] row = new byte[65536];
                for (var header : headers) {
                    rows.readFully(row);
                    if (header.charset == null) continue;
                    data.writeUTF(header.charset);
                    data.writeUTF(header.name);
                    data.writeDouble(header.norm);
                    int size = 0;
                    for (byte value : row) if (value != 0) size++;
                    data.writeInt(size);
                    int previous = 0;
                    for (int pair = 0; pair < row.length; pair++) {
                        if (row[pair] == 0) continue;
                        data.writeShort(pair - previous);
                        previous = pair;
                    }
                    for (byte value : row) if (value != 0) data.writeByte(value);
                }
                if (rows.read() != -1) throw new IOException("Trailing upstream model rows");
            }
        }
    }

    /// Retains upstream row identity and normalization before streaming the compressed matrices.
    /// @param name upstream language and encoding
    /// @param charset selected JDK charset name, or null for excluded encodings
    /// @param norm original full-model L2 norm
    @NotNullByDefault
    private record Header(String name, @Nullable String charset, double norm) {
    }
}
