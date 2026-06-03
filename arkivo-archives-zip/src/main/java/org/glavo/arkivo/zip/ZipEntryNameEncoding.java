// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/// Defines how ZIP entry names are decoded when no authoritative Unicode name is available.
@NotNullByDefault
public final class ZipEntryNameEncoding {
    /// The CP437 charset required by the original ZIP entry name encoding rules.
    private static final Charset CP437 = Charset.forName("IBM437");

    /// The default automatic charset candidate list.
    private static final @Unmodifiable List<Charset> DEFAULT_AUTO_CANDIDATES = List.of(
            StandardCharsets.UTF_8,
            Charset.forName("GB18030"),
            Charset.forName("Shift_JIS"),
            Charset.forName("MS932"),
            Charset.forName("EUC-KR"),
            Charset.forName("Big5"),
            Charset.forName("windows-1252"),
            CP437
    );

    /// The standard ZIP entry name encoding policy.
    private static final ZipEntryNameEncoding STANDARD =
            new ZipEntryNameEncoding(ZipEntryNameEncodingMode.STANDARD, null, List.of(CP437));

    /// The default automatic ZIP entry name encoding policy.
    private static final ZipEntryNameEncoding AUTO =
            new ZipEntryNameEncoding(ZipEntryNameEncodingMode.AUTO, null, DEFAULT_AUTO_CANDIDATES);

    /// The fallback decoding mode used when no authoritative Unicode name is available.
    private final ZipEntryNameEncodingMode mode;

    /// The explicit fallback charset used by `CHARSET` mode.
    private final @Nullable Charset charset;

    /// The automatic fallback charset candidates used by `AUTO` mode.
    private final @Unmodifiable List<Charset> candidates;

    /// Creates a ZIP entry name encoding policy.
    private ZipEntryNameEncoding(
            ZipEntryNameEncodingMode mode,
            @Nullable Charset charset,
            List<Charset> candidates
    ) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.charset = charset;
        this.candidates = List.copyOf(candidates);
    }

    /// Returns the ZIP standard entry name encoding policy.
    public static ZipEntryNameEncoding standard() {
        return STANDARD;
    }

    /// Returns an entry name encoding policy that uses the given fallback charset.
    public static ZipEntryNameEncoding charset(Charset charset) {
        return new ZipEntryNameEncoding(
                ZipEntryNameEncodingMode.CHARSET,
                Objects.requireNonNull(charset, "charset"),
                List.of(charset)
        );
    }

    /// Returns the default automatic entry name encoding policy.
    public static ZipEntryNameEncoding auto() {
        return AUTO;
    }

    /// Returns an automatic entry name encoding policy with the given fallback charset candidates.
    public static ZipEntryNameEncoding auto(List<Charset> candidates) {
        List<Charset> copiedCandidates = List.copyOf(candidates);
        if (copiedCandidates.isEmpty()) {
            throw new IllegalArgumentException("candidates must not be empty");
        }
        return new ZipEntryNameEncoding(ZipEntryNameEncodingMode.AUTO, null, copiedCandidates);
    }

    /// Parses a ZIP entry name encoding policy string.
    public static ZipEntryNameEncoding parse(String value) {
        String normalizedValue = value.trim();
        if (normalizedValue.isEmpty()) {
            throw new IllegalArgumentException("entryNameEncoding must not be empty");
        }

        String lowerCaseValue = normalizedValue.toLowerCase(Locale.ROOT);
        if ("standard".equals(lowerCaseValue)) {
            return standard();
        }
        if ("auto".equals(lowerCaseValue)) {
            return auto();
        }
        if (lowerCaseValue.startsWith("auto:")) {
            return auto(parseCharsetList(normalizedValue.substring("auto:".length())));
        }
        return charset(Charset.forName(normalizedValue));
    }

    /// Returns the CP437 charset required by the original ZIP entry name encoding rules.
    public static Charset cp437() {
        return CP437;
    }

    /// Returns the default automatic fallback charset candidates.
    public static @Unmodifiable List<Charset> defaultAutoCandidates() {
        return DEFAULT_AUTO_CANDIDATES;
    }

    /// Returns the fallback decoding mode used when no authoritative Unicode name is available.
    public ZipEntryNameEncodingMode mode() {
        return mode;
    }

    /// Returns the explicit fallback charset used by `CHARSET` mode, or `null` for other modes.
    public @Nullable Charset charset() {
        return charset;
    }

    /// Returns the automatic fallback charset candidates used by `AUTO` mode.
    public @Unmodifiable List<Charset> candidates() {
        return candidates;
    }

    /// Compares this encoding policy with another object.
    @Override
    public boolean equals(@Nullable Object object) {
        return object instanceof ZipEntryNameEncoding that
                && mode == that.mode
                && Objects.equals(charset, that.charset)
                && candidates.equals(that.candidates);
    }

    /// Returns the hash code for this encoding policy.
    @Override
    public int hashCode() {
        return Objects.hash(mode, charset, candidates);
    }

    /// Returns the stable display text for this encoding policy.
    @Override
    public String toString() {
        return switch (mode) {
            case STANDARD -> "standard";
            case CHARSET -> charset.name();
            case AUTO -> candidates.equals(DEFAULT_AUTO_CANDIDATES) ? "auto" : "auto:" + joinCharsetNames(candidates);
        };
    }

    /// Parses a comma-separated charset name list.
    private static @Unmodifiable List<Charset> parseCharsetList(String value) {
        ArrayList<Charset> charsets = new ArrayList<>();
        for (String charsetName : value.split(",")) {
            String trimmedName = charsetName.trim();
            if (!trimmedName.isEmpty()) {
                charsets.add(Charset.forName(trimmedName));
            }
        }
        if (charsets.isEmpty()) {
            throw new IllegalArgumentException("auto entryNameEncoding candidates must not be empty");
        }
        return List.copyOf(charsets);
    }

    /// Joins charset names into the string representation used by `AUTO` mode.
    private static String joinCharsetNames(List<Charset> charsets) {
        StringBuilder builder = new StringBuilder();
        for (Charset charset : charsets) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(charset.name());
        }
        return builder.toString();
    }
}
