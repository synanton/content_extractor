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
 * Converts an {@link OdlResponse} from the real OpenDataLoader library output into a
 * {@link NormalizedDocument} using the Synanton domain model.
 *
 * <p>The mapping (verified against the real library's output for heading/paragraph;
 * table/list/header/footer/text-block mapped per the upstream {@code schema.json} shape,
 * which our test fixture does not independently exercise — see class notes on
 * {@link OdlElement}):
 * <ul>
 *   <li>{@code heading} &rarr; {@link ElementType#HEADING}</li>
 *   <li>{@code paragraph}, {@code text block} &rarr; {@link ElementType#PARAGRAPH}</li>
 *   <li>{@code table} &rarr; {@link ElementType#TABLE} (rows/cells flattened to text)</li>
 *   <li>{@code image} &rarr; {@link ElementType#IMAGE}</li>
 *   <li>{@code list} &rarr; {@link ElementType#LIST} (list items flattened to text)</li>
 *   <li>{@code caption} &rarr; {@link ElementType#CAPTION}</li>
 *   <li>{@code header}, {@code footer} &rarr; {@link ElementType#PARAGRAPH}</li>
 * </ul>
 * {@code table row} and {@code table cell} are not top-level document elements in the real
 * schema (they nest under {@code table}), so they are handled inside {@link #mapTable} rather
 * than in the top-level switch.
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

            case "paragraph", "text block" -> new NormalizedElement(
                    elementId, ElementType.PARAGRAPH, bounds,
                    textOrNestedKids(kid), origin,
                    0, List.of(), Map.of(), null);

            case "table" -> mapTable(kid, elementId, bounds, origin);

            case "image" -> new NormalizedElement(
                    elementId, ElementType.IMAGE, bounds,
                    null, origin,
                    0, List.of(), Map.of(),
                    kid.getDescription());

            case "list" -> new NormalizedElement(
                    elementId, ElementType.LIST, bounds,
                    mapListText(kid), origin,
                    0, List.of(), Map.of(), null);

            case "caption" -> new NormalizedElement(
                    elementId, ElementType.CAPTION, bounds,
                    extractText(kid.getContent()), origin,
                    0, List.of(), Map.of(), null);

            case "header", "footer" -> new NormalizedElement(
                    elementId, ElementType.PARAGRAPH, bounds,
                    textOrNestedKids(kid), origin,
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

    /**
     * {@code text block}/{@code header}/{@code footer} elements nest their content under
     * {@code kids} (schema.json) rather than carrying it directly in {@code content}.
     */
    private String textOrNestedKids(OdlElement kid) {
        String direct = extractText(kid.getContent());
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        if (kid.getKids() == null || kid.getKids().isEmpty()) {
            return direct;
        }
        return joinNonBlank(kid.getKids().stream().map(child -> extractText(child.getContent())).toList());
    }

    /**
     * Table content nests as {@code table -> rows: [tableRow] -> cells: [tableCell]}
     * (schema.json), not as a {@code content} object — flattened here to pipe-delimited
     * rows since the domain model represents a table element as text, not a grid.
     */
    private NormalizedElement mapTable(OdlElement kid, String elementId, ElementBounds bounds,
                                        ContentOrigin origin) {
        List<OdlElement> rows = kid.getRows();
        if (rows == null || rows.isEmpty()) {
            return new NormalizedElement(elementId, ElementType.TABLE, bounds, null,
                    ContentOrigin.EMBEDDED_TEXT, 0, List.of(), Map.of(), null);
        }

        List<String> rowLines = new ArrayList<>();
        for (OdlElement row : rows) {
            List<OdlElement> cells = row.getCells();
            if (cells == null) {
                continue;
            }
            List<String> cellTexts = cells.stream().map(cell -> extractText(cell.getContent())).toList();
            rowLines.add(String.join(" | ", cellTexts));
        }

        return new NormalizedElement(elementId, ElementType.TABLE, bounds,
                String.join("\n", rowLines), origin,
                0, List.of(), Map.of(), null);
    }

    /**
     * List content nests as {@code list -> list items: [listItem]} (schema.json), each
     * item's text under its own {@code kids}.
     */
    private String mapListText(OdlElement kid) {
        List<OdlElement> items = kid.getListItems();
        if (items == null || items.isEmpty()) {
            return extractText(kid.getContent());
        }
        return joinNonBlank(items.stream().map(this::textOrNestedKids).toList());
    }

    private static String joinNonBlank(List<String> parts) {
        return parts.stream()
                .filter(t -> t != null && !t.isBlank())
                .reduce((a, b) -> a + "\n" + b)
                .orElse(null);
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
