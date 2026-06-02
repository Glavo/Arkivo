/// Provides gzip compression support for Arkivo.
module org.glavo.arkivo.codecs.gzip {
    requires org.glavo.arkivo.base;
    requires static org.jetbrains.annotations;

    exports org.glavo.arkivo.gzip;

    provides org.glavo.arkivo.compress.CompressionCodec with org.glavo.arkivo.gzip.GzipCodec;
}
