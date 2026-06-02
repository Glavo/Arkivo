package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

/// Classifies the kind of item represented by archive metadata.
@NotNullByDefault
public enum ArkivoItemType {
    /// A regular file item.
    REGULAR_FILE,
    /// A directory item.
    DIRECTORY,
    /// A symbolic link item.
    SYMBOLIC_LINK,
    /// A hard link item.
    HARD_LINK,
    /// A character device item.
    CHARACTER_DEVICE,
    /// A block device item.
    BLOCK_DEVICE,
    /// A FIFO item.
    FIFO,
    /// A socket item.
    SOCKET,
    /// An item with a format-specific or unknown type.
    UNKNOWN
}
