// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.PathMatcher;
import java.util.Objects;
import java.util.regex.Pattern;

/// Creates reusable path matchers for archive file systems.
@NotNullByDefault
public final class ArkivoPathMatchers {
    /// Creates a path matcher that treats `/` as the path separator.
    ///
    /// @param syntaxAndPattern a {@code glob:pattern} or {@code regex:pattern} specification
    /// @return a matcher applied to each path's string representation
    /// @throws IllegalArgumentException if the specification or pattern is invalid
    /// @throws UnsupportedOperationException if the named syntax is not supported
    public static PathMatcher create(String syntaxAndPattern) {
        return create(syntaxAndPattern, '/');
    }

    /// Creates a path matcher with the given path separator.
    ///
    /// @param syntaxAndPattern a {@code glob:pattern} or {@code regex:pattern} specification
    /// @param separator the path separator excluded by single-segment glob wildcards
    /// @return a matcher applied to each path's string representation
    /// @throws IllegalArgumentException if the specification or pattern is invalid
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
        int groupDepth = 0;
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
                case '[' -> index = appendGlobCharacterClass(glob, index, regex);
                case '{' -> {
                    regex.append("(?:");
                    groupDepth++;
                }
                case '}' -> {
                    if (groupDepth > 0) {
                        regex.append(')');
                        groupDepth--;
                    } else {
                        appendRegexLiteral(regex, ch);
                    }
                }
                case ',' -> {
                    if (groupDepth > 0) {
                        regex.append('|');
                    } else {
                        appendRegexLiteral(regex, ch);
                    }
                }
                case '\\' -> {
                    if (index + 1 >= glob.length()) {
                        appendRegexLiteral(regex, ch);
                    } else {
                        appendRegexLiteral(regex, glob.charAt(++index));
                    }
                }
                default -> appendRegexLiteral(regex, ch);
            }
        }

        if (groupDepth != 0) {
            throw new IllegalArgumentException("Unclosed glob group: " + glob);
        }
        return regex.toString();
    }

    /// Appends a character class that excludes the path separator.
    private static void appendNotSeparator(StringBuilder regex, char separator) {
        regex.append("[^");
        appendCharacterClassLiteral(regex, separator);
        regex.append(']');
    }

    /// Appends a glob character class and returns the final consumed index.
    private static int appendGlobCharacterClass(String glob, int startIndex, StringBuilder regex) {
        int index = startIndex + 1;
        if (index >= glob.length()) {
            throw new IllegalArgumentException("Unclosed glob character class: " + glob);
        }

        regex.append('[');
        if (glob.charAt(index) == '!') {
            regex.append('^');
            index++;
        } else if (glob.charAt(index) == '^') {
            regex.append("\\^");
            index++;
        }

        boolean closed = false;
        for (; index < glob.length(); index++) {
            char ch = glob.charAt(index);
            if (ch == ']') {
                regex.append(']');
                closed = true;
                break;
            }
            appendCharacterClassLiteral(regex, ch);
        }

        if (!closed) {
            throw new IllegalArgumentException("Unclosed glob character class: " + glob);
        }
        return index;
    }

    /// Appends one regular expression literal character.
    private static void appendRegexLiteral(StringBuilder regex, char ch) {
        if ("\\.[]{}()+-^$|".indexOf(ch) >= 0) {
            regex.append('\\');
        }
        regex.append(ch);
    }

    /// Appends one regular expression character class literal.
    private static void appendCharacterClassLiteral(StringBuilder regex, char ch) {
        if ("\\[]^-".indexOf(ch) >= 0) {
            regex.append('\\');
        }
        regex.append(ch);
    }
}
