// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// Defines shared ZIP record constants used by ZIP archive implementations.
@NotNullByDefault
final class ZipConstants {
    /// The ZIP local file header signature.
    static final int LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50;

    /// The ZIP central directory file header signature.
    static final int CENTRAL_DIRECTORY_HEADER_SIGNATURE = 0x02014b50;

    /// The ZIP end of central directory signature.
    static final int END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50;

    /// The ZIP64 end of central directory record signature.
    static final int ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06064b50;

    /// The ZIP64 end of central directory locator signature.
    static final int ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE = 0x07064b50;

    /// The ZIP data descriptor signature.
    static final int DATA_DESCRIPTOR_SIGNATURE = 0x08074b50;

    /// The ZIP version needed to extract entries written by the streaming writer.
    static final int VERSION_NEEDED = 20;

    /// The ZIP general purpose flag indicating encryption.
    static final int ENCRYPTED_FLAG = 1;

    /// The ZIP general purpose flag indicating strong encryption.
    static final int STRONG_ENCRYPTION_FLAG = 1 << 6;

    /// The ZIP general purpose flag indicating a data descriptor follows entry data.
    static final int DATA_DESCRIPTOR_FLAG = 1 << 3;

    /// The ZIP general purpose flag indicating UTF-8 entry names.
    static final int UTF8_FLAG = 1 << 11;

    /// The ZIP stored method identifier.
    static final int STORED_METHOD = 0;

    /// The ZIP deflated method identifier.
    static final int DEFLATED_METHOD = 8;

    /// The WinZip AES placeholder method identifier.
    static final int WINZIP_AES_METHOD = 99;

    /// The WinZip AES extra field identifier.
    static final int WINZIP_AES_EXTRA_FIELD_ID = 0x9901;

    /// The ZIP64 extended information extra field identifier.
    static final int ZIP64_EXTENDED_INFORMATION_EXTRA_FIELD_ID = 0x0001;

    /// The DOS date field value for 1980-01-01.
    static final int DOS_DATE_1980_01_01 = 0x21;

    /// The maximum value stored in an unsigned 16-bit ZIP field.
    static final int UINT16_MAX = 0xffff;

    /// The marker value used by ZIP records when a 32-bit unsigned field is stored directly or via ZIP64 metadata.
    static final long UINT32_MAX = 0xffff_ffffL;

    /// Prevents instantiation.
    private ZipConstants() {
    }
}
