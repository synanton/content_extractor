package synanton.extraction.adapter.document.pdf;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One element from the OpenDataLoader PDF JSON output.
 * Maps the "kids" array items in the response.
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

    // Can be a String (text content) or an Object (table content with headers/rows)
    private JsonNode content;

    // Optional provenance hint from OpenDataLoader (e.g. "ocr", "embedded")
    @JsonAlias("content origin")
    private String contentOrigin;

    // For picture/image elements
    private String description;
}
