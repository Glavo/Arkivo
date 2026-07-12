// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests shared archive path matcher behavior.
@NotNullByDefault
public final class ArkivoPathMatchersTest {
    /// Verifies that glob matchers use the configured separator.
    @Test
    public void globMatcherUsesConfiguredSeparator() {
        Path unixPath = path("/dir/hello.txt");
        Path customPath = path(":dir:hello.txt");

        assertEquals(true, ArkivoPathMatchers.create("glob:**/*.txt", '/').matches(unixPath));
        assertEquals(false, ArkivoPathMatchers.create("glob:**/*.bin", '/').matches(unixPath));
        assertEquals(true, ArkivoPathMatchers.create("glob:**:*.txt", ':').matches(customPath));
    }

    /// Verifies that regex matchers are forwarded unchanged.
    @Test
    public void regexMatcher() {
        assertEquals(true, ArkivoPathMatchers.create("regex:.*/hello\\.txt").matches(path("/dir/hello.txt")));
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
