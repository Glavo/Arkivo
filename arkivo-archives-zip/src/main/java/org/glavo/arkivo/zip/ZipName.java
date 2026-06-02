package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoName;
import org.jetbrains.annotations.NotNullByDefault;

/// Represents a normalized item name inside a ZIP archive.
///
/// @param asArkivoName the generic Arkivo name backing this ZIP name
@NotNullByDefault
public record ZipName(
        ArkivoName asArkivoName
) {
    /// Creates a ZIP name from archive path text.
    public static ZipName of(String value) {
        return new ZipName(ArkivoName.of(value));
    }

    /// Returns the normalized slash-separated ZIP item name.
    public String value() {
        return asArkivoName.value();
    }

    /// Returns the normalized slash-separated ZIP item name.
    @Override
    public String toString() {
        return value();
    }
}
