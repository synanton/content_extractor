package synanton.extraction.adapter.document.pdf;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Root response from the OpenDataLoader PDF service.
 * Represents the structured JSON output for one processed PDF.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OdlResponse {

    @JsonAlias("file name")
    private String fileName;

    @JsonAlias("number of pages")
    private int numberOfPages;

    private String author;
    private String title;

    @JsonAlias("creation date")
    private String creationDate;

    @JsonAlias("modification date")
    private String modificationDate;

    private List<OdlElement> kids = new ArrayList<>();
}
