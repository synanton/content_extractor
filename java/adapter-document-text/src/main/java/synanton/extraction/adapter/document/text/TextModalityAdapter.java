package synanton.extraction.adapter.document.text;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import synanton.extraction.spi.model.AdapterResult;
import synanton.extraction.spi.model.ContentOrigin;
import synanton.extraction.spi.model.ElementBounds;
import synanton.extraction.spi.model.ElementType;
import synanton.extraction.spi.model.ExtractionFailure;
import synanton.extraction.spi.model.ExtractionOptions;
import synanton.extraction.spi.model.ExtractionRequest;
import synanton.extraction.spi.model.FeatureOutcome;
import synanton.extraction.spi.model.NormalizedDocument;
import synanton.extraction.spi.model.NormalizedElement;
import synanton.extraction.spi.port.ModalityAdapter;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ModalityAdapter} for plain-text and text-like document formats.
 *
 * <p>Backed by Apache Tika's {@link AutoDetectParser}. Supports:
 * <ul>
 *   <li>{@code text/plain}</li>
 *   <li>{@code text/html}</li>
 *   <li>{@code text/csv}</li>
 *   <li>{@code text/xhtml+xml}</li>
 *   <li>{@code application/xhtml+xml}</li>
 *   <li>{@code application/epub+zip}</li>
 * </ul>
 *
 * <p>The extracted text is split at blank lines into {@link ElementType#PARAGRAPH} elements.
 * No layout analysis, OCR, or table extraction is performed.
 */
@Slf4j
public class TextModalityAdapter implements ModalityAdapter {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "text/plain",
            "text/html",
            "text/csv",
            "text/xhtml+xml",
            "application/xhtml+xml",
            "application/epub+zip"
    );

    @Override
    public boolean supports(String mediaType) {
        return SUPPORTED_TYPES.contains(mediaType);
    }

    @Override
    public String processorId() {
        return "text-adapter-tika";
    }

    @Override
    public AdapterResult extract(ExtractionRequest request, InputStream source) {
        try {
            Metadata tikaMetadata = new Metadata();
            tikaMetadata.set(Metadata.CONTENT_TYPE, request.mediaType());

            // Use an unlimited buffer (-1) to capture the full document body.
            BodyContentHandler handler = new BodyContentHandler(-1);
            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(source, handler, tikaMetadata, new ParseContext());

            String fullText = handler.toString().trim();

            List<NormalizedElement> elements = buildElements(fullText);

            Map<String, String> docMetadata = extractDocMetadata(tikaMetadata);

            String flattenedText = elements.stream()
                    .map(NormalizedElement::text)
                    .filter(t -> t != null && !t.isBlank())
                    .reduce("", (a, b) -> a.isBlank() ? b : a + "\n" + b);

            NormalizedDocument document = new NormalizedDocument(
                    request.mediaType(), docMetadata, elements, flattenedText);

            Map<String, FeatureOutcome> featureStates = buildFeatureStates(request);
            return AdapterResult.success(document, featureStates);

        } catch (Exception e) {
            log.warn("Text extraction failed for contentRefId={}: {}",
                    request.contentRefId(), e.getMessage());
            return AdapterResult.failed(
                    ExtractionFailure.extractionFailed(e.getMessage()),
                    Map.of("text", FeatureOutcome.FAILED)
            );
        }
    }

    /**
     * Splits the Tika-extracted text at blank lines and wraps each paragraph
     * in a {@link NormalizedElement}.
     */
    private List<NormalizedElement> buildElements(String text) {
        if (text.isBlank()) {
            return List.of();
        }
        String[] paragraphs = text.split("\\n\\s*\\n+");
        List<NormalizedElement> elements = new ArrayList<>();
        for (int i = 0; i < paragraphs.length; i++) {
            String para = paragraphs[i].strip();
            if (!para.isBlank()) {
                elements.add(new NormalizedElement(
                        "e" + (i + 1),
                        ElementType.PARAGRAPH,
                        ElementBounds.absent(),
                        para,
                        ContentOrigin.EMBEDDED_TEXT,
                        0,
                        List.of(),
                        Map.of(),
                        null
                ));
            }
        }
        return elements;
    }

    /**
     * Copies all non-blank Tika metadata entries into a {@link LinkedHashMap} in insertion order.
     */
    private Map<String, String> extractDocMetadata(Metadata tikaMetadata) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : tikaMetadata.names()) {
            String value = tikaMetadata.get(name);
            if (value != null && !value.isBlank()) {
                result.put(name, value);
            }
        }
        return result;
    }

    /**
     * Derives the {@link FeatureOutcome} map from the request options and the media type.
     */
    private Map<String, FeatureOutcome> buildFeatureStates(ExtractionRequest request) {
        Map<String, FeatureOutcome> states = new LinkedHashMap<>();
        states.put("text", FeatureOutcome.APPLIED);

        ExtractionOptions options = request.options() != null
                ? request.options()
                : ExtractionOptions.defaults();

        boolean isStructured = "text/html".equals(request.mediaType())
                || "application/epub+zip".equals(request.mediaType())
                || "application/xhtml+xml".equals(request.mediaType())
                || "text/xhtml+xml".equals(request.mediaType());
        states.put("layout", isStructured ? FeatureOutcome.APPLIED : FeatureOutcome.NOT_APPLICABLE);

        // OCR is not needed for digital-text formats.
        states.put("ocr", Boolean.TRUE.equals(options.ocr())
                ? FeatureOutcome.NOT_APPLICABLE
                : FeatureOutcome.NOT_REQUESTED);

        // Table extraction is not implemented in this adapter.
        states.put("tables", Boolean.TRUE.equals(options.tables())
                ? FeatureOutcome.UNSUPPORTED
                : FeatureOutcome.NOT_REQUESTED);

        // Transcription is not applicable to document formats.
        states.put("transcription", Boolean.TRUE.equals(options.transcription())
                ? FeatureOutcome.NOT_APPLICABLE
                : FeatureOutcome.NOT_REQUESTED);

        // Embedded-image extraction is not implemented in this adapter.
        states.put("embeddedImages", Boolean.TRUE.equals(options.embeddedImages())
                ? FeatureOutcome.UNSUPPORTED
                : FeatureOutcome.NOT_REQUESTED);

        return states;
    }
}
