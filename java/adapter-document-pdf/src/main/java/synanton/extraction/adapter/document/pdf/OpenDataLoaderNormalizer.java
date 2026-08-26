package synanton.extraction.adapter.document.pdf;

import com.fasterxml.jackson.databind.JsonNode;
import synanton.extraction.spi.model.ContentOrigin;
import synanton.extraction.spi.model.ElementBounds;
import synanton.extraction.spi.model.ElementType;
import synanton.extraction.spi.model.NormalizedDocument;
import synanton.extraction.spi.model.NormalizedElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts an {@link OdlResponse} from the OpenDataLoader PDF service into a
 * {@link NormalizedDocument} using the Synanton domain model.
 *
 * <p>The mapping is:
 * <ul>
 *   <li>OpenDataLoader {@code heading} &rarr; {@link ElementType#HEADING}</li>
 *   <li>OpenDataLoader {@code paragraph} &rarr; {@link ElementType#PARAGRAPH}</li>
 *   <li>OpenDataLoader {@code table} &rarr; {@link ElementType#TABLE}</li>
 *   <li>OpenDataLoader {@code picture} / {@code image} &rarr; {@link ElementType#IMAGE}</li>
 *   <li>OpenDataLoader {@code formula} &rarr; {@link ElementType#FORMULA}</li>
 *   <li>OpenDataLoader {@code list} &rarr; {@link ElementType#LIST}</li>
 *   <li>OpenDataLoader {@code caption} &rarr; {@link ElementType#CAPTION}</li>
 * </ul>
 */
public class OpenDataLoaderNormalizer {

    /**
     * Normalizes an {@link OdlResponse} into a {@link NormalizedDocument}.
     *
     * @param response  the raw OpenDataLoader response
     * @param mediaType the IANA media type of the source (e.g. {@code application/pdf})
     * @return the normalized document
     */
    public NormalizedDocument normalize(OdlResponse response, String mediaType) {
        Map<String, String> metadata = buildMetadata(response);
        List<NormalizedElement> elements = new ArrayList<>();

        List<OdlElement> kids = response.getKids() != null ? response.getKids() : List.of();
        for (int index = 0; index < kids.size(); index++) {
            OdlElement kid = kids.get(index);
            NormalizedElement element = mapElement(kid, index);
            if (element != null) {
                elements.add(element);
            }
        }

        String flattenedText = buildFlattenedText(elements);
        return new NormalizedDocument(mediaType, metadata, elements, flattenedText);
    }

    private Map<String, String> buildMetadata(OdlResponse response) {
        Map<String, String> meta = new LinkedHashMap<>();
        if (response.getTitle() != null && !response.getTitle().isBlank()) {
            meta.put("title", response.getTitle());
        }
        if (response.getAuthor() != null && !response.getAuthor().isBlank()) {
            meta.put("author", response.getAuthor());
        }
        if (response.getNumberOfPages() > 0) {
            meta.put("pageCount", String.valueOf(response.getNumberOfPages()));
        }
        if (response.getCreationDate() != null) {
            meta.put("creationDate", response.getCreationDate());
        }
        return meta;
    }

    private ContentOrigin mapOrigin(OdlElement kid) {
        if (kid.getContentOrigin() != null && kid.getContentOrigin().equalsIgnoreCase("ocr")) {
            return ContentOrigin.OCR;
        }
        return ContentOrigin.EMBEDDED_TEXT;
    }

    private NormalizedElement mapElement(OdlElement kid, int index) {
        String elementId = "p" + kid.getPageNumber() + "-e" + (index + 1);
        ElementBounds bounds = mapBounds(kid);
        ContentOrigin origin = mapOrigin(kid);

        return switch (kid.getType() != null ? kid.getType().toLowerCase() : "") {
            case "heading" -> new NormalizedElement(
                    elementId, ElementType.HEADING, bounds,
                    extractText(kid.getContent()), origin,
                    kid.getHeadingLevel() > 0 ? kid.getHeadingLevel() : 1,
                    List.of(), Map.of(), null);

            case "paragraph", "text" -> new NormalizedElement(
                    elementId, ElementType.PARAGRAPH, bounds,
                    extractText(kid.getContent()), origin,
                    0, List.of(), Map.of(), null);

            case "table" -> mapTable(kid, elementId, bounds, origin);

            case "picture", "image", "figure" -> new NormalizedElement(
                    elementId, ElementType.IMAGE, bounds,
                    null, origin,
                    0, List.of(), Map.of(),
                    kid.getDescription());

            case "formula", "equation" -> new NormalizedElement(
                    elementId, ElementType.FORMULA, bounds,
                    null, origin,
                    0, List.of(), Map.of(),
                    extractText(kid.getContent()));

            case "list" -> new NormalizedElement(
                    elementId, ElementType.LIST, bounds,
                    extractText(kid.getContent()), origin,
                    0, List.of(), Map.of(), null);

            case "caption" -> new NormalizedElement(
                    elementId, ElementType.CAPTION, bounds,
                    extractText(kid.getContent()), origin,
                    0, List.of(), Map.of(), null);

            default -> {
                String text = extractText(kid.getContent());
                if (text == null || text.isBlank()) yield null;
                yield new NormalizedElement(
                        elementId, ElementType.PARAGRAPH, bounds,
                        text, origin,
                        0, List.of(), Map.of(), null);
            }
        };
    }

    private NormalizedElement mapTable(OdlElement kid, String elementId, ElementBounds bounds,
                                       ContentOrigin origin) {
        JsonNode contentNode = kid.getContent();
        if (contentNode == null || contentNode.isNull()) {
            return new NormalizedElement(elementId, ElementType.TABLE, bounds, null,
                    ContentOrigin.EMBEDDED_TEXT, 0, List.of(), Map.of(), null);
        }

        StringBuilder tableText = new StringBuilder();
        if (contentNode.has("headers")) {
            List<String> headers = new ArrayList<>();
            contentNode.get("headers").forEach(h -> headers.add(h.asText()));
            tableText.append(String.join(" | ", headers));
        }
        if (contentNode.has("rows")) {
            contentNode.get("rows").forEach(row -> {
                List<String> cells = new ArrayList<>();
                row.forEach(cell -> cells.add(cell.asText()));
                tableText.append("\n").append(String.join(" | ", cells));
            });
        }

        return new NormalizedElement(elementId, ElementType.TABLE, bounds,
                tableText.toString().trim(), origin,
                0, List.of(), Map.of(), null);
    }

    private ElementBounds mapBounds(OdlElement kid) {
        double[] bbox = kid.getBoundingBox();
        if (bbox == null || bbox.length < 4) {
            return ElementBounds.absent();
        }
        return new ElementBounds(kid.getPageNumber(), bbox[0], bbox[1], bbox[2], bbox[3]);
    }

    private String extractText(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) return null;
        if (contentNode.isTextual()) return contentNode.asText();
        if (contentNode.isObject() || contentNode.isArray()) return contentNode.toString();
        return contentNode.asText();
    }

    private String buildFlattenedText(List<NormalizedElement> elements) {
        return elements.stream()
                .map(el -> {
                    if (el.text() != null && !el.text().isBlank()) return el.text();
                    if (el.alternateRepresentation() != null) return el.alternateRepresentation();
                    return null;
                })
                .filter(t -> t != null && !t.isBlank())
                .reduce("", (a, b) -> a.isBlank() ? b : a + "\n" + b);
    }
}
