package org.synanton.extraction.spi.model;

/**
 * Immutable source identity supplied by the extraction request.
 *
 * @param mediaType     IANA media type of the source
 * @param sourceFormat  source format identifier, e.g. "markdown"
 * @param sourceDigest  immutable source digest, e.g. sha256 hex
 * @param sourceVersion object version, or null/empty when not versioned
 * @param filename      source key/filename, or null/empty when unavailable
 */
public record NormalizedSourceDescriptor(
        String mediaType,
        String sourceFormat,
        String sourceDigest,
        String sourceVersion,
        String filename) {
}
