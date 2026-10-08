// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.zip.ZipLegacyMetadataDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/// Decodes unmarked ZIP metadata using bounded evidence and deterministic CP437 fallback.
@NotNullByDefault
public final class ZipAutoMetadataDecoder implements ZipLegacyMetadataDecoder {
    /// The stateless default; archive-specific selections are stored in separate immutable instances.
    public static final ZipAutoMetadataDecoder DEFAULT = new ZipAutoMetadataDecoder(Map.of());

    /// The fallback for ambiguous legacy metadata.
    private static final Charset CP437 = Charset.forName("IBM437");

    /// The maximum number of central records inspected for evidence.
    private static final int MAX_RECORDS = 4096;

    /// The maximum number of legacy fields inspected, including repeated samples.
    private static final int MAX_SAMPLES = 256;

    /// The maximum total raw field and extra-field bytes copied while gathering evidence.
    private static final int MAX_SAMPLE_BYTES = 64 * 1024;

    /// The maximum complete field length used for language heuristics.
    private static final int MAX_FIELD_BYTES = 4096;

    /// The maximum number of non-ASCII parent prefixes recorded per sample.
    private static final int MAX_PARENT_PREFIXES = 16;

    /// Indicates that no heuristic candidate was selected.
    private static final int NO_CANDIDATE = -1;

    /// Immutable selections keyed by creator system and metadata kind.
    private final @Unmodifiable Map<Integer, Selection> selections;

    /// Creates a decoder with frozen archive evidence and no retained input buffers.
    private ZipAutoMetadataDecoder(Map<Integer, Selection> selections) {
        this.selections = Map.copyOf(selections);
    }

    /// Prepares the built-in decoder from a bounded central-directory prefix; custom decoders are unchanged.
    /// The supplied buffer's contents, position, limit, and byte order are not changed or retained.
    static ArchiveMetadataDecoder prepare(ArchiveMetadataDecoder configured, ByteBuffer centralDirectory)
            throws IOException {
        if (configured != DEFAULT) {
            return configured;
        }
        ByteBuffer buffer = centralDirectory.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        HashMap<Integer, Evidence> evidence = new HashMap<>();
        int samples = 0;
        int copied = 0;
        for (int record = 0; buffer.hasRemaining() && record < MAX_RECORDS
                && samples < MAX_SAMPLES && copied < MAX_SAMPLE_BYTES; record++) {
            int offset = buffer.position();
            if (buffer.remaining() < 46 || buffer.getInt(offset) != ZipConstants.CENTRAL_DIRECTORY_HEADER_SIGNATURE) {
                throw new IOException("Invalid ZIP central directory header");
            }
            int flags = Short.toUnsignedInt(buffer.getShort(offset + 8));
            int nameLength = Short.toUnsignedInt(buffer.getShort(offset + 28));
            int extraLength = Short.toUnsignedInt(buffer.getShort(offset + 30));
            int commentLength = Short.toUnsignedInt(buffer.getShort(offset + 32));
            int variableLength = nameLength + extraLength + commentLength;
            if (variableLength > buffer.remaining() - 46) {
                throw new IOException("Invalid ZIP central directory variable data length");
            }
            int start = offset + 46;
            buffer.position(start + variableLength);
            if ((flags & ZipEntryNameDecoder.UTF_8_FLAG) != 0) {
                continue;
            }
            int creator = Short.toUnsignedInt(buffer.getShort(offset + 4)) >>> 8;
            for (int kind = 0; kind < 2 && samples < MAX_SAMPLES; kind++) {
                int length = kind == 0 ? nameLength : commentLength;
                int fieldStart = kind == 0 ? start : start + nameLength + extraLength;
                if (length == 0 || length > MAX_FIELD_BYTES || isAscii(buffer.slice(fieldStart, length))) {
                    continue;
                }
                int cost = length + extraLength;
                if (cost > MAX_SAMPLE_BYTES - copied) {
                    continue;
                }
                samples++;
                copied += cost;
                byte[] raw = new byte[length];
                buffer.get(fieldStart, raw);
                byte[] extra = new byte[extraLength];
                buffer.get(start + nameLength, extra);
                @Nullable String unicode = ZipEntryNameDecoder.decodeUnicodeExtraField(raw, extra, kind == 0
                        ? ZipEntryNameDecoder.UNICODE_PATH_EXTRA_FIELD_ID
                        : ZipEntryNameDecoder.UNICODE_COMMENT_EXTRA_FIELD_ID);
                evidence.computeIfAbsent(creator * 2 + kind, ignored -> new Evidence())
                        .accept(raw, unicode, kind == 0);
            }
        }
        HashMap<Integer, Selection> selections = new HashMap<>();
        evidence.forEach((key, value) -> selections.put(key, value.selection()));
        return selections.isEmpty() ? DEFAULT : new ZipAutoMetadataDecoder(selections);
    }

