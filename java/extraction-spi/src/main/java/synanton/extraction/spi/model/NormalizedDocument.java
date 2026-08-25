package synanton.extraction.spi.model;

import java.util.List;
import java.util.Map;

/**
 * Normalized document payload produced by a modality adapter.
 *
 * <p>{@code flattenedText} MUST be derived from {@code elements}, never by reparsing the source
 * (rule §67.17).
 *
 * @param mediaType     the IANA media type of the source content
 * @param metadata      document-level key-value metadata (author, title, language, etc.)
 * @param elements      all structural elements in reading order
 * @param flattenedText concatenated plain text derived from {@code elements}
 */
public record NormalizedDocument(
        String mediaType,
        Map<String, String> metadata,
        List<NormalizedElement> elements,
        String flattenedText) {

    /**
     * Compact constructor that defensively copies mutable collections.
     */
    public NormalizedDocument {
        metadata = Map.copyOf(metadata);
        elements = List.copyOf(elements);
    }
}
