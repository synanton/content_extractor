package org.synanton.extraction.adapter.document.pdf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.synanton.extraction.spi.model.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real {@code org.opendataloader:opendataloader-pdf-core} library against a
 * real (spec-valid) PDF fixture — end to end through {@link OpenDataLoaderClient},
 * {@link OpenDataLoaderNormalizer} and {@link PdfModalityAdapter}. This is what actually
 * proves the OpenDataLoader integration works; {@link OpenDataLoaderNormalizerTest} only
 * covers mapping logic against hand-built objects, which can't catch a schema mismatch
 * against the real library (as happened before this test existed — the adapter previously
 * called a non-existent HTTP service and no test caught it).
 */
class OpenDataLoaderIntegrationTest {

    private static final Path REPORT_PDF = Path.of(
            "src/test/resources/fixtures/quarterly-report.pdf");
    private static final Path PATENT_PDF = Path.of(
            "src/test/resources/fixtures/einsteins-patents.pdf");


    @AfterAll
    static void shutdownLibrary() {
        org.opendataloader.pdf.api.OpenDataLoaderPDF.shutdown();
    }

    @Test
    void clientShouldExtractStructureFromPatentPdf() throws Exception {
        OpenDataLoaderClient client = new OpenDataLoaderClient(new ObjectMapper());

        OdlResponse response = client.extract(PATENT_PDF);

        assertThat(response.getNumberOfPages()).isEqualTo(15);
        assertThat(response.getKids()).isNotEmpty();
        assertThat(response.getKids().size()).isEqualTo(85);
        assertThat(response.getKids())
                .extracting(OdlElement::getType)
                .allMatch(type -> type != null && !type.isBlank());
        OdlElement paragraph22 = OdlElement.builder()
                .type("paragraph")
                .id(216)
                .pageNumber(5)
                .boundingBox(new double[]{72.0, 434.555, 523.371, 768.845})
                .font("Calibri")
                .fontSize(10.98)
                .textColor("[0.133]")
                .pdfuaTag("P")
                .content(new TextNode("Immediately after the 17th issue was published he came to Johann Laub8 " +
                        "and asked him to discuss Einstein’s papers in the next colloquium. Max Plank9 at Berlin also realized " +
                        "their importance. He was one of the few who understood relativity. Immediately, after the paper was " +
                        "published he gave a colloquium on relativity. The other man was the Polish professor Witkowski10, who" +
                        " after reading Einstein’s paper proclaimed to his colleagues, “A new Copernicus is born! Read Einstein’s" +
                        " paper.” Even though a few recognized his ability, he remained largely unknown to the academic world. " +
                        "For example, even in 1907 Max Born11 apparently was not aware of Einstein’s papers. In 1907, Einstein" +
                        " applied for a Privatdozentship at the University of Bern. Privatdozentship carried no salary, but only" +
                        " right to teach. However, it was denied due to a technical flaw in the application and only after " +
                        "Einstein corrected the technical flaw, he was granted Privatdozentship and formally, Einstein became a" +
                        " member of the academic world. In the meantime, he even contemplated for applying for a teacher’s " +
                        "position in a school. Gradually, his reputation as a mathematical physicist grew and he was offered his" +
                        " first faculty position, associate professor of the theoretical physics at the University of Zurich. " +
                        "Einstein left his job at the Patent office and joined the University of Zurich on October 15, 1909. " +
                        "Thereafter, he continued to rise in ladder. In 1911, he moved to Prague University as a full professor," +
                        " a year later, he was appointed as full professor at ETH, Zurich, his alma‐mater. In 1914, he was " +
                        "appointed Director of the Kaiser Wilhelm Institute for Physics (1914–1932) and a professor at the" +
                        " Humboldt University of Berlin, with a special clause in his contract that freed him from teaching " +
                        "obligations. In the meantime, he was working for a theory of gravity. The work started in 1907 and " +
                        "after eight long years, in 1915, he could finalize his theory of gravity. He christened it as the " +
                        "General Theory of Relativity. In general relativity, Einstein gave a geometric picture of gravity: in" +
                        " presence of mass, space‐time is curved, and gravity is nothing but curvature of the space‐time. In" +
                        " 1921, he received Nobel Prize in Physics for the explanation of the photoelectric effect. In 1933," +
                        " when Hitler assumed power and started persecuting Jews, Einstein left Germany and joined Princeton" +
                        " University in USA, where he continued until his death on April 18, 1955. In his later years, he tried " +
                        "to unify all the fundamental forces but could not succeed."))
                .build();

        assertThat(response.getKids().get(22)).isEqualTo(paragraph22);

        OpenDataLoaderNormalizer normalizer = new OpenDataLoaderNormalizer();
        NormalizedDocument document = normalizer.normalize(response, "application/pdf");

        assertThat(document.flattenedText()).startsWith("Einstein’s Patents and Inventions");
        assertThat(document.elements()).isNotEmpty();
        assertThat(document.elements())
                .extracting(NormalizedElement::type)
                .contains(ElementType.HEADING);
        assertThat(document.elements().get(43).text()).isEqualTo("""
                Collaborator | Date | Patent No. | Description
                Leo Szilard | 01/12/1928 | FR647838 | Refrigerating machine with pumping of liquid effected by intermittently increasing the vapour pressure.
                Leo Szilard | 28/11/1929 | FR670428 | Refrigerating machine
                Leo Szilard | 15/11/1928 | GB282428 | Improvements relating to refrigerating apparatus.
                Leo Szilard | 26/06/1930 | GB303065 | Electrodynamic movement of fluid metals particularly for refrigerating machine.
                Leo Szilard | 09/03/1931 | GB344881 | Pump especially for refrigerating machines.
                Leo Szilard | 05/12/1929 | HU102079 | Refrigerator
                Leo Szilard | 11/11/1930 | US102079 | Refrigeration
                Leo Szilard | 26/05/1933 | AT133386 | Condenser for refrigeratot
                Leo Szilard | 16/08/1930 | CH140217 | Refrigerator
                Leo Szilard | 27/07/1933 | DE554959 | Apparatus for movement of fluid metals in refrigerators
                Leo Szilard | 04/07/1933 | DE565614 | Compressor
                Leo Szilard | 30/05/1933 | DE563403 | Refrigerator
                Leo Szilard | 08/04/1933 | DE562300 | Refrigerator
                Leo Szilard | 20/09/1933 | DE562040 | Electromagnetic appliance for generating oscillatory motion
                Leo Szilard | 13/04/1933 | DE561904 | Refrigerator
                Leo Szilard | 16/09/1933 | DE556535 | Pumps especially for refrigerators
                Gustav Bucky | 27/10/1936 | US2058562 | Light intensity self‐adjusting camera
                Rudolf Goldschmidt | 10/01/1934 | DE590783 | Electromagnetic sound reproduction apparatus
                 | 27/10/1936 | US101756S | Design of a blouse""");
    }

