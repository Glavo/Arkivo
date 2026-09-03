// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.PathMatcher;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/// Creates reusable path matchers for archive file systems.
@NotNullByDefault
public final class ArkivoPathMatchers {
    /// Creates a path matcher that treats `/` as the path separator.
    ///
    /// @param syntaxAndPattern a {@code glob:pattern} or {@code regex:pattern} specification
    /// @return a matcher applied to each path's string representation
    /// @throws IllegalArgumentException if the specification does not contain a nonempty syntax name
    /// @throws PatternSyntaxException if the glob or regular-expression pattern is invalid
    /// @throws UnsupportedOperationException if the named syntax is not supported
    public static PathMatcher create(String syntaxAndPattern) {
        return create(syntaxAndPattern, '/');
    }

    /// Creates a path matcher with the given path separator.
    ///
    /// @param syntaxAndPattern a {@code glob:pattern} or {@code regex:pattern} specification
    /// @param separator the path separator excluded by single-segment glob wildcards
    /// @return a matcher applied to each path's string representation
    /// @throws IllegalArgumentException if the specification does not contain a nonempty syntax name
    /// @throws PatternSyntaxException if the glob or regular-expression pattern is invalid
    /// @throws UnsupportedOperationException if the named syntax is not supported
    public static PathMatcher create(String syntaxAndPattern, char separator) {
        Objects.requireNonNull(syntaxAndPattern, "syntaxAndPattern");
        int syntaxSeparator = syntaxAndPattern.indexOf(':');
        if (syntaxSeparator <= 0) {
            throw new IllegalArgumentException("Path matcher syntax must be syntax:pattern");
        }

        String syntax = syntaxAndPattern.substring(0, syntaxSeparator);
        String pattern = syntaxAndPattern.substring(syntaxSeparator + 1);
        Pattern compiledPattern = switch (syntax) {
            case "glob" -> Pattern.compile(globToRegex(pattern, separator));
            case "regex" -> Pattern.compile(pattern);
            default -> throw new UnsupportedOperationException("Unsupported path matcher syntax: " + syntax);
        };
        return path -> compiledPattern.matcher(path.toString()).matches();
    }

    /// Creates no path matcher instances.
    private ArkivoPathMatchers() {
    }

    /// Converts a glob pattern to a regular expression that treats `separator` as the only path separator.
    private static String globToRegex(String glob, char separator) {
        StringBuilder regex = new StringBuilder(glob.length() * 2);
        boolean inGroup = false;
        for (int index = 0; index < glob.length(); index++) {
            char ch = glob.charAt(index);
            switch (ch) {
                case '*' -> {
                    if (index + 1 < glob.length() && glob.charAt(index + 1) == '*') {
                        regex.append(".*");
                        index++;
                    } else {
                        appendNotSeparator(regex, separator);
                        regex.append('*');
                    }
                }
                case '?' -> appendNotSeparator(regex, separator);
                case '[' -> index = appendGlobCharacterClass(glob, index, regex, separator);
                case '{' -> {
                    if (inGroup) {
                        throw new PatternSyntaxException("Cannot nest glob groups", glob, index);
                    }
                    regex.append("(?:(?:");
                    inGroup = true;
                }
                case '}' -> {
                    if (inGroup) {
                        regex.append("))");
                        inGroup = false;
                    } else {
                        appendRegexLiteral(regex, ch);
                    }
                }
                case ',' -> {
                    if (inGroup) {
                        regex.append(")|(?:");
                    } else {
                        appendRegexLiteral(regex, ch);
                    }
                }
                case '\\' -> {
                    if (index + 1 >= glob.length()) {
                        throw new PatternSyntaxException("No character to escape", glob, index);
                    }
                    appendRegexLiteral(regex, glob.charAt(++index));
                }
                default -> appendRegexLiteral(regex, ch);
            }
        }

        if (inGroup) {
            throw new PatternSyntaxException("Unclosed glob group", glob, glob.length() - 1);
        }
        return regex.toString();
    }

    /// Appends a character class that excludes the path separator.
    private static void appendNotSeparator(StringBuilder regex, char separator) {
        regex.append("[^");
        appendCharacterClassLiteral(regex, separator);
        regex.append(']');
    }

    /// Appends a separator-excluding glob character class and returns the final consumed index.
    private static int appendGlobCharacterClass(
            String glob,
            int startIndex,
            StringBuilder regex,
            char separator
    ) {
        int index = startIndex + 1;
        if (index >= glob.length()) {
            throw new PatternSyntaxException("Unclosed glob character class", glob, startIndex);
        }

        regex.append("[[^");
        appendCharacterClassLiteral(regex, separator);
        regex.append("]&&[");

        boolean hasMember = false;
        boolean hasRangeStart = false;
        char rangeStart = 0;
        if (glob.charAt(index) == '!') {
            regex.append('^');
            index++;
        } else if (glob.charAt(index) == '^') {
            regex.append("\\^");
            index++;
            hasMember = true;
            hasRangeStart = true;
            rangeStart = '^';
        }
        if (index < glob.length() && glob.charAt(index) == '-') {
            regex.append("\\-");
            index++;
            hasMember = true;
            hasRangeStart = false;
        }

        for (; index < glob.length(); index++) {
            char ch = glob.charAt(index);
            if (ch == ']') {
                if (!hasMember) {
                    throw new PatternSyntaxException("Empty glob character class", glob, index);
                }
                regex.append("]]");
                return index;
            }
            if (ch == separator) {
                throw new PatternSyntaxException("Path separator in glob character class", glob, index);
            }
            if (ch == '-') {
                if (!hasRangeStart) {
                    throw new PatternSyntaxException("Invalid glob character range", glob, index);
                }
                if (index + 1 >= glob.length()) {
                    throw new PatternSyntaxException("Unclosed glob character class", glob, startIndex);
                }
                char rangeEnd = glob.charAt(index + 1);
                if (rangeEnd == ']') {
                    regex.append("\\-");
                    hasRangeStart = false;
                    continue;
                }
                if (rangeEnd == separator) {
                    throw new PatternSyntaxException(
                            "Path separator in glob character class",
                            glob,
                            index + 1
                    );
                }
                if (rangeEnd == '-' || rangeEnd < rangeStart) {
                    throw new PatternSyntaxException("Invalid glob character range", glob, index);
                }
                regex.append('-');
                appendCharacterClassLiteral(regex, rangeEnd);
                index++;
                hasMember = true;
                hasRangeStart = false;
                continue;
            }

            appendCharacterClassLiteral(regex, ch);
            hasMember = true;
            hasRangeStart = true;
            rangeStart = ch;
        }
        throw new PatternSyntaxException("Unclosed glob character class", glob, startIndex);
    }

    /// Appends one regular expression literal character.
    private static void appendRegexLiteral(StringBuilder regex, char ch) {
        if ("\\.[]{}()+-^$|*?".indexOf(ch) >= 0) {
            regex.append('\\');
        }
        regex.append(ch);
    }

    /// Appends one regular expression character class literal.
    private static void appendCharacterClassLiteral(StringBuilder regex, char ch) {
        if ("\\[]^-&".indexOf(ch) >= 0) {
            regex.append('\\');
        }
        regex.append(ch);
    }
}
