// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum;

import org.glavo.arkivo.checksum.internal.BZip2CRC32Algorithm;
import org.glavo.arkivo.checksum.internal.CRC64XZAlgorithm;
import org.glavo.arkivo.checksum.internal.JdkChecksumAlgorithm32;
import org.glavo.arkivo.checksum.internal.MessageDigestChecksumAlgorithm;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;

/// Provides Arkivo's built-in checksum algorithms.
///
/// Catalogue entries are immutable and safe for concurrent use. The catalogue is closed and performs no provider or
/// service discovery.
@NotNullByDefault
public final class Checksums {
    /// The Adler-32 checksum used by zlib streams.
    public static final ChecksumAlgorithm.Width32 ADLER32 =
            new JdkChecksumAlgorithm32("Adler-32", Adler32::new);

    /// The reflected CRC-32/ISO-HDLC checksum used by ZIP, gzip, XZ, and several archive formats.
    public static final ChecksumAlgorithm.Width32 CRC32 =
            new JdkChecksumAlgorithm32("CRC-32/ISO-HDLC", CRC32::new);

    /// The reflected CRC-32C checksum using the Castagnoli polynomial.
    public static final ChecksumAlgorithm.Width32 CRC32C =
            new JdkChecksumAlgorithm32("CRC-32C", CRC32C::new);

    /// The non-reflected CRC-32/BZIP2 checksum used for BZip2 blocks.
    public static final ChecksumAlgorithm.Width32 CRC32_BZIP2 = BZip2CRC32Algorithm.INSTANCE;

    /// The reflected CRC-64/XZ checksum.
    public static final ChecksumAlgorithm.Width64 CRC64_XZ = CRC64XZAlgorithm.INSTANCE;

    /// The zero-seeded XXH32 checksum used by LZ4 frames.
    public static final XXHash32 XXH32 = XXHash32.DEFAULT;

    /// The zero-seeded XXH64 checksum used by Zstandard frames.
    public static final XXHash64 XXH64 = XXHash64.DEFAULT;

    /// The mandatory SHA-256 message digest used as an XZ integrity check.
    public static final ChecksumAlgorithm SHA256 = new MessageDigestChecksumAlgorithm("SHA-256", 32);

    /// Prevents catalogue construction.
    private Checksums() {
    }
}
