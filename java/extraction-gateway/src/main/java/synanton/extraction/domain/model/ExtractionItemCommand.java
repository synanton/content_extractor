package synanton.extraction.domain.model;

import synanton.extraction.spi.model.ExtractionOptions;
import synanton.extraction.spi.model.ObjectRef;

/**
 * Domain command for one artifact within a submit request.
 */
public record ExtractionItemCommand(
        String contentRefId,
        ObjectRef source,
        String mediaType,
        ExtractionOptions options) {
}