    @Override
    public String decode(Context context) {
        return decode(context.bytes(), context.metadataKind(), context.creatorSystem());
    }

    @Override
    public String decode(@UnmodifiableView ByteBuffer bytes) {
        return decode(bytes, MetadataKind.UNKNOWN, UNKNOWN_HEADER_VALUE);
    }

    /// Decodes reader-owned bytes without allocating a public context or unused extra-field view.
    String decode(byte[] bytes, MetadataKind kind, int versionMadeBy) {
        return decode(ByteBuffer.wrap(bytes), kind,
                versionMadeBy == UNKNOWN_HEADER_VALUE ? UNKNOWN_HEADER_VALUE : versionMadeBy >>> 8);
    }

    /// Decodes a complete field using only frozen evidence and local temporary state.
    private String decode(ByteBuffer bytes, MetadataKind kind, int creatorSystem) {
        if (isAscii(bytes)) {
            return StandardCharsets.ISO_8859_1.decode(bytes.duplicate()).toString();
        }
        @Nullable Selection selection = selections.get(creatorSystem * 2 + (kind == MetadataKind.ENTRY_COMMENT ? 1 : 0));
        if (selection != null && selection.anchorMask() != 0) {
            @Nullable String anchored = decodeAgreement(bytes, selection.anchorMask());
            if (anchored != null) {
                return anchored;
            }
        }
        @Nullable String utf8 = decodeStrict(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return utf8;
        }
        // A strong individual result takes precedence over an archive hint, allowing mixed encodings.
        int candidate = guess(bytes);
        if (candidate == NO_CANDIDATE && selection != null && kind != MetadataKind.ENTRY_COMMENT) {
            long parentMask = selection.parentMask(bytes);
            if (parentMask != 0) {
                @Nullable String parentDecoded = decodeAgreement(bytes, parentMask);
                // Conflicting prefix evidence must not be overruled by an unrelated archive majority.
                return parentDecoded != null ? parentDecoded : CP437.decode(bytes.duplicate()).toString();
            }
        }
        if (candidate == NO_CANDIDATE && selection != null) {
            candidate = selection.candidate();
        }
        if (candidate >= 0) {
            @Nullable String decoded = decodeStrict(bytes, Candidates.VALUES.get(candidate).charset());
            if (decoded != null) {
                return decoded;
            }
        }
        return CP437.decode(bytes.duplicate()).toString();
    }

    /// Returns a common strict decoding only when every Unicode-evidence candidate agrees.
    private static @Nullable String decodeAgreement(ByteBuffer bytes, long mask) {
        @Nullable String result = null;
        for (int index = 0; index < Candidates.VALUES.size(); index++) {
            if ((mask & (1L << index)) == 0) continue;
            @Nullable String decoded = decodeStrict(bytes, Candidates.VALUES.get(index).charset());
            if (decoded == null || result != null && !result.equals(decoded)) return null;
            result = decoded;
        }
        return result;
    }

