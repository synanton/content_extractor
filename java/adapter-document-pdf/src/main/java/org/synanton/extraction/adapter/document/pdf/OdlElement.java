package org.synanton.extraction.adapter.document.pdf;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One element from the real OpenDataLoader PDF JSON output ({@code kids} array items,
 * recursively — table rows/cells, list items and text blocks are themselves elements).
 *
 * <p>Field set verified against the real {@code org.opendataloader:opendataloader-pdf-core}
 * library (v2.5.8) output for {@code heading}/{@code paragraph} elements, and against the
 * upstream {@code schema.json} for the remaining documented element types (table/list/etc.),
 * which our test fixture does not exercise directly — see {@link OpenDataLoaderNormalizer}.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OdlElement {

    private String type;
    private int id;

    @JsonAlias("page number")
    private int pageNumber;

    @JsonAlias("bounding box")
    private double[] boundingBox;

    @JsonAlias("heading level")
    private int headingLevel;

    // Confirmed on real heading/paragraph output.
    private String level;
    private String font;

    @JsonAlias("font size")
    private double fontSize;

    @JsonAlias("text color")
    private String textColor;

    @JsonAlias("pdfua_tag")
    private String pdfuaTag;

    // Can be a String (text content) or an Object (nested structure) depending on type.
    private JsonNode content;

    // Table (schema.json §table/§tableRow/§tableCell) — nested-kids shape, not yet exercised
    // by a real generated table in our fixture (needs actual vector-drawn table lines to
    // trigger OpenDataLoader's table detection; text-column-alignment alone isn't enough).
    @JsonAlias("number of rows")
    private int numberOfRows;

    @JsonAlias("number of columns")
    private int numberOfColumns;

    private List<OdlElement> rows;
    private List<OdlElement> cells;

    @JsonAlias("row number")
    private int rowNumber;

    @JsonAlias("column number")
    private int columnNumber;

    // textBlock / listItem / header / footer nest their own children under "kids", same as
    // the document root does.
    private List<OdlElement> kids;

    // List (schema.json §list)
    @JsonAlias("numbering style")
    private String numberingStyle;

    @JsonAlias("list items")
    private List<OdlElement> listItems;

    // Optional provenance hint (e.g. "ocr", "embedded") — not confirmed present in real
    // base-extraction output; kept for forward compatibility rather than guessed further.
    @JsonAlias("content origin")
    private String contentOrigin;

    // Only populated when hybrid/VLM image-description mode is enabled (Config.HYBRID_*);
    // base extraction (this adapter's default, Config.HYBRID_OFF) does not produce it.
    private String description;
}