    @Test
    void clientShouldExtracStructureFromRepostPdf() throws Exception {
        byte[] pdfBytes = Files.readAllBytes(REPORT_PDF);

        OpenDataLoaderClient client = new OpenDataLoaderClient(new ObjectMapper());

        OdlResponse response = client.extract(pdfBytes);

        assertThat(response.getNumberOfPages()).isEqualTo(1);
        assertThat(response.getKids()).isNotEmpty();
        assertThat(response.getKids())
                .extracting(OdlElement::getType)
                .allMatch(type -> type != null && !type.isBlank());

        OpenDataLoaderNormalizer normalizer = new OpenDataLoaderNormalizer();
        NormalizedDocument document = normalizer.normalize(response, "application/pdf");

        assertThat(document.flattenedText()).contains("Quarterly Report");
        assertThat(document.elements()).isNotEmpty();
        assertThat(document.elements())
                .extracting(el -> el.type())
                .contains(ElementType.HEADING);
    }

    @Test
    void adapterShouldSucceedEndToEndForRealPdf() throws Exception {
        PdfModalityAdapter adapter = new PdfModalityAdapter(
                new OpenDataLoaderClient(new ObjectMapper()));

        ExtractionRequest request = new ExtractionRequest(
                "op-1", "tenant-1", "idem-1", "content-ref-1",
                new ObjectRef("bucket", "key", null, null, 0L),
                "application/pdf", ExtractionOptions.defaults(), "normal",
                Instant.now().plusSeconds(3600));
        InputStream source = new ByteArrayInputStream(Files.readAllBytes(REPORT_PDF));

        AdapterResult result = adapter.extract(request, source);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.document().flattenedText()).contains("Quarterly Report");
    }
}
