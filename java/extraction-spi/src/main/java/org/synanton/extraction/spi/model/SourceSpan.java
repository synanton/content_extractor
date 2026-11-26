package org.synanton.extraction.spi.model;

/**
 * Verified original-source byte span for an element.
 *
 * <p>Coordinates are half-open UTF-8 byte offsets into the original source bytes. This model is
 * intentionally limited to verified original-source spans in the minimum Markdown evidence slice.
 *
 * @param locationId stable location identifier within the payload
 * @param startByte  inclusive UTF-8 byte offset into the original source
 * @param endByte    exclusive UTF-8 byte offset into the original source
 */
public record SourceSpan(String locationId, long startByte, long endByte) {
    public SourceSpan {
        if (locationId == null || locationId.isBlank()) {
            throw new IllegalArgumentException("locationId must not be blank");
        }
        if (startByte < 0 || endByte <= startByte) {
            throw new IllegalArgumentException("source span must be non-empty: " + startByte + ".." + endByte);
        }
    }
}
