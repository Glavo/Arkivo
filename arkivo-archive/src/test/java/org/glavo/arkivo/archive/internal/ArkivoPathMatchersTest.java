// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests shared archive path matcher behavior.
@NotNullByDefault
public final class ArkivoPathMatchersTest {
    /// Verifies that glob matchers use the configured separator.
    @Test
    public void globMatcherUsesConfiguredSeparator() {
        Path unixPath = path("/dir/hello.txt");
        Path customPath = path(":dir:hello.txt");

        assertTrue(ArkivoPathMatchers.create("glob:**/*.txt", '/').matches(unixPath));
        assertFalse(ArkivoPathMatchers.create("glob:**/*.bin", '/').matches(unixPath));
        assertTrue(ArkivoPathMatchers.create("glob:**:*.txt", ':').matches(customPath));
    }

    /// Verifies that regex matchers are forwarded unchanged.
    @Test
    public void regexMatcher() {
        assertTrue(ArkivoPathMatchers.create("regex:.*/hello\\.txt").matches(path("/dir/hello.txt")));
        assertFalse(ArkivoPathMatchers.create("regex:.*/hello\\.txt").matches(path("/dir/hello.bin")));
    }

    /// Verifies single and recursive glob wildcards follow archive path boundaries.
    @Test
    public void globWildcardsRespectPathBoundaries() {
        PathMatcher segment = ArkivoPathMatchers.create("glob:/root/*.txt");
        assertTrue(segment.matches(path("/root/file.txt")));
        assertFalse(segment.matches(path("/root/sub/file.txt")));

        PathMatcher recursive = ArkivoPathMatchers.create("glob:/root/**/*.txt");
        assertTrue(recursive.matches(path("/root/sub/file.txt")));
        assertTrue(recursive.matches(path("/root/a/b/file.txt")));
        assertFalse(recursive.matches(path("/other/sub/file.txt")));

        PathMatcher singleCharacter = ArkivoPathMatchers.create("glob:/root/file?.txt");
        assertTrue(singleCharacter.matches(path("/root/file1.txt")));
        assertFalse(singleCharacter.matches(path("/root/file12.txt")));
        assertFalse(singleCharacter.matches(path("/root/file/.txt")));
    }

    /// Verifies glob character classes support ranges, negation, and leading literals.
    @Test
    public void globCharacterClassesFollowNioSyntax() {
        PathMatcher range = ArkivoPathMatchers.create("glob:/[a-c][!0-3].txt");
        assertTrue(range.matches(path("/b9.txt")));
        assertFalse(range.matches(path("/d9.txt")));
        assertFalse(range.matches(path("/b2.txt")));
        assertFalse(range.matches(path("/b/.txt")));

        PathMatcher leadingHyphen = ArkivoPathMatchers.create("glob:/[-a].txt");
        assertTrue(leadingHyphen.matches(path("/-.txt")));
        assertTrue(leadingHyphen.matches(path("/a.txt")));

        PathMatcher leadingCaret = ArkivoPathMatchers.create("glob:/[^a].txt");
        assertTrue(leadingCaret.matches(path("/^.txt")));
        assertTrue(leadingCaret.matches(path("/a.txt")));
        assertFalse(leadingCaret.matches(path("/b.txt")));

        PathMatcher classLiterals = ArkivoPathMatchers.create("glob:/[a&&b][-z].txt");
        assertTrue(classLiterals.matches(path("/&-.txt")));
        assertTrue(classLiterals.matches(path("/az.txt")));
    }

    /// Verifies comma-separated glob groups select any complete alternative.
    @Test
    public void globGroupsSelectAlternatives() {
        PathMatcher matcher = ArkivoPathMatchers.create("glob:/{main,test}.{java,class}");

        assertTrue(matcher.matches(path("/main.java")));
        assertTrue(matcher.matches(path("/test.class")));
        assertFalse(matcher.matches(path("/main.txt")));
        assertFalse(matcher.matches(path("/other.java")));
        assertTrue(ArkivoPathMatchers.create("glob:/a,b").matches(path("/a,b")));
        assertTrue(ArkivoPathMatchers.create("glob:/a}b").matches(path("/a}b")));
    }

    /// Verifies backslash escapes glob metacharacters and regular-expression literals.
    @Test
    public void globEscapesMetacharacters() {
        PathMatcher escapedGlob = ArkivoPathMatchers.create("glob:/literal/\\*\\?\\{\\[.txt");
        assertTrue(escapedGlob.matches(path("/literal/*?{[.txt")));
        assertFalse(escapedGlob.matches(path("/literal/anything.txt")));

        PathMatcher regexLiterals = ArkivoPathMatchers.create("glob:/a.b+(c)$|d.txt");
        assertTrue(regexLiterals.matches(path("/a.b+(c)$|d.txt")));
    }

    /// Verifies malformed and unsupported matcher specifications fail deterministically.
    @Test
    public void rejectsInvalidMatcherSyntax() {
        assertThrows(NullPointerException.class, () -> ArkivoPathMatchers.create(null));
        assertThrows(IllegalArgumentException.class, () -> ArkivoPathMatchers.create("glob"));
        assertThrows(IllegalArgumentException.class, () -> ArkivoPathMatchers.create(":pattern"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ArkivoPathMatchers.create("unknown:pattern")
        );
        assertThrows(PatternSyntaxException.class, () -> ArkivoPathMatchers.create("regex:["));
        assertThrows(PatternSyntaxException.class, () -> ArkivoPathMatchers.create("glob:[abc"));
        assertThrows(PatternSyntaxException.class, () -> ArkivoPathMatchers.create("glob:{a,b"));
        assertThrows(PatternSyntaxException.class, () -> ArkivoPathMatchers.create("glob:{a,{b,c}}"));
        assertThrows(PatternSyntaxException.class, () -> ArkivoPathMatchers.create("glob:trailing\\"));
        assertThrows(PatternSyntaxException.class, () -> ArkivoPathMatchers.create("glob:[a/]"));
        assertThrows(PatternSyntaxException.class, () -> ArkivoPathMatchers.create("glob:[]"));
        assertThrows(PatternSyntaxException.class, () -> ArkivoPathMatchers.create("glob:[!]"));
        assertThrows(PatternSyntaxException.class, () -> ArkivoPathMatchers.create("glob:[z-a]"));
        assertThrows(PatternSyntaxException.class, () -> ArkivoPathMatchers.create("glob:[a--c]"));
    }

    /// Returns a minimal path whose string form is fixed.
    private static Path path(String value) {
        return (Path) Proxy.newProxyInstance(
                ArkivoPathMatchersTest.class.getClassLoader(),
                new Class<?>[]{Path.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "toString" -> value;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }
}
