package synanton.extraction.adapter.document.pdf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import synanton.extraction.spi.model.AdapterResult;
import synanton.extraction.spi.model.ContentOrigin;
import synanton.extraction.spi.model.ExtractionFailure;
import synanton.extraction.spi.model.ExtractionOptions;
import synanton.extraction.spi.model.ExtractionRequest;
import synanton.extraction.spi.model.FeatureOutcome;
import synanton.extraction.spi.model.NormalizedDocument;
import synanton.extraction.spi.port.ModalityAdapter;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Modality adapter for {@code application/pdf} backed by the OpenDataLoader HTTP service.
 *
 * <p>When the OpenDataLoader service URL is not configured, this adapter returns
 * {@link AdapterResult#unsupported(String)} so the gateway can report the feature as
 * unsupported rather than fail with an obscure error.
 */
public class PdfModalityAdapter implements ModalityAdapter {

    private static final Logger log = LoggerFactory.getLogger(PdfModalityAdapter.class);

    private final OpenDataLoaderClient client;    // null when not configured
    private final OpenDataLoaderNormalizer normalizer;

    /** Constructor for when OpenDataLoader is configured. */
    public PdfModalityAdapter(OpenDataLoaderClient client) {
        this.client = client;
        this.normalizer = new OpenDataLoaderNormalizer();
    }

    /** Constructor for when OpenDataLoader is NOT configured (returns UNSUPPORTED). */
    public PdfModalityAdapter() {
        this.client = null;
        this.normalizer = new OpenDataLoaderNormalizer();
    }

    @Override
    public boolean supports(String mediaType) {
        return "application/pdf".equals(mediaType);
    }

    @Override
    public String processorId() {
        return "opendataloader-pdf";
    }

    @Override
    public AdapterResult extract(ExtractionRequest request, InputStream source) {
        if (client == null) {
            log.info("OpenDataLoader not configured; declining PDF for contentRefId={}",
                    request.contentRefId());
            return AdapterResult.unsupported(request.mediaType());
        }

        try {
            byte[] pdfBytes = source.readAllBytes();
            log.debug("Sending PDF to OpenDataLoader, contentRefId={}, bytes={}",
                    request.contentRefId(), pdfBytes.length);

            OdlResponse odlResponse = client.extract(pdfBytes);
            NormalizedDocument document = normalizer.normalize(odlResponse, request.mediaType());

            Map<String, FeatureOutcome> featureStates = buildFeatureStates(request, document, odlResponse);
            return AdapterResult.success(document, featureStates);

        } catch (OpenDataLoaderException e) {
            log.warn("OpenDataLoader extraction failed for contentRefId={}: {}",
                    request.contentRefId(), e.getMessage());
            return AdapterResult.failed(
                    ExtractionFailure.extractionFailed(e.getMessage()),
                    Map.of("text", FeatureOutcome.FAILED, "layout", FeatureOutcome.FAILED)
            );
        } catch (Exception e) {
            log.error("Unexpected error extracting PDF contentRefId={}", request.contentRefId(), e);
            return AdapterResult.failed(
                    ExtractionFailure.extractionFailed(e.getMessage()),
                    Map.of("text", FeatureOutcome.FAILED)
            );
        }
    }

    private Map<String, FeatureOutcome> buildFeatureStates(
            ExtractionRequest request,
            NormalizedDocument document,
            OdlResponse response) {
        Map<String, FeatureOutcome> states = new LinkedHashMap<>();
        ExtractionOptions options = request.options() != null ? request.options() : ExtractionOptions.defaults();

        states.put("text", FeatureOutcome.APPLIED);
        states.put("layout", FeatureOutcome.APPLIED);

        boolean hasTables = response.getKids() != null && response.getKids().stream()
                .anyMatch(k -> "table".equalsIgnoreCase(k.getType()));
        states.put("tables", Boolean.TRUE.equals(options.tables())
                ? (hasTables ? FeatureOutcome.APPLIED : FeatureOutcome.NOT_APPLICABLE)
                : FeatureOutcome.NOT_REQUESTED);

        boolean ocrRequested = Boolean.TRUE.equals(options.ocr());
        boolean hasOcrProvenance = document.elements().stream()
                .anyMatch(el -> el.contentOrigin() == ContentOrigin.OCR);
        if (ocrRequested) {
            states.put("ocr", hasOcrProvenance ? FeatureOutcome.APPLIED : FeatureOutcome.FAILED);
        } else {
            states.put("ocr", FeatureOutcome.NOT_REQUESTED);
        }

        boolean hasImages = response.getKids() != null && response.getKids().stream()
                .anyMatch(k -> k.getType() != null
                        && (k.getType().equalsIgnoreCase("picture")
                            || k.getType().equalsIgnoreCase("image")));
        states.put("embeddedImages", Boolean.TRUE.equals(options.embeddedImages())
                ? (hasImages ? FeatureOutcome.APPLIED : FeatureOutcome.NOT_APPLICABLE)
                : FeatureOutcome.NOT_REQUESTED);

        states.put("transcription", FeatureOutcome.NOT_APPLICABLE);
        states.put("sceneAnalysis", Boolean.TRUE.equals(options.sceneAnalysis())
                ? FeatureOutcome.UNSUPPORTED
                : FeatureOutcome.NOT_REQUESTED);

        return states;
    }
}
