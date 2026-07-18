// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// Interprets POSIX file-type bits stored by CPIO headers.
@NotNullByDefault
final class CPIOPosixSupport {
    /// The POSIX file-type mask.
    static final int TYPE_MASK = 0170000;

    /// The POSIX regular-file type.
    static final int REGULAR_FILE = 0100000;

    /// The POSIX directory type.
    static final int DIRECTORY = 0040000;

    /// The POSIX symbolic-link type.
    static final int SYMBOLIC_LINK = 0120000;

    /// Creates no instances.
    private CPIOPosixSupport() {
    }

    /// Returns whether the mode describes a regular file.
    static boolean isRegularFile(int mode) {
        return (mode & TYPE_MASK) == REGULAR_FILE;
    }

    /// Returns whether the mode describes a directory.
    static boolean isDirectory(int mode) {
        return (mode & TYPE_MASK) == DIRECTORY;
    }

    /// Returns whether the mode describes a symbolic link.
    static boolean isSymbolicLink(int mode) {
        return (mode & TYPE_MASK) == SYMBOLIC_LINK;
    }

    /// Returns whether the mode describes another file type.
    static boolean isOther(int mode) {
        return !isRegularFile(mode) && !isDirectory(mode) && !isSymbolicLink(mode);
    }
}
