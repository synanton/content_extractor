package synanton.extraction.spi.model;

import java.util.List;
import java.util.Map;

/**
 * One structural element in reading order.
 *
 * <p>{@code level} is heading depth (1–6) for {@link ElementType#HEADING}, and nesting depth for
 * {@link ElementType#LIST} and {@link ElementType#LIST_ITEM}.
 *
 * <p>{@code alternateRepresentation} holds LaTeX for {@link ElementType#FORMULA} or a VLM
 * description for {@link ElementType#IMAGE}.
 *
 * @param id                      unique element identifier within this document
 * @param type                    the structural type of the element
 * @param bounds                  the bounding box on the source page
 * @param text                    the normalised plain-text content of the element
 * @param contentOrigin           how the element's text content was obtained
 * @param level                   heading depth (1–6) or list nesting depth
 * @param childIds                ordered identifiers of direct child elements
 * @param attributes              arbitrary key-value metadata attached to the element
 * @param alternateRepresentation an alternate string representation (LaTeX, VLM description, etc.)
 */
public record NormalizedElement(
        String id,
        ElementType type,
        ElementBounds bounds,
        String text,
        ContentOrigin contentOrigin,
        int level,
        List<String> childIds,
        Map<String, String> attributes,
        String alternateRepresentation) {

    /**
     * Compact constructor that defensively copies mutable collections.
     */
    public NormalizedElement {
        childIds   = List.copyOf(childIds);
        attributes = Map.copyOf(attributes);
    }
}