    /// Returns a high-margin language candidate, or -1 for short, ambiguous, or oversized fields.
    private static int guess(ByteBuffer bytes) {
        if (bytes.remaining() > MAX_FIELD_BYTES) return NO_CANDIDATE;
        int best = NO_CANDIDATE;
        int bestScore = 0;
        int secondScore = 0;
        for (int index = 0; index < Candidates.VALUES.size(); index++) {
            Candidate candidate = Candidates.VALUES.get(index);
            if (candidate.script() == Script.NONE) continue;
            @Nullable String decoded = decodeStrict(bytes, candidate.charset());
            int score = decoded == null ? 0 : score(decoded, candidate.script());
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                best = index;
            } else {
                secondScore = Math.max(secondScore, score);
            }
        }
        return bestScore >= 12 && bestScore - secondScore >= 8 ? best : NO_CANDIDATE;
    }

    /// Scores common characters and script coherence; ASCII contributes no evidence.
    private static int score(String text, Script script) {
        int significant = 0;
        int common = 0;
        int unexpected = 0;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch < 128) continue;
            significant++;
            boolean expected = switch (script) {
                case SIMPLIFIED, TRADITIONAL -> ch >= 0x4e00 && ch <= 0x9fff;
                case JAPANESE -> ch >= 0x3041 && ch <= 0x30fa || ch >= 0x4e00 && ch <= 0x9fff;
                case KOREAN -> ch >= 0xac00 && ch <= 0xd7a3;
                case NONE -> false;
            };
            if (!expected) unexpected++;
            if (script.commonCharacters.indexOf(ch) >= 0
                    || script == Script.JAPANESE && ch >= 0x3041 && ch <= 0x30fa) {
                common++;
            }
        }
        if (common < 3 || common * 3 < significant * 2) return 0;
        return Math.max(0, common * 4 - (significant - common) * 3 - unexpected * 5);
    }

    /// Returns a strict decoding, or null when the bytes are invalid for the charset.
    private static @Nullable String decodeStrict(ByteBuffer bytes, Charset charset) {
        try {
            return charset.newDecoder().decode(bytes.duplicate()).toString();
        } catch (CharacterCodingException ignored) {
            return null;
        }
    }

    /// Tests complete field bytes without changing buffer state.
    private static boolean isAscii(ByteBuffer bytes) {
        for (int index = bytes.position(); index < bytes.limit(); index++) {
            if (bytes.get(index) < 0) return false;
        }
        return true;
    }

    /// Selects the last non-ASCII path component so repeated directories do not multiply archive votes.
    /// Slash cannot be a continuation byte in the legacy multibyte candidates; backslash can and is not split here.
    private static @UnmodifiableView ByteBuffer sampleComponent(ByteBuffer bytes) {
        int start = bytes.position();
        int selectedStart = start;
        int selectedEnd = bytes.limit();
        boolean nonAscii = false;
        for (int index = start; index <= bytes.limit(); index++) {
            if (index == bytes.limit() || bytes.get(index) == '/') {
                if (nonAscii) {
                    selectedStart = start;
                    selectedEnd = index;
                }
                start = index + 1;
                nonAscii = false;
            } else if (bytes.get(index) < 0) {
                nonAscii = true;
            }
        }
        return bytes.slice(selectedStart, selectedEnd - selectedStart).asReadOnlyBuffer();
    }

    /// Holds independent, bounded evidence for one creator system and one metadata kind.
    @NotNullByDefault
    private static final class Evidence {
        /// Distinct sample contents; the arrays are private copies, not caller storage.
        private final HashSet<ByteBuffer> samples = new HashSet<>();

        /// Counts decisive votes rather than multiplying scores from repeated strings.
        private final int[] votes = new int[Candidates.VALUES.size()];

        /// Candidate masks for raw parent prefixes in privately copied samples.
        private final HashMap<@UnmodifiableView ByteBuffer, Long> parents = new HashMap<>();

        /// The candidates agreeing with all usable Unicode counterparts, or zero after a conflict.
        private long anchorMask = (1L << Candidates.VALUES.size()) - 1;

        /// Whether at least one non-ASCII Unicode counterpart was observed.
        private boolean anchored;

        /// Adds a complete field, keeping Unicode contradictions even for duplicate raw names.
        private void accept(byte[] raw, @Nullable String unicode, boolean path) {
            ByteBuffer bytes = ByteBuffer.wrap(raw);
            if (unicode != null) {
                long matching = 0;
                for (int index = 0; index < Candidates.VALUES.size(); index++) {
                    if (unicode.equals(decodeStrict(bytes, Candidates.VALUES.get(index).charset()))) {
                        matching |= 1L << index;
                    }
                }
                anchored = true;
                anchorMask &= matching;
                if (path) acceptParents(bytes, matching);
                return;
            }
            // UTF-8 entries must not vote for a legacy encoding merely because their bytes also fit it.
            if (decodeStrict(bytes, StandardCharsets.UTF_8) != null) return;
            if (path) {
                int candidate = guess(bytes);
                if (candidate >= 0) acceptParents(bytes, 1L << candidate);
            }
            if (path) bytes = sampleComponent(bytes);
            if (samples.add(bytes.asReadOnlyBuffer())) {
                int candidate = guess(bytes);
                if (candidate >= 0) votes[candidate]++;
            }
        }

        /// Records parent evidence without allowing duplicate samples to increase its weight.
        private void acceptParents(ByteBuffer bytes, long mask) {
            if (mask == 0) return;
            boolean nonAscii = false;
            int count = 0;
            for (int index = bytes.position(); index < bytes.limit() && count < MAX_PARENT_PREFIXES; index++) {
                byte value = bytes.get(index);
                if (value < 0) nonAscii = true;
                if (value == '/' && nonAscii) {
                    ByteBuffer prefix = bytes.slice(bytes.position(), index - bytes.position() + 1).asReadOnlyBuffer();
                    parents.merge(prefix, mask, (first, second) -> first | second);
                    count++;
                }
            }
        }

        /// Freezes evidence; archive hints require agreement from multiple distinct names.
        private Selection selection() {
            int candidate = NO_CANDIDATE;
            for (int index = 0; index < votes.length; index++) {
                if (votes[index] >= 2 && votes[index] * 4 >= samples.size() * 3) {
                    candidate = index;
                    break;
                }
            }
            return new Selection(anchored ? anchorMask : 0, candidate, Map.copyOf(parents));
        }
    }

    /// Stores fixed evidence using only privately owned sample storage.
    /// @param anchorMask candidate bits consistent with Unicode counterparts, or zero
    /// @param candidate the archive heuristic candidate, or -1
    /// @param parents immutable raw parent-prefix views and their candidate masks
    @NotNullByDefault
    private record Selection(long anchorMask, int candidate,
                             @Unmodifiable Map<@UnmodifiableView ByteBuffer, Long> parents) {
        /// Returns the longest sampled parent prefix's candidates without retaining the lookup buffer.
        private long parentMask(ByteBuffer bytes) {
            if (parents.isEmpty() || bytes.remaining() > MAX_FIELD_BYTES) return 0;
            int count = 0;
            for (int index = bytes.limit() - 1; index >= bytes.position() && count < MAX_PARENT_PREFIXES; index--) {
                if (bytes.get(index) == '/') {
                    long mask = parents.getOrDefault(bytes.slice(bytes.position(), index - bytes.position() + 1), 0L);
                    if (mask != 0) return mask;
                    count++;
                }
            }
            return 0;
        }
    }

    /// Associates a charset with a conservative script heuristic or evidence-only use.
    /// @param charset the strict JDK decoder
    /// @param script the heuristic character set
    @NotNullByDefault
    private record Candidate(Charset charset, Script script) {
    }

    /// Loads optional JDK charsets only when non-ASCII detection requires them.
    @NotNullByDefault
    private static final class Candidates {
        /// The available legacy candidates and UTF-8 for Unicode counterpart comparisons.
        private static final @Unmodifiable List<Candidate> VALUES = create();

        /// Builds a stable candidate order without requiring the jdk.charsets module.
        private static @Unmodifiable List<Candidate> create() {
            ArrayList<Candidate> result = new ArrayList<>();
            add(result, "GB18030", Script.SIMPLIFIED);
            add(result, "Big5", Script.TRADITIONAL);
            add(result, "windows-31j", Script.JAPANESE);
            add(result, "EUC-JP", Script.JAPANESE);
            add(result, "x-windows-949", Script.KOREAN);
            for (String name : List.of("IBM437", "IBM850", "IBM866", "windows-1251", "windows-1252", "KOI8-R", "UTF-8")) {
                add(result, name, Script.NONE);
            }
            return List.copyOf(result);
        }

        /// Adds a candidate only when the runtime supplies its charset.
        private static void add(ArrayList<Candidate> result, String name, Script script) {
            if (Charset.isSupported(name)) result.add(new Candidate(Charset.forName(name), script));
        }
    }

    /// Supplies small lexical hints, not a general language classifier or an encoding probability.
    @NotNullByDefault
    private enum Script {
        /// Common simplified Chinese characters used as positive evidence.
        SIMPLIFIED("的了一是我不在人们有来他这上着个地到大里说去子得也和那要下看天时过出小么起你都好把开还用生对自作年就为能动同工面事行方经头无日文文件目录资料图片照片视频音乐说明测试下载安装软件程序数据备份报告学习工作新旧版本中文简体压缩包项目设计系统用户管理服务网络信息记录世界中国北京上海广州深圳"),
        /// Common traditional Chinese characters used as positive evidence.
        TRADITIONAL("的了一是我不在人們有來他這上著個地到大裡說去子得也和那要下看天時過出小麼起你都好把開還用生對自作年就為能動同工面事行方經頭無日文文件目錄資料圖片照片視頻音樂說明測試下載安裝軟體程式數據備份報告學習工作新舊版本中文繁體壓縮包項目設計系統用戶管理服務網絡資訊記錄世界中國臺灣台灣香港"),
        /// Common Japanese ideographs; full-width kana are handled by the scorer.
        JAPANESE("日本語中文年月日時間人大小新古上下左右前後中学校会社仕事名前写真画像音楽動画説明資料文書保存設定情報一覧確認変更登録削除作成表示内容利用方法世界東京大阪京都"),
        /// Common Korean syllables used as positive evidence.
        KOREAN("가나다라마바사아자차카타파하한글국어문서파일목록자료사진그림음악동영상설명시험테스트다운로드설치프로그램데이터백업보고학습작업새버전압축사용관리정시스네트워크기록세계서울대한민국"),
        /// Charsets selected only by explicit Unicode counterparts, never by language guessing.
        NONE("");

        /// The positive lexical hints for this script.
        private final String commonCharacters;

        /// Creates a script's lexical hints.
        Script(String commonCharacters) {
            this.commonCharacters = commonCharacters;
        }
    }
}
