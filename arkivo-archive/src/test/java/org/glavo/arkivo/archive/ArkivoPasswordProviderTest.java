// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/// Tests Arkivo password providers.
@NotNullByDefault
public final class ArkivoPasswordProviderTest {
    /// Verifies that fixed byte passwords are defensively copied.
    @Test
    public void fixedBytesAreCopied() throws Exception {
        byte[] password = new byte[]{1, 2, 3};
        ArkivoPasswordProvider provider = ArkivoPasswordProvider.fixed(password);

        password[0] = 9;
        byte[] firstCopy = provider.passwordForArchive();
        byte[] secondCopy = provider.passwordForArchive();
        firstCopy[1] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, secondCopy);
    }

    /// Verifies that character passwords require an explicit charset.
    @Test
    public void fixedCharactersUseCharset() throws Exception {
        ArkivoPasswordProvider provider = ArkivoPasswordProvider.fixed("abc".toCharArray(), StandardCharsets.UTF_8);

        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), provider.passwordForArchive());
    }

    /// Verifies that entry passwords fall back to archive passwords by default.
    @Test
    public void entryPasswordFallsBackToArchivePassword() throws Exception {
        ArkivoPasswordProvider provider = ArkivoPasswordProvider.fixed(new byte[]{1, 2, 3});

        assertArrayEquals(new byte[]{1, 2, 3}, provider.passwordForEntry(Path.of("entry.txt")));
    }
}
