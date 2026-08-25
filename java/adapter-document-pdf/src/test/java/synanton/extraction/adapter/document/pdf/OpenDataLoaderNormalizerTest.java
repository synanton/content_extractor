package synanton.extraction.adapter.document.pdf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import synanton.extraction.spi.model.ElementType;
import synanton.extraction.spi.model.NormalizedDocument;
import synanton.extraction.spi.model.NormalizedElement;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenDataLoaderNormalizerTest {

    private OpenDataLoaderNormalizer normalizer;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        normalizer = new OpenDataLoaderNormalizer();
        mapper = new ObjectMapper();
    }

    @Test
    void shouldMapHeadingElement() {
        OdlElement headingElement = new OdlElement();
        headingElement.setType("heading");
        headingElement.setId(1);
        headingElement.setPageNumber(1);
        headingElement.setBoundingBox(new double[]{72, 700, 540, 730});
        headingElement.setHeadingLevel(1);
        headingElement.setContent(new TextNode("Introduction"));

        OdlResponse response = new OdlResponse();
        response.setNumberOfPages(1);
        response.setTitle("Test PDF");
        response.setKids(List.of(headingElement));

        NormalizedDocument doc = normalizer.normalize(response, "application/pdf");

        assertThat(doc.elements()).hasSize(1);
        NormalizedElement element = doc.elements().get(0);
        assertThat(element.type()).isEqualTo(ElementType.HEADING);
        assertThat(element.level()).isEqualTo(1);
        assertThat(element.text()).isEqualTo("Introduction");
        assertThat(element.bounds().page()).isEqualTo(1);
    }

    @Test
    void shouldMapParagraphElement() {
        OdlElement paragraphElement = new OdlElement();
        paragraphElement.setType("paragraph");
        paragraphElement.setPageNumber(1);
        paragraphElement.setContent(new TextNode("Some text here"));

        OdlResponse response = new OdlResponse();
        response.setKids(List.of(paragraphElement));

        NormalizedDocument doc = normalizer.normalize(response, "application/pdf");

        assertThat(doc.elements()).hasSize(1);
        NormalizedElement element = doc.elements().get(0);
        assertThat(element.type()).isEqualTo(ElementType.PARAGRAPH);
        assertThat(element.text()).isEqualTo("Some text here");
    }

    @Test
    void shouldMapTableElementWithTextRepresentation() {
        ObjectNode tableContent = mapper.createObjectNode();
        tableContent.set("headers", mapper.createArrayNode().add("Col1").add("Col2"));
        tableContent.set("rows", mapper.createArrayNode()
                .add(mapper.createArrayNode().add("A").add("B")));

        OdlElement tableElement = new OdlElement();
        tableElement.setType("table");
        tableElement.setPageNumber(2);
        tableElement.setContent(tableContent);

        OdlResponse response = new OdlResponse();
        response.setKids(List.of(tableElement));

        NormalizedDocument doc = normalizer.normalize(response, "application/pdf");

        assertThat(doc.elements()).hasSize(1);
        NormalizedElement element = doc.elements().get(0);
        assertThat(element.type()).isEqualTo(ElementType.TABLE);
        assertThat(element.text()).contains("Col1");
        assertThat(element.text()).contains("Col2");
    }

    @Test
    void shouldMapPictureElementWithDescription() {
        OdlElement pictureElement = new OdlElement();
        pictureElement.setType("picture");
        pictureElement.setPageNumber(2);
        pictureElement.setDescription("An architecture diagram");

        OdlResponse response = new OdlResponse();
        response.setKids(List.of(pictureElement));

        NormalizedDocument doc = normalizer.normalize(response, "application/pdf");

        assertThat(doc.elements()).hasSize(1);
        NormalizedElement element = doc.elements().get(0);
        assertThat(element.type()).isEqualTo(ElementType.IMAGE);
        assertThat(element.alternateRepresentation()).isEqualTo("An architecture diagram");
    }

    @Test
    void shouldMapFormulaElement() {
        OdlElement formulaElement = new OdlElement();
        formulaElement.setType("formula");
        formulaElement.setPageNumber(1);
        formulaElement.setContent(new TextNode("\\frac{f(x+h)-f(x)}{h}"));

        OdlResponse response = new OdlResponse();
        response.setKids(List.of(formulaElement));

        NormalizedDocument doc = normalizer.normalize(response, "application/pdf");

        assertThat(doc.elements()).hasSize(1);
        NormalizedElement element = doc.elements().get(0);
        assertThat(element.type()).isEqualTo(ElementType.FORMULA);
        assertThat(element.alternateRepresentation()).contains("frac");
    }

    @Test
    void shouldIncludeDocumentMetadata() {
        OdlResponse response = new OdlResponse();
        response.setTitle("My PDF");
        response.setAuthor("Author");
        response.setNumberOfPages(5);

        NormalizedDocument doc = normalizer.normalize(response, "application/pdf");

        assertThat(doc.metadata()).containsEntry("title", "My PDF");
        assertThat(doc.metadata()).containsEntry("author", "Author");
        assertThat(doc.metadata()).containsEntry("pageCount", "5");
    }

    @Test
    void shouldBuildFlattenedTextFromElements() {
        OdlElement headingElement = new OdlElement();
        headingElement.setType("heading");
        headingElement.setPageNumber(1);
        headingElement.setHeadingLevel(1);
        headingElement.setContent(new TextNode("Section"));

        OdlElement paragraphElement = new OdlElement();
        paragraphElement.setType("paragraph");
        paragraphElement.setPageNumber(1);
        paragraphElement.setContent(new TextNode("Content"));

        OdlResponse response = new OdlResponse();
        response.setKids(List.of(headingElement, paragraphElement));

        NormalizedDocument doc = normalizer.normalize(response, "application/pdf");

        assertThat(doc.flattenedText()).contains("Section");
        assertThat(doc.flattenedText()).contains("Content");
    }
}
