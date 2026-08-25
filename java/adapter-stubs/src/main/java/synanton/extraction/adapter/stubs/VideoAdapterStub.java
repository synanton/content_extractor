package synanton.extraction.adapter.stubs;

import synanton.extraction.spi.model.AdapterResult;
import synanton.extraction.spi.model.ExtractionFailure;
import synanton.extraction.spi.model.ExtractionRequest;
import synanton.extraction.spi.model.FeatureOutcome;
import synanton.extraction.spi.port.ModalityAdapter;

import java.io.InputStream;
import java.util.Map;

/**
 * Capability-declining stub for the {@code video/*} modality.
 *
 * <p>This adapter is registered so that the extraction plane never leaves video media types
 * unmatched. It immediately declines all requests with
 * {@link ExtractionFailure#unsupportedMediaType(String)} and marks every feature
 * {@link FeatureOutcome#UNSUPPORTED}.
 */
class VideoAdapterStub implements ModalityAdapter {

    @Override
    public boolean supports(String mediaType) {
        return mediaType != null && mediaType.startsWith("video/");
    }

    @Override
    public String processorId() {
        return "video-stub";
    }

    @Override
    public AdapterResult extract(ExtractionRequest request, InputStream source) {
        return AdapterResult.failed(
                ExtractionFailure.unsupportedMediaType(request.mediaType()),
                buildUnsupportedFeatures()
        );
    }

    private static Map<String, FeatureOutcome> buildUnsupportedFeatures() {
        return Map.of(
                "text",           FeatureOutcome.UNSUPPORTED,
                "layout",         FeatureOutcome.UNSUPPORTED,
                "ocr",            FeatureOutcome.UNSUPPORTED,
                "transcription",  FeatureOutcome.UNSUPPORTED,
                "tables",         FeatureOutcome.UNSUPPORTED,
                "embeddedImages", FeatureOutcome.UNSUPPORTED,
                "sceneAnalysis",  FeatureOutcome.UNSUPPORTED
        );
    }
}
