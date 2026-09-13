package org.synanton.extraction.adapter.stubs.stubs;

import org.synanton.extraction.spi.spi.model.AdapterResult;
import org.synanton.extraction.spi.spi.model.ExtractionFailure;
import org.synanton.extraction.spi.spi.model.ExtractionRequest;
import org.synanton.extraction.spi.spi.model.FeatureOutcome;
import org.synanton.extraction.spi.spi.port.ModalityAdapter;

import java.io.InputStream;
import java.util.Map;

/**
 * Capability-declining stub for the {@code audio/*} modality.
 *
 * <p>This adapter is registered so that the extraction plane never leaves audio media types
 * unmatched. It immediately declines all requests with
 * {@link ExtractionFailure#unsupportedMediaType(String)} and marks every feature
 * {@link FeatureOutcome#UNSUPPORTED}.
 */
class AudioAdapterStub implements ModalityAdapter {

    @Override
    public boolean supports(String mediaType) {
        return mediaType != null && mediaType.startsWith("audio/");
    }

    @Override
    public String processorId() {
        return "audio-stub";
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
